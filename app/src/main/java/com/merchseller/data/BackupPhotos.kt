package com.merchseller.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

internal const val MAX_PHOTO_BYTES = 128 * 1024
private const val MAX_SIDE = 640

internal fun decodePhotoPayload(value: String): ByteArray {
    require(value.length <= (MAX_PHOTO_BYTES + 2) / 3 * 4) { "Фото в копии слишком большое" }
    val bytes = Base64.getDecoder().decode(value)
    require(bytes.size in 1..MAX_PHOTO_BYTES) { "Некорректный размер фото в копии" }
    return bytes
}

internal class BackupPhotos(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "restored-photos")

    fun encode(product: Product): String {
        val uri = Uri.parse(requireNotNull(product.photoUri))
        fun open() = checkNotNull(context.contentResolver.openInputStream(uri))
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0)
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            val orientation = runCatching {
                open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            }.getOrDefault(1)
            val decoded = checkNotNull(open().use { BitmapFactory.decodeStream(it, null, options) })
            try {
                val ratio = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
                val transform = Matrix().apply {
                    val scaleX = maxOf(ratio, 1f / decoded.width)
                    val scaleY = maxOf(ratio, 1f / decoded.height)
                    setScale(if (orientation in listOf(2, 4, 5, 7)) -scaleX else scaleX, scaleY)
                    postRotate(when (orientation) {
                        3, 4 -> 180f
                        5, 8 -> 270f
                        6, 7 -> 90f
                        else -> 0f
                    })
                }
                val scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
                try {
                    val output = ByteArrayOutputStream()
                    for (quality in listOf(82, 65, 45, 25)) {
                        output.reset()
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output))
                        if (output.size() <= MAX_PHOTO_BYTES) {
                            return Base64.getEncoder().encodeToString(output.toByteArray())
                        }
                    }
                    error("Фото слишком большое")
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Не удалось включить фото товара «${product.name}». Замените фото или создайте копию без фотографий.", error)
        }
    }

    fun restore(bytes: ByteArray, staged: MutableList<File>): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE) {
            "Копия содержит неподдерживаемое фото"
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Копия содержит повреждённое фото")
        decoded.recycle()
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку для фотографий" }
        val file = File.createTempFile("photo-", ".jpg", directory)
        staged.add(file)
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    fun legacyUri(value: String?): String? = value?.takeIf { uri ->
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == uri }
    }

    fun deleteRestored(value: String?) {
        val uri = value?.let(Uri::parse) ?: return
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        if (file.parentFile == directory && file.name.startsWith("photo-") && file.extension == "jpg") file.delete()
    }
}
