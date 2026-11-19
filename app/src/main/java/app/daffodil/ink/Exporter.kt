package app.daffodil.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.daffodil.model.DocKind
import app.daffodil.model.DocStore
import app.daffodil.model.ImageItem
import app.daffodil.model.Stroke
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Off-screen rendering: thumbnails and exports. Safe to call from a background thread. */
object Exporter {
    private val inkPaint = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val bmpPaint = ThreadLocal.withInitial { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }

    private fun drawItems(c: Canvas, dir: File, strokes: List<Stroke>, images: List<ImageItem>, clip: RectF?, imageMax: Int) {
        val bp = bmpPaint.get()!!
        for (im in images) {
            if (clip != null && !RectF.intersects(im.rect, clip)) continue
            val b = ImageCache.decode(File(dir, im.file), imageMax) ?: continue
            c.drawBitmap(b, null, im.rect, bp)
            b.recycle()
        }
        val p = inkPaint.get()!!
        // Highlights first so they sit underneath the ink.
        for (pass in 0..1) for (s in strokes) {
            if (s.isHighlight != (pass == 0)) continue
            if (clip != null && !RectF.intersects(s.bounds, clip)) continue
            p.color = s.color
            c.drawPath(s.path, p)
        }
    }

    private fun contentBounds(strokes: List<Stroke>, images: List<ImageItem>): RectF? {
        if (strokes.isEmpty() && images.isEmpty()) return null
        val b = RectF()
        strokes.forEach { b.union(it.bounds) }
        images.forEach { b.union(it.rect) }
        return b
    }

    private inline fun <T> withPdf(file: File, block: (PdfRenderer) -> T): T {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try { return block(r) } finally { r.close(); pfd.close() }
    }

    /** Page rects in world space, matching [PdfPages] layout. */
    private fun pageRects(r: PdfRenderer): List<RectF> {
        val out = ArrayList<RectF>()
        var y = 0f
        for (i in 0 until r.pageCount) r.openPage(i).use { p ->
            out.add(RectF(0f, y, p.width.toFloat(), y + p.height)); y += p.height + PdfPages.GAP
        }
        return out
    }

    private fun renderPage(r: PdfRenderer, index: Int, scale: Float): Bitmap {
        r.openPage(index).use { p ->
            val w = max(1, (p.width * scale).roundToInt())
            val h = max(1, (p.height * scale).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, Matrix().apply { setScale(w / p.width.toFloat(), h / p.height.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    fun writeThumbnail(s: DocStore.Snapshot) {
        val tw = 480; val th = 360
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (s.meta.kind == DocKind.PDF) {
            c.drawColor(Color.WHITE)
            val src = File(s.dir, "source.pdf")
            runCatching {
                withPdf(src) { r ->
                    val rect = pageRects(r).firstOrNull() ?: return@withPdf
                    val sc = tw / rect.width()
                    val page = renderPage(r, 0, sc * 1.0f)
                    c.drawBitmap(page, 0f, 0f, bmpPaint.get())
                    page.recycle()
                    c.save(); c.scale(sc, sc); c.translate(-rect.left, -rect.top)
                    c.clipRect(rect)
                    drawItems(c, s.dir, s.strokes, s.images, rect, 512)
                    c.restore()
                }
            }
        } else {
            c.drawColor(Palette.CANVAS_BG)
            val b = contentBounds(s.strokes, s.images)
            if (b != null) {
                val margin = 18f
                val sc = min((tw - 2 * margin) / max(b.width(), 1f), (th - 2 * margin) / max(b.height(), 1f)).coerceAtMost(3f)
                c.save()
                c.translate(tw / 2f, th / 2f); c.scale(sc, sc); c.translate(-b.centerX(), -b.centerY())
                drawItems(c, s.dir, s.strokes, s.images, null, 512)
                c.restore()
            }
        }
        FileOutputStream(File(s.dir, "thumb.png.tmp")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(s.dir, "thumb.png.tmp").renameTo(File(s.dir, "thumb.png"))
        bmp.recycle()
    }

    /** PDF export. Original PDF pages are rasterised (~180 dpi); ink stays vector. */
    fun exportPdf(s: DocStore.Snapshot, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            if (s.meta.kind == DocKind.PDF) {
                withPdf(File(s.dir, "source.pdf")) { r ->
                    val rects = pageRects(r)
                    for (i in rects.indices) {
                        val rect = rects[i]
                        val info = PdfDocument.PageInfo.Builder(rect.width().roundToInt(), rect.height().roundToInt(), i + 1).create()
                        val page = pdf.startPage(info)
                        val c = page.canvas
                        val bmp = renderPage(r, i, 2.5f)
                        c.drawBitmap(bmp, null, RectF(0f, 0f, rect.width(), rect.height()), bmpPaint.get())
                        bmp.recycle()
                        c.save(); c.translate(-rect.left, -rect.top); c.clipRect(rect)
                        drawItems(c, s.dir, s.strokes, s.images, rect, ImageCache.MAX_DIM)
                        c.restore()
                        pdf.finishPage(page)
                    }
                }
            } else {
                val b = contentBounds(s.strokes, s.images)?.apply { inset(-24f, -24f) } ?: RectF(0f, 0f, 595f, 842f)
                // PDF pages are limited to 14400pt; scale down huge canvases.
                val sc = min(1f, 14000f / max(b.width(), b.height()))
                val info = PdfDocument.PageInfo.Builder(max(1, (b.width() * sc).roundToInt()), max(1, (b.height() * sc).roundToInt()), 1).create()
                val page = pdf.startPage(info)
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.scale(sc, sc); c.translate(-b.left, -b.top)
                if (s.meta.paper != 0) Paper.draw(c, s.meta.paper, b, 1f, 1f)
                drawItems(c, s.dir, s.strokes, s.images, null, ImageCache.MAX_DIM)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun exportPng(s: DocStore.Snapshot, out: OutputStream) {
        val b = contentBounds(s.strokes, s.images)?.apply { inset(-24f, -24f) } ?: RectF(0f, 0f, 800f, 600f)
        val sc = min(3f, 7000f / max(b.width(), b.height()))
        val w = max(1, (b.width() * sc).roundToInt())
        val h = max(1, (b.height() * sc).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(sc, sc); c.translate(-b.left, -b.top)
        if (s.meta.paper != 0) Paper.draw(c, s.meta.paper, b, 1f, 1f)
        drawItems(c, s.dir, s.strokes, s.images, null, ImageCache.MAX_DIM)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }
}
