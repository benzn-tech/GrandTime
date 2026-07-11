package com.benzn.grandtime.capture

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File

class PhotoTaker(private val context: Context) {

    fun take(imageCapture: ImageCapture, file: File, onDone: (success: Boolean) -> Unit) {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onDone(true)
                override fun onError(exception: ImageCaptureException) = onDone(false)
            },
        )
    }
}
