package com.cyberbot.ai.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Minimal front-camera helper built on CameraX.
 *
 * Call [initialize] once (binds the camera to the given lifecycle), then
 * [captureFrame] to grab a single JPEG frame on demand. All failures are logged
 * and never crash the caller.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Bind the front camera to the lifecycle. Safe to call once after permission. */
    fun initialize() {
        if (!hasPermission()) {
            Log.e(TAG, "CAMERA permission not granted; cannot initialize")
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    capture,
                )
                Log.i(TAG, "Front camera initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Capture a single frame as JPEG bytes and deliver it via [onFrame]. */
    fun captureFrame(onFrame: (ByteArray) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "captureFrame called before camera was initialized")
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val jpeg = image.toJpegBytes()
                        Log.i(TAG, "Frame captured: ${jpeg.size} bytes")
                        onFrame(jpeg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read captured frame", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Frame capture failed", exception)
                }
            },
        )
    }

    /** Unbind the camera and release resources. */
    fun release() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Camera release failed", e)
        }
        imageCapture = null
        cameraProvider = null
        Log.i(TAG, "Camera released")
    }

    /** ImageCapture with the default output format produces a single JPEG plane. */
    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
