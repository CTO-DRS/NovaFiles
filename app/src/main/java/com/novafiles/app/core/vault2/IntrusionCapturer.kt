package com.novafiles.app.core.vault2

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.15 — التقاط صورة تسلل: صورة أمامية واحدة عبر camera2 عند فشل دخول الخزنة
 * (اختياري من الإعدادات). الصورة تُعاد كتدفق JPEG وتُشفَّر فوراً في سجل التسلل.
 *
 * آمنة الفشل: لا كاميرا/صلاحية غائبة/انشغال → null (يُقيد الحدث بختم زمني فقط).
 * تستخدم نافذة 2.5 ثانية كحد أقصى — لا تحجب الواجهة أبداً.
 */
object IntrusionCapturer {

    private const val CAPTURE_TIMEOUT_MS = 2500L

    fun capture(context: Context): InputStream? {
        if (!hasCameraPermission(context)) return null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val frontId = findFrontCamera(manager) ?: return null

        val latch = CountDownLatch(1)
        val bytes = arrayOfNulls<ByteArray>(1)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                bytes[0] = data
            } finally {
                image.close()
                latch.countDown()
            }
        }, null)

        val state = arrayOfNulls<Any>(1) // CameraDevice أو Session
        var failed = false
        try {
            manager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    state[0] = camera
                    try {
                        val surface = reader.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            @SuppressLint("MissingPermission")
                            override fun onConfigured(session: CameraCaptureSession) {
                                state[0] = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(surface)
                                    }.build()
                                    session.capture(request, null, null)
                                } catch (e: Exception) {
                                    failed = true
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                failed = true
                                latch.countDown()
                            }
                        }, null)
                    } catch (e: Exception) {
                        failed = true
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    failed = true
                    latch.countDown()
                }
            }, null)
        } catch (e: Exception) {
            return null
        }

        latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runCatching { (state[0] as? CameraCaptureSession)?.close() }
        runCatching { (state[0] as? CameraDevice)?.close() }
        runCatching { reader.close() }

        return if (!failed && bytes[0] != null) ByteArrayInputStream(bytes[0]) else null
    }

    private fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun findFrontCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }
}
