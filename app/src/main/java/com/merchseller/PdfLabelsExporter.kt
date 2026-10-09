package com.merchseller

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.merchseller.data.Product
import kotlin.math.ceil

data class LabelOptions(val columns: Int = 3, val rows: Int = 5, val showPhoto: Boolean = true)

object PdfLabelsExporter {
    fun export(context: Context, products: List<Product>, options: LabelOptions): Uri? {
        if (products.isEmpty()) return null
        val doc = PdfDocument(); val pageW = 595; val pageH = 842
        val slots = options.columns.coerceIn(1, 5) * options.rows.coerceIn(1, 10)
        val pages = ceil(products.size / slots.toDouble()).toInt()
        // Разбиваем каталог на страницы, чтобы каждая этикетка оставалась одинакового размера.
        products.chunked(slots).forEachIndexed { pageIndex, batch ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
            val canvas = page.canvas; canvas.drawColor(Color.WHITE)
            val pad = 18f; val cellW = (pageW - pad * 2) / options.columns; val cellH = (pageH - pad * 2) / options.rows
            val paint = Paint(Paint.ANTI_ALIAS_FLAG); val title = Paint(paint).apply { color = Color.rgb(30, 40, 60); textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }; val sub = Paint(paint).apply { color = Color.DKGRAY; textSize = 8f }
            batch.forEachIndexed { i, product ->
                val col = i % options.columns; val row = i / options.columns; val left = pad + col * cellW; val top = pad + row * cellH
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.7f; paint.color = Color.LTGRAY
                canvas.drawRect(left + 2, top + 2, left + cellW - 2, top + cellH - 2, paint); paint.style = Paint.Style.FILL
                val qrSize = minOf(cellW * if (options.showPhoto) .40f else .58f, cellH * .58f).toInt()
                val qr = qrBitmap(product.sku, qrSize)
                val qrLeft = if (options.showPhoto) left + cellW * .53f else left + (cellW - qrSize) / 2
                canvas.drawBitmap(qr, qrLeft, top + 12, null)
                if (options.showPhoto) loadPhoto(context, product.photoUri)?.let { photo ->
                    val size = minOf(qrSize.toFloat(), cellW * .38f)
                    val src = Rect(0, 0, photo.width, photo.height)
                    canvas.drawBitmap(photo, src, RectF(left + cellW * .08f, top + 12, left + cellW * .08f + size, top + 12 + size), paint)
                }
                val textY = top + qrSize + 28
                canvas.drawText(product.name.take(25), left + 10, textY, title)
                canvas.drawText(product.sku, left + 10, textY + 14, sub)
                canvas.drawText("${String.format("%.2f", product.totalPrice)} ₽  ·  остаток ${product.stock}", left + 10, textY + 27, sub)
            }
            doc.finishPage(page)
        }
        val name = "MerchSeller_labels_${System.currentTimeMillis()}.pdf"
        val resolver = context.contentResolver
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/pdf"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.IS_PENDING, 1) }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null) }
        doc.close(); return uri
    }
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap -> for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE) }
    }
    private fun loadPhoto(context: Context, uriText: String?): Bitmap? = try {
        uriText?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) }
    } catch (_: Exception) { null }
}
