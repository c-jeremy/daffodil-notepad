package app.daffodil.ink

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Renders PDF pages on a private thread. Pages are laid out top-to-bottom in world
 * space, 1 world unit = 1 PDF point. A full-page bitmap is kept per page at a
 * zoom-dependent resolution; when zoomed in further than a full-page bitmap can
 * cover, a screen-sized "detail" patch is rendered for the visible part.
 */
class PdfPages(file: File) {
    class PageDraw(val rect: RectF, val bitmap: Bitmap?, val detailRect: RectF?, val detail: Bitmap?)

    private val thread = HandlerThread("pdf").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var renderer: PdfRenderer
    private lateinit var pfd: ParcelFileDescriptor

    val pageRects: List<RectF>
    val pageCount get() = pageRects.size

    private class Full(val scale: Float, val bitmap: Bitmap)
    private class Detail(val rect: RectF, val scale: Float, val bitmap: Bitmap)

    // Main-thread state.
    private val full = HashMap<Int, Full>()
    private val details = HashMap<Int, Detail>()
    private val lru = ArrayList<Int>()
    @Volatile private var generation = 0
    var onUpdated: (() -> Unit)? = null
    @Volatile private var closed = false

    init {
        val rects = ArrayList<RectF>()
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                var y = 0f
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { p ->
                        rects.add(RectF(0f, y, p.width.toFloat(), y + p.height.toFloat()))
                        y += p.height + GAP
                    }
                }
            } catch (t: Throwable) { error = t }
            latch.countDown()
        }
        latch.await()
        error?.let { thread.quitSafely(); throw it }
        pageRects = rects
    }

    val contentWidth: Float get() = pageRects.maxOfOrNull { it.width() } ?: 612f

    fun drawList(visible: RectF): List<PageDraw> {
        val out = ArrayList<PageDraw>()
        for (i in pageRects.indices) {
            val r = pageRects[i]
            if (!RectF.intersects(r, visible)) continue
            val d = details[i]
            out.add(PageDraw(r, full[i]?.bitmap, d?.rect, d?.bitmap))
        }
        return out
    }

    /**
     * Ask for bitmaps that suit the current view. [scale] is screen pixels per world unit.
     * [settled] = the view has stopped moving, so expensive detail patches are worth it.
     */
    fun request(visible: RectF, scale: Float, settled: Boolean) {
        if (closed) return
        val gen = ++generation
        val wanted = tier(scale)
        val jobs = ArrayList<Runnable>()
        val visiblePages = pageRects.indices.filter { RectF.intersects(pageRects[it], visible) }
        // Prefetch one page before/after for smooth scrolling.
        val around = (visiblePages.minOrNull()?.minus(1) ?: 0)..(visiblePages.maxOrNull()?.plus(1) ?: -1)
        for (i in around) {
            if (i !in pageRects.indices) continue
            val r = pageRects[i]
            val cap = capScale(r)
            val target = min(wanted, cap)
            val have = full[i]
            if (have == null || (have.scale < target * 0.99f) || (have.scale > target * 2.01f)) {
                jobs.add(Runnable { renderFull(gen, i, target) })
            }
        }
        // Details: only when settled and the full bitmap is too coarse.
        val keepDetail = HashSet<Int>()
        if (settled) {
            for (i in visiblePages) {
                val r = pageRects[i]
                if (scale <= capScale(r) * 1.05f) continue
                val vis = RectF(r)
                if (!vis.intersect(visible)) continue
                keepDetail.add(i)
                val d = details[i]
                if (d != null && d.rect.contains(vis) && d.scale >= scale * 0.99f && d.scale <= scale * 1.01f) continue
                val patch = RectF(vis)
                jobs.add(Runnable { renderDetail(gen, i, patch, scale) })
            }
        }
        // Drop stale details when zooming out / scrolling away.
        if (settled) details.keys.retainAll(keepDetail) else {
            val it = details.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.scale > scale * 1.6f || e.value.scale < scale / 1.6f || !RectF.intersects(e.value.rect, visible)) it.remove()
            }
        }
        handler.removeCallbacksAndMessages(JOB_TOKEN)
        val now = SystemClock.uptimeMillis()
        jobs.forEach { handler.postAtTime(it, JOB_TOKEN, now) }
    }

    private fun tier(scale: Float): Float {
        // Quantize to steps of sqrt(2) so small zoom changes do not re-render.
        val k = ceil(ln(max(scale, 0.05f).toDouble()) / ln(SQRT2)).toInt()
        return SQRT2.pow(k).toFloat()
    }

    private fun capScale(r: RectF): Float {
        val byDim = MAX_DIM / max(r.width(), r.height())
        val byPixels = kotlin.math.sqrt(MAX_PIXELS / (r.width() * r.height()))
        return min(byDim, byPixels)
    }

    // --- worker thread ----------------------------------------------------------------------

    private fun renderFull(gen: Int, index: Int, scale: Float) {
        if (gen != generation || closed) return
        val r = pageRects[index]
        val w = max(1, (r.width() * scale).roundToInt())
        val h = max(1, (r.height() * scale).roundToInt())
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return }
        bmp.eraseColor(Color.WHITE)
        try {
            renderer.openPage(index).use { p ->
                val m = Matrix().apply { setScale(w / p.width.toFloat(), h / p.height.toFloat()) }
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } catch (_: Throwable) { return }
        bmp.prepareToDraw()
        main.post {
            if (closed) return@post
            full[index] = Full(scale, bmp)
            lru.remove(index); lru.add(index)
            trimCache()
            onUpdated?.invoke()
        }
    }

    private fun renderDetail(gen: Int, index: Int, patch: RectF, scale: Float) {
        if (gen != generation || closed) return
        val pr = pageRects[index]
        val w = max(1, (patch.width() * scale).roundToInt())
        val h = max(1, (patch.height() * scale).roundToInt())
        if (w.toLong() * h > 16_000_000L) return
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return }
        bmp.eraseColor(Color.WHITE)
        try {
            renderer.openPage(index).use { p ->
                val sx = pr.width() / p.width
                val m = Matrix().apply {
                    setScale(scale * sx, scale * sx)
                    postTranslate(-(patch.left - pr.left) * scale, -(patch.top - pr.top) * scale)
                }
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } catch (_: Throwable) { return }
        bmp.prepareToDraw()
        main.post {
            if (closed || gen != generation) return@post
            details[index] = Detail(patch, scale, bmp)
            onUpdated?.invoke()
        }
    }

    private fun trimCache() {
        var bytes = full.values.sumOf { it.bitmap.allocationByteCount.toLong() }
        while (bytes > CACHE_BYTES && lru.size > 3) {
            val victim = lru.removeAt(0)
            full.remove(victim)?.let { bytes -= it.bitmap.allocationByteCount }
        }
    }

    /** Renders page [index] at [scale] synchronously on the pdf thread (for export/thumbnails). */
    fun renderBlocking(index: Int, scale: Float): Bitmap? {
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        handler.postAtFrontOfQueue {
            try {
                val r = pageRects[index]
                val w = max(1, (r.width() * scale).roundToInt())
                val h = max(1, (r.height() * scale).roundToInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                renderer.openPage(index).use { p ->
                    val m = Matrix().apply { setScale(w / p.width.toFloat(), h / p.height.toFloat()) }
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                out = bmp
            } catch (_: Throwable) { }
            latch.countDown()
        }
        latch.await()
        return out
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
        thread.quitSafely()
        full.clear(); details.clear()
    }

    companion object {
        const val GAP = 18f
        private val JOB_TOKEN = Any()
        private const val SQRT2 = 1.4142135623730951
        private const val MAX_DIM = 4096f
        private const val MAX_PIXELS = 4096f * 3072f
        private const val CACHE_BYTES = 220L * 1024 * 1024
    }
}
