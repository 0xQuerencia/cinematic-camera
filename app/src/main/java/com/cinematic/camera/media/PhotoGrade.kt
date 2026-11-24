package com.cinematic.camera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cinematic.camera.color.Grade
import java.io.File
import kotlin.math.roundToInt

/** Bounded-memory JPEG LUT copy. Original files are never modified. */
internal object PhotoGrade {
    fun export(context: Context, source: Uri, title: String, grade: Grade): Uri {
        val cube = requireNotNull(grade.look.cube)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid JPEG." }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 8_000_000L) sample *= 2
        val orientation = resolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bitmap = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply {
                inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }) } ?: error("Could not decode JPEG.")
        val temporary = File.createTempFile("photo-look-", ".jpg", context.cacheDir)
        try {
            val row = IntArray(bitmap.width)
            val mapped = FloatArray(3)
            val strength = grade.strength.coerceIn(0f, 1f)
            fun blend(source: Int, target: Float) =
                (source + (target.coerceIn(0f, 1f) * 255f - source) * strength).roundToInt().coerceIn(0, 255)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (i in row.indices) {
                    val rgb = row[i]
                    val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
                    cube.sampleInto(r / 255f, g / 255f, b / 255f, mapped)
                    row[i] = (255 shl 24) or (blend(r, mapped[0]) shl 16) or
                        (blend(g, mapped[1]) shl 8) or blend(b, mapped[2])
                }
                bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            }
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(temporary.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${title.substringBeforeLast('.')}_Look.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output -> temporary.inputStream().use { it.copyTo(output) } }
                if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null) == 1)
                return uri
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
    }
}
