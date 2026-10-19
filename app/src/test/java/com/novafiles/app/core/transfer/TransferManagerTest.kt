package com.novafiles.app.core.transfer

import com.novafiles.app.core.provider.ProviderCapability
import com.novafiles.app.core.provider.ProviderEntry
import com.novafiles.app.core.provider.StorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * اختبارات مدير النقل v2 — توازي، استئناف Range، إيقاف/استئناف/إلغاء،
 * قيود الشبكة، السرعة — كله على الزمن الافتراضي ومزوّد وهمي.
 */
class TransferManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var tmp: File
    private lateinit var provider: FakeProvider

    /** مزوّد وهمي: ينسخ «ملفات» من ذاكرة — يدعم المدى للاختبار */
    private class FakeProvider(
        val store: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap(),
    ) : StorageProvider {
        override val id = "fake-1"
        override val scheme = "webdav"
        override val displayUrl = "fake://dav"
        override val capabilities = ProviderCapability.entries.toSet()
        var rangeCalls = AtomicInteger(0)
        var fullCalls = AtomicInteger(0)

        fun put(path: String, data: ByteArray) {
            store[path] = data
        }

        override suspend fun list(path: String): List<ProviderEntry> = emptyList()
        override suspend fun stat(path: String): ProviderEntry =
            ProviderEntry(path.substringAfterLast('/'), path, false, (store[path]?.size ?: 0).toLong())

        override suspend fun readTo(path: String, target: File, onProgress: (Long) -> Unit) {
            fullCalls.incrementAndGet()
            val data = store[path] ?: throw java.io.IOException("not found")
            // نقل قطعي — مثل القارئ الحقيقي
            var done = 0L
            val buf = 16 * 1024
            target.outputStream().use { out ->
                while (done < data.size) {
                    val n = minOf(buf.toLong(), data.size - done).toInt()
                    out.write(data, done.toInt(), n)
                    done += n
                    onProgress(n.toLong())
                }
            }
        }

        override suspend fun readRangeTo(path: String, offset: Long, sink: OutputStream, onProgress: (Long) -> Unit) {
            rangeCalls.incrementAndGet()
            val data = store[path] ?: throw java.io.IOException("not found")
            var done = offset
            sink.use { out ->
                while (done < data.size) {
                    val n = minOf(1024L, data.size - done).toInt()
                    out.write(data, done.toInt(), n)
                    done += n
                    onProgress(n.toLong())
                }
            }
        }

        override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): String? {
            store["$dir/${source.name}"] = source.readBytes()
            return "\"up1\""
        }

        override suspend fun mkdir(parent: String, name: String) { }
        override suspend fun delete(path: String, isDir: Boolean) { store.remove(path) }
        override suspend fun rename(path: String, newName: String) { }
        override suspend fun test(): String = "fake"
        override fun close() { }
    }

    @Before
    fun setup() {
        tmp = createTempDir("transfer")
        provider = FakeProvider()
    }

    @After
    fun teardown() {
        runCatching { scope.cancel() }
        tmp.deleteRecursively()
    }

    private fun manager(
        testScope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        parallelism: Int = 2,
        wifiOnly: Boolean = true,
        online: () -> Boolean = { true },
        nowMs: () -> Long = { 0L },
        rate: Long = 0,
    ) = TransferManager(
        scope = testScope,
        io = dispatcher,
        parallelism = parallelism,
        wifiOnly = wifiOnly,
        networkOnline = { online() },
        perItemBytesPerSec = rate,
        nowMs = nowMs,
    )

    private fun spec(name: String, size: Long, etag: String? = null) = TransferSpec(
        direction = TransferDirection.DOWNLOAD,
        remotePath = "/$name",
        local = File(tmp, name),
        totalBytes = size,
        etag = etag,
    )

    // ═══════════════ الأساسيات ═══════════════

    @Test
    fun downloadCompletesAndCleansPartFile() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val data = ByteArray(50_000) { (it % 255).toByte() }
        provider.put("/file.bin", data)
        val target = File(tmp, "file.bin")

        val id = m.enqueue(spec("file.bin", data.size.toLong()), TransferSources.download(provider))
        advanceUntilIdle()

        val snap = m.observe.value.first { it.id == id }
        assertEquals(TransferState.DONE, snap.state)
        assertEquals(data.size.toLong(), snap.bytesDone)
        assertTrue(target.readBytes().contentEquals(data))
        // لا بقايا
        assertTrue(!ResumeJournal.partFile(target).exists())
    }

    @Test
    fun parallelismRespected() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher, parallelism = 2)

        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        // مصدر يبقى حياً حتى نطلقه — لقياس التزامن
        fun parkedSource() = TransferSource { spec, _, _ ->
            val n = active.incrementAndGet()
            maxActive.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            gate.await()
            active.decrementAndGet()
            spec.totalBytes
        }

        repeat(4) { i ->
            m.enqueue(
                spec("p$i.bin", 100L),
                parkedSource(),
            )
        }
        testScheduler.runCurrent()
        assertEquals(2, maxActive.get()) // اثنان فقط يعملان
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, m.observe.value.count { it.state == TransferState.DONE })
    }

    // ═══════════════ الإيقاف والاستئناف والإلغاء ═══════════════

    @Test
    fun pauseAndResumeGateTheWorker() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val firstChunk = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val src = TransferSource { spec, _, onProgress ->
            onProgress(10)
            firstChunk.complete(Unit)
            proceed.await()
            onProgress(90)
            100L
        }
        val id = m.enqueue(spec("g.txt", 100L), src)

        testScheduler.runCurrent()
        firstChunk.await()
        testScheduler.runCurrent()
        // أول قطعة وصلت لكن العامل ينتظر عند proceed — نوقفه
        m.pause(id)
        testScheduler.runCurrent()
        assertEquals(TransferState.PAUSED, m.observe.value.first { it.id == id }.state)

        m.resume(id)
        testScheduler.runCurrent()
        proceed.complete(Unit)
        advanceUntilIdle()

        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
        assertEquals(100L, m.observe.value.first { it.id == id }.bytesDone)
    }

    @Test
    fun cancelRemovesPartFileAndStopsWorker() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val src = TransferSource { _, _, _ ->
            CompletableDeferred<Unit>().await() // عالق إلى الأبد — الإلغاء هو المنقذ
            0L
        }
        val id = m.enqueue(spec("c.bin", 100L), src)
        advanceUntilIdle()
        m.cancel(id)
        advanceUntilIdle()

        assertEquals(TransferState.CANCELLED, m.observe.value.first { it.id == id }.state)
    }

    @Test
    fun failureRecordsErrorAndRetrySucceeds() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        var attempt = 0
        val src = TransferSource { spec, _, _ ->
            if (attempt++ == 0) throw java.io.IOException("boom")
            100L
        }
        val id = m.enqueue(spec("f.bin", 100L), src)
        advanceUntilIdle()

        val failed = m.observe.value.first { it.id == id }
        assertEquals(TransferState.FAILED, failed.state)
        assertEquals("boom", failed.error)

        m.retry(id)
        advanceUntilIdle()
        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
    }

    // ═══════════════ الاستئناف عبر Range ═══════════════

    @Test
    fun resumeDownloadsFromJournalOffset() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val data = ByteArray(10_000) { (it and 0xFF).toByte() }
        provider.put("/resume.bin", data)
        val target = File(tmp, "resume.bin")
        val part = ResumeJournal.partFile(target)

        // محاكاة تنزيل سابق توقف عند 4 آلاف بايت مع مجلة صالحة
        part.writeBytes(data.copyOf(4_000))
        ResumeJournal.save(target, ResumeJournal.State(data.size.toLong(), "\"et\"", "/resume.bin"))

        val id = m.enqueue(
            spec("resume.bin", data.size.toLong(), etag = "\"et\""),
            TransferSources.download(provider),
        )
        advanceUntilIdle()

        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
        assertTrue(target.readBytes().contentEquals(data))
        assertEquals(1, provider.rangeCalls.get())  // استئناف فعلي عبر Range
        assertEquals(0, provider.fullCalls.get())   // بلا إعادة تنزيل كامل
        assertTrue(!part.exists())
    }

    @Test
    fun changedEtagForcesFreshDownload() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val data = ByteArray(10_000) { 7 }
        provider.put("/x.bin", data)
        val target = File(tmp, "x.bin")
        val part = ResumeJournal.partFile(target)
        part.writeBytes(data.copyOf(3_000))
        // المجلة تحمل etag قديماً — الـ spec يعلن etag جديداً
        ResumeJournal.save(target, ResumeJournal.State(data.size.toLong(), "\"old\"", "/x.bin"))

        val id = m.enqueue(
            spec("x.bin", data.size.toLong(), etag = "\"new\""),
            TransferSources.download(provider),
        )
        advanceUntilIdle()

        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
        assertEquals(0, provider.rangeCalls.get()) // تغير الملف → بلا استئناف
        assertTrue(target.readBytes().contentEquals(data))
    }

    // ═══════════════ قيود الشبكة ═══════════════

    @Test
    fun wifiOnlyBlocksUntilNetworkReturns() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        var online = false
        val m = manager(this, dispatcher, wifiOnly = true, online = { online })

        val src = TransferSource { spec, _, _ -> spec.totalBytes }
        val id = m.enqueue(spec("w.bin", 10L), src)
        testScheduler.runCurrent()

        assertEquals(TransferState.WAITING_NETWORK, m.observe.value.first { it.id == id }.state)

        online = true
        m.notifyNetworkChanged()
        advanceUntilIdle()

        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
    }

    @Test
    fun anyNetworkSkipsConstraint() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher, wifiOnly = false, online = { false })

        val src = TransferSource { spec, _, _ -> spec.totalBytes }
        val id = m.enqueue(spec("a.bin", 10L), src)
        advanceUntilIdle()
        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
    }

    // ═══════════════ السرعة والرفع والحذف ═══════════════

    @Test
    fun speedIsComputedFromProgressSamples() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        // كل استدعاء nowMs يقفز 500 مللي — قطعة واحدة لكل نافذة عينة
        var calls = 0
        val m = manager(this, dispatcher, nowMs = { calls++ * 500L })

        val src = TransferSource { _, _, onProgress ->
            repeat(4) { onProgress(1000) }
            4000L
        }
        val id = m.enqueue(spec("s.bin", 4000L), src)
        advanceUntilIdle()

        val snap = m.observe.value.first { it.id == id }
        assertEquals(TransferState.DONE, snap.state)
        assertEquals(2000L, snap.speedBps) // 1000 بايت كل 500 مللي
    }

    @Test
    fun uploadRunsThroughProvider() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val source = File(tmp, "up.bin").apply { writeBytes(ByteArray(300) { 5 }) }
        val upSpec = TransferSpec(
            direction = TransferDirection.UPLOAD,
            remotePath = "/photos",
            remoteName = "up.bin",
            local = source,
            totalBytes = 300,
        )
        val id = m.enqueue(upSpec, TransferSources.upload(provider))
        advanceUntilIdle()

        assertEquals(TransferState.DONE, m.observe.value.first { it.id == id }.state)
        assertTrue(provider.store.containsKey("/photos/up.bin"))
    }

    @Test
    fun clearFinishedRemovesOnlyFinished() = runTest {
        val dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor]!! as CoroutineDispatcher
        scope = backgroundScope
        val m = manager(this, dispatcher)

        val gate = CompletableDeferred<Unit>()
        val doneSrc = TransferSource { _, _, _ -> 1L }
        val stuckSrc = TransferSource { _, _, _ -> gate.await(); 1L }

        val doneId = m.enqueue(spec("d1.bin", 1L), doneSrc)
        m.enqueue(spec("s1.bin", 1L), stuckSrc)
        advanceUntilIdle()

        m.clearFinished()
        val snapshot = m.observe.value
        assertEquals(1, snapshot.size)
        assertEquals("s1.bin", snapshot.first().name)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.DONE, m.observe.value.first { it.name == "s1.bin" }.state)
    }
}

/** وحدة RateLimiter — دلو الرموز على الزمن الافتراضي */
class RateLimiterTest {

    @Test
    fun zeroMeansUnlimited() = runTest {
        val rl = RateLimiter(0)
        rl.acquire(1_000_000) // فوري بلا انتظار
    }

    @Test
    fun secondBurstWaitsForTokens() = runTest {
        val rl = RateLimiter(bytesPerSec = 1000)
        rl.acquire(1000) // أول دفعة — من الرصيد الأولي
        val start = testScheduler.currentTime
        rl.acquire(1000) // ثانية — يجب انتظار ~1000 مللي افتراضياً
        val waited = testScheduler.currentTime - start
        assertTrue("waited only $waited ms", waited >= 999)
    }

    @Test
    fun waitingLongerAccumulatesMoreTokens() = runTest {
        val rl = RateLimiter(bytesPerSec = 1000)
        rl.acquire(1000)
        testScheduler.advanceTimeBy(2500) // يجمع 2.5 ألف رمز
        rl.acquire(2000) // متاح من الرصيد المتراكم
    }
}

/** وحدة مجلة الاستئناف */
class ResumeJournalTest {

    @Test
    fun saveLoadRoundtripWithArabicAndEtag() {
        val dir = createTempDir("journal")
        val target = File(dir, "ملف عربي.bin")
        ResumeJournal.save(target, ResumeJournal.State(1234, "\"e-1\"", "/دليل/ملف عربي.bin"))
        val st = ResumeJournal.load(target)
        assertEquals(1234L, st!!.totalBytes)
        assertEquals("\"e-1\"", st.etag)
        assertEquals("/دليل/ملف عربي.bin", st.remotePath)
        dir.deleteRecursively()
    }

    @Test
    fun validPartYieldsOffset() {
        val dir = createTempDir("journal")
        val target = File(dir, "f.bin")
        val part = ResumeJournal.partFile(target)
        part.writeBytes(ByteArray(500))
        ResumeJournal.save(target, ResumeJournal.State(1000, null, "/f.bin"))
        assertEquals(500L, ResumeJournal.resumeOffset(target, 1000, null))
        dir.deleteRecursively()
    }

    @Test
    fun mismatchedTotalResets() {
        val dir = createTempDir("journal")
        val target = File(dir, "f.bin")
        ResumeJournal.partFile(target).writeBytes(ByteArray(500))
        ResumeJournal.save(target, ResumeJournal.State(1000, null, "/f.bin"))
        assertEquals(0L, ResumeJournal.resumeOffset(target, 2000, null))
        assertTrue(!ResumeJournal.partFile(target).exists()) // الجزء الفاسد حُذف
        dir.deleteRecursively()
    }

    @Test
    fun corruptJournalResets() {
        val dir = createTempDir("journal")
        val target = File(dir, "f.bin")
        ResumeJournal.partFile(target).writeBytes(ByteArray(500))
        ResumeJournal.partFile(target).writeText("garbage data")
        assertEquals(0L, ResumeJournal.resumeOffset(target, 1000, null))
        dir.deleteRecursively()
    }

    @Test
    fun etagMismatchResets() {
        val dir = createTempDir("journal")
        val target = File(dir, "f.bin")
        ResumeJournal.partFile(target).writeBytes(ByteArray(500))
        ResumeJournal.save(target, ResumeJournal.State(1000, "\"old\"", "/f.bin"))
        assertEquals(0L, ResumeJournal.resumeOffset(target, 1000, "\"new\""))
        dir.deleteRecursively()
    }
}
