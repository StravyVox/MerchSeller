package com.merchseller

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.merchseller.data.Product
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

data class LabelOptions(val columns: Int = 3, val rows: Int = 5, val showPhoto: Boolean = true)

object PdfLabelsExporter {
    fun export(context: Context, destination: Uri, products: List<Product>, options: LabelOptions) {
        require(products.isNotEmpty())
        require(options.columns in 1..5 && options.rows in 1..10)
        val pageWidth = 595
        val pageHeight = 842
        val margin = 18f
        val cellWidth = (pageWidth - 2 * margin) / options.columns
        val cellHeight = (pageHeight - 2 * margin) / options.rows
        val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"))
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
        val image = Paint(Paint.FILTER_BITMAP_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 8f }
        val title = Paint(text).apply { textSize = 9f; typeface = Typeface.DEFAULT_BOLD }

        val document = PdfDocument()
        try {
            products.chunked(options.columns * options.rows).forEachIndexed { pageIndex, batch ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
                try {
                    batch.forEachIndexed { index, product ->
                        val left = margin + index % options.columns * cellWidth
                        val top = margin + index / options.columns * cellHeight
                        val canvas = page.canvas
                        canvas.drawRect(left + 2, top + 2, left + cellWidth - 2, top + cellHeight - 2, border)
                        // Оставляем место для всех трёх строк даже при десяти рядах на странице.
                        val size = minOf(cellHeight - 37, cellWidth * if (options.showPhoto) .43f else .7f)
                        val photo = if (options.showPhoto) loadPhoto(context, product.photoUri) else null
                        val qrLeft = if (photo == null) left + (cellWidth - size) / 2 else left + cellWidth - size - 5
                        drawQr(canvas, product.sku, qrLeft, top + 4, size)
                        photo?.let {
                            val scale = minOf(size / it.width, size / it.height)
                            val photoLeft = left + 5
                            val photoTop = top + 4 + (size - it.height * scale) / 2
                            canvas.drawBitmap(it, null, RectF(photoLeft, photoTop, photoLeft + it.width * scale, photoTop + it.height * scale), image)
                        }
                        val baseline = top + size + 14
                        canvas.drawText(fit(product.name, title, cellWidth - 12), left + 6, baseline, title)
                        canvas.drawText(fit(product.sku, text, cellWidth - 12), left + 6, baseline + 9, text)
                        val priceAndStock = "${currency.format(BigDecimal(product.totalPriceKopecks, 2))} · ${product.stock}\u00a0шт."
                        canvas.drawText(fit(priceAndStock, text, cellWidth - 12), left + 6, baseline + 18, text)
                    }
                } finally {
                    document.finishPage(page)
                }
            }
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("Не удалось открыть файл для записи")
            output.use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun fit(value: String, paint: Paint, width: Float): String {
        val line = value.replace(Regex("[\\r\\n]+"), " ")
        if (paint.measureText(line) <= width) return line
        val count = paint.breakText(line, true, width - paint.measureText("…"), null)
        return line.take(count) + "…"
    }

    private fun drawQr(canvas: Canvas, value: String, left: Float, top: Float, size: Float) {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val step = size / matrix.width
        val paint = Paint().apply { color = Color.BLACK }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) canvas.drawRect(left + x * step, top + y * step, left + (x + 1) * step, top + (y + 1) * step, paint)
            }
        }
    }

    private fun loadPhoto(context: Context, uriText: String?): Bitmap? {
        val uri = uriText?.let(Uri::parse) ?: return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > 512) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }
}
