package com.dinoe.clockwise.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepared photo attachment. The request copy is sized for the vision model; the preview copy is
 * a small thumbnail that can safely be kept in the chat transcript.
 */
data class AiImageAttachment(
    val requestBase64: String,
    val previewBase64: String,
    val mimeType: String = "image/jpeg",
)

class AiImageProcessor(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun load(uri: Uri): AiImageAttachment = withContext(Dispatchers.IO) {
        val full = decodeScaled(uri)
        try {
            val request = encodeJpeg(full, REQUEST_QUALITY)
            if (request.size > MAX_REQUEST_BYTES) throw IOException("Image is too large")
            val preview = full.scaled(PREVIEW_DIMENSION)
            val previewBytes = try {
                encodeJpeg(preview, PREVIEW_QUALITY)
            } finally {
                if (preview !== full) preview.recycle()
            }
            AiImageAttachment(
                requestBase64 = Base64.encodeToString(request, Base64.NO_WRAP),
                previewBase64 = Base64.encodeToString(previewBytes, Base64.NO_WRAP),
            )
        } finally {
            full.recycle()
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Hardware bitmaps cannot be compressed, and the request copy is JPEG.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_DIMENSION) {
                    val scale = MAX_IMAGE_DIMENSION.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            decodeWithBitmapFactory(uri)
        }
        return decoded.scaled(MAX_IMAGE_DIMENSION)
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported image format")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Could not decode image")
        val rotation = readRotation(uri)
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun readRotation(uri: Uri): Float = try {
        open(uri).use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    } catch (_: Exception) {
        0f
    }

    private fun open(uri: Uri) =
        resolver.openInputStream(uri) ?: throw IOException("Cannot open image")

    private fun Bitmap.scaled(maxDimension: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxDimension) return this
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== this) recycle()
        return scaled
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            throw IOException("Could not encode image")
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_IMAGE_DIMENSION = 1_536
        const val PREVIEW_DIMENSION = 240
        const val REQUEST_QUALITY = 85
        const val PREVIEW_QUALITY = 65
        const val MAX_REQUEST_BYTES = 3_500_000
    }
}
