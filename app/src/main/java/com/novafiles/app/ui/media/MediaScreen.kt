package com.novafiles.app.ui.media

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.novafiles.app.R
import com.novafiles.app.core.media.InMemoryMediaResumeStore
import com.novafiles.app.core.media.MediaPlaybackEngine
import com.novafiles.app.core.media.MediaRepeatMode
import com.novafiles.app.core.media.MediaResumePolicy
import com.novafiles.app.core.media.MediaResumeStore
import com.novafiles.app.core.media.PlaybackSpeed
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSDark
import java.io.File

/**
 * v0.31.0 — مشغل الوسائط المدمج (media3) بنمط iOS.
 *
 * ورقة كاملة الشاشة: سرعات ×0.5–×2، تكرار (إيقاف/مقطع/كل)، شريط تمرير
 * دقيق بأزرار ±10 ثوانٍ، ومتابعة آخر موضع عبر مخزن الاستئناف.
 * كل القرارات (سرعات/تكرار/استئناف/تنسيق وقت) من محرك التشغيل الخالص.
 */

/** مخزن استئناف فوق SharedPreferences — طبقة أندرويد فوق الواجهة الخالصة */
class SharedPrefsMediaResumeStore(context: android.content.Context) : MediaResumeStore {
    private val prefs = context.getSharedPreferences("nova_media_resume", android.content.Context.MODE_PRIVATE)

    override fun save(key: String, positionMs: Long, durationMs: Long) {
        prefs.edit()
            .putLong("pos_$key", positionMs)
            .putLong("dur_$key", durationMs)
            .putLong("at_$key", System.currentTimeMillis())
            .apply()
    }

    override fun load(key: String): com.novafiles.app.core.media.MediaResumePoint? {
        if (!prefs.contains("pos_$key")) return null
        return com.novafiles.app.core.media.MediaResumePoint(
            positionMs = prefs.getLong("pos_$key", 0L),
            durationMs = prefs.getLong("dur_$key", 0L),
            savedAt = prefs.getLong("at_$key", 0L),
        )
    }

    override fun clear(key: String) {
        prefs.edit().remove("pos_$key").remove("dur_$key").remove("at_$key").apply()
    }
}

/** ربط نمط التكرار الخالص بثوابت media3 */
private fun MediaRepeatMode.toPlayerRepeat(): Int = when (this) {
    MediaRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    MediaRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    MediaRepeatMode.ALL -> Player.REPEAT_MODE_ALL
}

/** تسمية سرعة ثابتة الرقمية (0.5 / 1 / 1.25 …) — لا أرقام محلية غامضة */
private fun speedLabel(x: Float): String =
    if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()

@Composable
fun MediaScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = IOSDark
    val file = remember(path) { File(path) }
    val isVideo = remember(path) {
        path.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")
    }
    val resumeStore = remember { SharedPrefsMediaResumeStore(context) }
    val resumeKey = remember(path) { file.absolutePath }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }

    // ── الحالة المعروضة ──
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableStateOf(PlaybackSpeed.S100) }
    var repeat by remember { mutableStateOf(MediaRepeatMode.OFF) }
    var speedSheet by remember { mutableStateOf(false) }
    var resumedLabel by remember { mutableStateOf<String?>(null) }
    var seekTarget by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // ── استئناف آخر موضع عند الجاهزية ──
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0L) {
                    val d = player.duration
                    durationMs = d
                    val saved = resumeStore.load(resumeKey)
                    if (saved != null && MediaResumePolicy.shouldResume(saved)) {
                        player.seekTo(saved.positionMs)
                        resumedLabel = MediaPlaybackEngine.formatTime(saved.positionMs)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ── حفظ الموضع عند المغادرة ──
    DisposableEffect(player) {
        onDispose {
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0 && pos > 0) resumeStore.save(resumeKey, pos, dur)
            player.release()
        }
    }

    // ── استطلاع الموضع لتحديث الشريط ──
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) {
                positionMs = player.currentPosition
                val d = player.duration
                if (d > 0) durationMs = d
            }
            kotlinx.coroutines.delay(250)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
    ) {
        IOSNavBar(
            title = file.name,
            subtitle = if (resumedLabel != null) {
                context.getString(R.string.media_player_resume_note, resumedLabel ?: "")
            } else null,
            forceDark = true,
            onBack = onBack,
        )

        // ═══ السطح ═══
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = stringResource(R.string.media_player_audio_art),
                        tint = palette.secondaryLabel,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        file.name,
                        color = palette.label,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }

        // ═══ لوحة التحكم ═══
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF2111111))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // شريط التمرير + الوقت
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    MediaPlaybackEngine.formatTime(if (dragging) seekTarget.toLong() else positionMs),
                    color = palette.secondaryLabel,
                    fontSize = 11.sp,
                )
                Slider(
                    value = when {
                        dragging -> seekTarget
                        durationMs > 0 -> positionMs.toFloat().coerceIn(0f, durationMs.toFloat())
                        else -> 0f
                    },
                    onValueChange = {
                        dragging = true
                        seekTarget = it
                    },
                    onValueChangeFinished = {
                        player.seekTo(seekTarget.toLong())
                        positionMs = seekTarget.toLong()
                        dragging = false
                    },
                    valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF0A84FF),
                        activeTrackColor = Color(0xFF0A84FF),
                        inactiveTrackColor = Color(0xFF3A3A3C),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    MediaPlaybackEngine.formatTime(durationMs),
                    color = palette.secondaryLabel,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(4.dp))

            // أزرار التحكم الرئيسية
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    speed = MediaPlaybackEngine.prevSpeed(speed)
                    player.setPlaybackSpeed(speed.x)
                }) {
                    Icon(Icons.Filled.Speed, contentDescription = stringResource(R.string.media_player_speed_down), tint = palette.label)
                }

                Spacer(Modifier.size(8.dp))

                IconButton(onClick = {
                    player.seekTo(MediaPlaybackEngine.seekBy(player.currentPosition, -10_000, durationMs))
                }) {
                    Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.media_player_back10), tint = palette.label)
                }

                Spacer(Modifier.size(8.dp))

                // تشغيل/إيقاف — الدائرة الكبيرة
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF0A84FF), CircleShape),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.media_player_pause else R.string.media_player_play
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(Modifier.size(8.dp))

                IconButton(onClick = {
                    player.seekTo(MediaPlaybackEngine.seekBy(player.currentPosition, 10_000, durationMs))
                }) {
                    Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.media_player_fwd10), tint = palette.label)
                }

                Spacer(Modifier.size(8.dp))

                IconButton(onClick = {
                    repeat = when (repeat) {
                        MediaRepeatMode.OFF -> MediaRepeatMode.ONE
                        MediaRepeatMode.ONE -> MediaRepeatMode.ALL
                        MediaRepeatMode.ALL -> MediaRepeatMode.OFF
                    }
                    player.repeatMode = repeat.toPlayerRepeat()
                }) {
                    Icon(
                        if (repeat == MediaRepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = stringResource(R.string.media_player_repeat),
                        tint = if (repeat == MediaRepeatMode.OFF) palette.label else Color(0xFF0A84FF),
                    )
                }
            }

            // شريحة السرعة الحالية — تفتح ورقة السرعات
            Text(
                stringResource(R.string.media_player_speed_value, speedLabel(speed.x)),
                color = Color(0xFF0A84FF),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable { speedSheet = true },
            )
        }
    }

    // ورقة السرعات
    IOSActionSheet(
        visible = speedSheet,
        onDismiss = { speedSheet = false },
        title = stringResource(R.string.media_player_speed),
        cancelText = stringResource(R.string.action_cancel),
        actions = PlaybackSpeed.entries.map { s ->
            IOSAction(
                stringResource(R.string.media_player_speed_option, speedLabel(s.x)),
                selected = s == speed,
            ) {
                speed = s
                player.setPlaybackSpeed(s.x)
            }
        },
    )
}
