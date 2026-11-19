package app.daffodil.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import app.daffodil.model.Geometry
import app.daffodil.model.Stroke

/** A recorded group of strokes (display list) with its world bounds. */
class Chunk(val node: RenderNode?, val bounds: RectF, val strokes: List<Stroke>)

class ImgDraw(val rect: RectF, val bitmap: Bitmap?)

class SelectionDraw(
    val chunk: Chunk?,
    val images: List<ImgDraw>,
    val transform: Matrix,
    val bounds: RectF, // untransformed world bounds
)

/** Immutable snapshot handed to the render thread. */
class Frame(
    val matrix: Matrix,
    val visible: RectF,
    val pdfMode: Boolean,
    val pages: List<PdfPages.PageDraw>,
    val images: List<ImgDraw>,
    val chunks: List<Chunk>,
    val selection: SelectionDraw?,
    val lasso: FloatArray?,        // screen x,y pairs
    val cursor: FloatArray?,       // screen x, y, r
)

/** One batch of wet ink for the front buffer: screen-space (x, y, r) triples. */
class WetBatch(val color: Int, val pts: FloatArray, val count: Int)

object Palette {
    const val CANVAS_BG = 0xFFFCFBF8.toInt()
    const val PDF_BG = 0xFFE9E7E2.toInt()
    const val ACCENT = 0xFFD9A300.toInt()
    const val PAGE_SHADOW = 0x22000000
}

/** Draws [Frame]s. Lives on the render thread; owns its own paints. */
class SceneDrawer(private val density: Float) {
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = Palette.PAGE_SHADOW }
    private val placeholderPaint = Paint().apply { color = 0xFFEDEBE6.toInt() }
    private val selFill = Paint().apply { color = 0x0FD9A300 }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.ACCENT; strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.ACCENT; strokeWidth = 1.5f * density
    }
    private val lassoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF6B6B6B.toInt(); strokeWidth = 1.2f * density
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0C000000 }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x99555555.toInt(); strokeWidth = 1f * density
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val tmpRect = RectF()
    private val tmpMatrix = Matrix()
    private val lassoPath = Path()
    private val wetPath = Path().apply { fillType = Path.FillType.WINDING }
    private val wetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun drawWet(canvas: Canvas, b: WetBatch) {
        val p = b.pts
        wetPath.rewind()
        if (b.count == 1) {
            Geometry.addCircle(wetPath, p[0], p[1], p[2])
        } else {
            for (i in 1 until b.count) {
                val j = 3 * (i - 1); val k = 3 * i
                Geometry.addCapsule(wetPath, p[j], p[j + 1], p[j + 2], p[k], p[k + 1], p[k + 2])
            }
        }
        wetPaint.color = b.color
        canvas.drawPath(wetPath, wetPaint)
    }

    fun draw(canvas: Canvas, f: Frame) {
        canvas.drawColor(if (f.pdfMode) Palette.PDF_BG else Palette.CANVAS_BG)
        val hw = canvas.isHardwareAccelerated
        val scale = f.matrix.mapRadius(1f)

        canvas.save()
        canvas.concat(f.matrix)
        for (p in f.pages) {
            val s = 1.5f / scale
            tmpRect.set(p.rect); tmpRect.offset(0f, s); tmpRect.inset(-s * 0.5f, 0f)
            canvas.drawRect(tmpRect, shadowPaint)
            if (p.bitmap != null) canvas.drawBitmap(p.bitmap, null, p.rect, bmpPaint)
            else canvas.drawRect(p.rect, pagePaint)
            if (p.detail != null && p.detailRect != null) canvas.drawBitmap(p.detail, null, p.detailRect, bmpPaint)
        }
        for (im in f.images) drawImage(canvas, im)
        for (c in f.chunks) {
            if (!RectF.intersects(c.bounds, f.visible)) continue
            drawChunk(canvas, c, hw)
        }
        f.selection?.let { sel ->
            canvas.save()
            canvas.concat(sel.transform)
            for (im in sel.images) drawImage(canvas, im)
            sel.chunk?.let { drawChunk(canvas, it, hw) }
            canvas.restore()
        }
        canvas.restore()

        // Screen-space overlays.
        f.selection?.let { sel ->
            tmpMatrix.set(sel.transform); tmpMatrix.postConcat(f.matrix)
            tmpRect.set(sel.bounds); tmpMatrix.mapRect(tmpRect)
            val pad = 6f * density
            tmpRect.inset(-pad, -pad)
            canvas.drawRoundRect(tmpRect, 4f * density, 4f * density, selFill)
            canvas.drawRoundRect(tmpRect, 4f * density, 4f * density, selStroke)
            val hr = 5.5f * density
            canvas.drawCircle(tmpRect.right, tmpRect.bottom, hr, handleFill)
            canvas.drawCircle(tmpRect.right, tmpRect.bottom, hr, handleStroke)
        }
        f.lasso?.let { pts ->
            if (pts.size >= 4) {
                lassoPath.rewind()
                lassoPath.moveTo(pts[0], pts[1])
                var i = 2
                while (i + 1 < pts.size) { lassoPath.lineTo(pts[i], pts[i + 1]); i += 2 }
                canvas.drawPath(lassoPath, lassoFill)
                canvas.drawPath(lassoPath, lassoStroke)
            }
        }
        f.cursor?.let { c ->
            canvas.drawCircle(c[0], c[1], c[2], cursorFill)
            canvas.drawCircle(c[0], c[1], c[2], cursorStroke)
        }
    }

    private fun drawChunk(canvas: Canvas, c: Chunk, hw: Boolean) {
        if (hw && c.node != null) {
            canvas.drawRenderNode(c.node)
        } else {
            for (s in c.strokes) { inkPaint.color = s.color; canvas.drawPath(s.path, inkPaint) }
        }
    }

    private fun drawImage(canvas: Canvas, im: ImgDraw) {
        if (im.bitmap != null) canvas.drawBitmap(im.bitmap, null, im.rect, bmpPaint)
        else canvas.drawRect(im.rect, placeholderPaint)
    }
}

/** Records strokes into a RenderNode (display list) so redraws are cheap. */
object ChunkRecorder {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun record(strokes: List<Stroke>): Chunk {
        val b = RectF()
        for (s in strokes) b.union(s.bounds)
        if (strokes.isEmpty()) return Chunk(null, b, strokes)
        val l = kotlin.math.floor(b.left).toInt() - 1
        val t = kotlin.math.floor(b.top).toInt() - 1
        val r = kotlin.math.ceil(b.right).toInt() + 1
        val bt = kotlin.math.ceil(b.bottom).toInt() + 1
        val node = RenderNode("ink")
        node.setPosition(l, t, r, bt)
        node.setClipToBounds(false)
        val c = node.beginRecording()
        try {
            c.translate(-l.toFloat(), -t.toFloat())
            for (s in strokes) { paint.color = s.color; c.drawPath(s.path, paint) }
        } finally {
            node.endRecording()
        }
        return Chunk(node, b, strokes)
    }
}
