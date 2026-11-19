package app.daffodil.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.widget.OverScroller
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import app.daffodil.model.DocListener
import app.daffodil.model.Document
import app.daffodil.model.Edit
import app.daffodil.model.Geometry
import app.daffodil.model.ImageItem
import app.daffodil.model.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class Tool { PEN, ERASER, LASSO }

/**
 * The drawing surface. Wet ink goes to a front-buffered layer (drawn straight to the
 * display buffer, bypassing the usual queue), committed ink is drawn from cached
 * display lists into the multi-buffered layer. All document state lives on the UI
 * thread; the render thread only ever sees immutable [Frame]s and [WetBatch]es.
 */
class InkSurface(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), DocListener, SurfaceHolder.Callback {

    interface Listener {
        fun onToolChanged() {}
        fun onSelectionChanged() {}
        fun onHistoryChanged() {}
        fun onViewportChanged() {}
        fun onContentChanged() {}
        fun onHint(text: String) {}
    }

    companion object {
        const val INK_BLACK = 0xFF1D1D1F.toInt()
        const val INK_RED = 0xFFD7372A.toInt()
        const val INK_BLUE = 0xFF1E5BCF.toInt()
        /** Pen diameters in world units (PDF points / dp on a blank canvas). */
        val PEN_SIZES = floatArrayOf(1.3f, 2.2f, 3.8f)
        private const val CHUNK = 48
        private const val MIN_ZOOM = 0.08f
        private const val MAX_ZOOM = 40f
        private val DUMMY = listOf(WetBatch(0, FloatArray(0), 0))
    }

    private val density = resources.displayMetrics.density
    var listener: Listener? = null

    // ---------------------------------------------------------------- document

    var doc: Document? = null
        private set
    var pdf: PdfPages? = null
        private set
    private var images: ImageCache? = null

    fun bind(d: Document, p: PdfPages?) {
        doc = d; d.listener = this; pdf = p
        images?.shutdown()
        images = ImageCache(d.dir) { invalidateScene() }
        p?.onUpdated = { invalidateScene() }
        chunks.clear()
        selection = null
        viewportReady = false
        if (width > 0) initViewport()
        invalidateScene()
    }

    fun unbind() {
        images?.shutdown(); images = null
        pdf?.onUpdated = null
    }

    // ---------------------------------------------------------------- tools & settings

    var tool = Tool.PEN
        private set
    var penColor = INK_BLACK
    var penSize = 1
    var partialEraser = false
        set(v) { field = v; if (cursor != null) invalidateScene() }
    var autoReturnAfterLasso = true
    var fingerDraws = false
    var twoFingerTapUndo = true
    private var returnTool: Tool = Tool.PEN

    fun setTool(t: Tool) {
        if (t == tool) return
        if (t == Tool.LASSO) returnTool = tool
        if (tool == Tool.LASSO) clearSelection()
        tool = t
        cursor = null
        invalidateScene()
        listener?.onToolChanged()
    }

    private val eraserRadiusPx get() = (if (partialEraser) 11f else 7f) * density

    // ---------------------------------------------------------------- viewport

    /** Screen pixels per world unit. */
    var scale = density
        private set
    private var tx = 0f
    private var ty = 0f
    private var viewportReady = false

    private val settleRunnable = Runnable { requestPdf(true); listener?.onViewportChanged() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (doc != null && !viewportReady) initViewport()
        else if (oldw > 0 && oldh > 0) { tx += (w - oldw) / 2f; ty += (h - oldh) / 2f }
        afterViewportChange(true)
    }

    private fun initViewport() {
        val m = doc?.meta ?: return
        if (!m.viewZoom.isNaN() && m.viewZoom > 0f) {
            scale = m.viewZoom * density
            tx = width / 2f - m.viewCx * scale
            ty = height / 2f - m.viewCy * scale
        } else if (pdf != null) {
            fitPdfWidth()
        } else {
            scale = density; tx = 0f; ty = 0f
        }
        viewportReady = true
        afterViewportChange(true)
    }

    private fun fitPdfWidth() {
        val p = pdf ?: return
        val margin = 10f * density
        scale = ((width - 2 * margin) / p.contentWidth).coerceAtLeast(0.01f)
        tx = (width - p.contentWidth * scale) / 2f
        ty = margin
    }

    fun saveViewport() {
        val m = doc?.meta ?: return
        if (width == 0 || !viewportReady) return
        m.viewZoom = scale / density
        m.viewCx = (width / 2f - tx) / scale
        m.viewCy = (height / 2f - ty) / scale
    }

    /** PDF: fit page width. Canvas: fit everything that has been drawn. */
    fun resetView() {
        if (pdf != null) { fitPdfWidth(); afterViewportChange(true); return }
        val b = contentBounds()
        if (b == null) { scale = density; tx = 0f; ty = 0f }
        else {
            val margin = 32f * density
            val s = min((width - 2 * margin) / max(b.width(), 1f), (height - 2 * margin) / max(b.height(), 1f))
            scale = s.coerceIn(MIN_ZOOM * density, 3f * density)
            tx = width / 2f - b.centerX() * scale
            ty = height / 2f - b.centerY() * scale
        }
        afterViewportChange(true)
    }

    fun contentBounds(): RectF? {
        val d = doc ?: return null
        if (d.strokes.isEmpty() && d.images.isEmpty()) return null
        val b = RectF()
        d.strokes.forEach { b.union(it.bounds) }
        d.images.forEach { b.union(it.rect) }
        return b
    }

    fun visibleWorld() = RectF(-tx / scale, -ty / scale, (width - tx) / scale, (height - ty) / scale)

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val ns = (scale * f).coerceIn(MIN_ZOOM * density, MAX_ZOOM * density)
        val rf = ns / scale
        tx = fx - (fx - tx) * rf
        ty = fy - (fy - ty) * rf
        scale = ns
    }

    private fun afterViewportChange(settled: Boolean) {
        invalidateScene()
        listener?.onViewportChanged()
        requestPdf(settled)
        removeCallbacks(settleRunnable)
        if (!settled) postDelayed(settleRunnable, 160)
    }

    private fun requestPdf(settled: Boolean) {
        if (width == 0) return
        pdf?.request(visibleWorld(), scale, settled)
    }

    private fun toWorldX(sx: Float) = (sx - tx) / scale
    private fun toWorldY(sy: Float) = (sy - ty) / scale

    // ---------------------------------------------------------------- rendering

    private var renderer: CanvasFrontBufferedRenderer<WetBatch>? = null
    private val drawer = SceneDrawer(density)
    @Volatile private var frame: Frame? = null
    private val chunks = ArrayList<Chunk?>()
    private var sceneScheduled = false
    private var pendingScene = false
    private var frontActive = false
    private var lassoScreen: FloatList? = null
    private var cursor: FloatArray? = null

    private val frameCallback = Runnable { sceneScheduled = false; renderScene() }

    private val rendererCallback = object : CanvasFrontBufferedRenderer.Callback<WetBatch> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: WetBatch) {
            drawer.drawWet(canvas, param)
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<WetBatch>) {
            val f = frame
            if (f == null) canvas.drawColor(Palette.CANVAS_BG) else drawer.draw(canvas, f)
        }
    }

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = CanvasFrontBufferedRenderer(this, rendererCallback)
        invalidateScene()
    }

    override fun onDetachedFromWindow() {
        renderer?.release(true)
        renderer = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { invalidateScene() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { invalidateScene() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    fun invalidateScene() {
        if (!sceneScheduled) { sceneScheduled = true; postOnAnimation(frameCallback) }
    }

    private fun renderScene() {
        if (frontActive) { pendingScene = true; return }
        val r = renderer ?: return
        frame = buildFrame()
        r.renderMultiBufferedLayer(DUMMY)
    }

    private fun buildFrame(): Frame {
        val m = Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) }
        val vis = visibleWorld()
        val d = doc ?: return Frame(m, vis, false, emptyList(), emptyList(), emptyList(), null, null, null)
        ensureChunks(d)
        val sel = selection
        val imgs = ArrayList<ImgDraw>()
        for (im in d.images) {
            if (sel != null && im.id in sel.imageIds) continue
            if (RectF.intersects(im.rect, vis)) imgs.add(ImgDraw(im.rect, images?.get(im.file)))
        }
        val selDraw = sel?.let { s ->
            SelectionDraw(s.chunk, s.images.map { ImgDraw(it.rect, images?.get(it.file)) }, Matrix(s.transform), s.bounds)
        }
        return Frame(
            m, vis, pdf != null,
            pdf?.drawList(vis) ?: emptyList(),
            imgs, chunks.filterNotNull(), selDraw,
            lassoScreen?.toArray(), cursor,
        )
    }

    private fun ensureChunks(d: Document) {
        val n = d.strokes.size
        val nc = (n + CHUNK - 1) / CHUNK
        while (chunks.size > nc) chunks.removeAt(chunks.size - 1)
        while (chunks.size < nc) chunks.add(null)
        val skip = selection?.strokeIds
        for (k in 0 until nc) {
            if (chunks[k] != null) continue
            val from = k * CHUNK
            val to = min(n, from + CHUNK)
            val list = ArrayList<Stroke>(to - from)
            for (i in from until to) {
                val s = d.strokes[i]
                if (skip == null || s.id !in skip) list.add(s)
            }
            chunks[k] = ChunkRecorder.record(list)
        }
    }

    override fun onStrokesChanged(from: Int) {
        val k0 = max(0, from / CHUNK)
        for (k in k0 until chunks.size) chunks[k] = null
        invalidateScene()
        listener?.onContentChanged()
    }

    override fun onImagesChanged() {
        invalidateScene()
        listener?.onContentChanged()
    }

    // ---------------------------------------------------------------- input routing

    private enum class Mode { NONE, INK, NAV, IGNORE }

    private var mode = Mode.NONE
    private var inkPointerId = -1
    private var inkTool = Tool.PEN
    private var inkFromFinger = false
    private var inkStartTime = 0L
    private var penHovering = false
    private var lastPenSeen = 0L

    private fun isPenTool(t: Int) =
        t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE

    private fun fingersSuppressed() = penHovering || SystemClock.uptimeMillis() - lastPenSeen < 400

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            lastPenSeen = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    penHovering = true
                    val erasing = tool == Tool.ERASER || tt == MotionEvent.TOOL_TYPE_ERASER ||
                        (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    if (erasing && mode == Mode.NONE) {
                        cursor = floatArrayOf(e.x, e.y, eraserRadiusPx); invalidateScene()
                    } else if (cursor != null && mode == Mode.NONE) { cursor = null; invalidateScene() }
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    penHovering = false
                    if (cursor != null && mode == Mode.NONE) { cursor = null; invalidateScene() }
                }
            }
            return true
        }
        return super.onHoverEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val tt = e.getToolType(idx)
                if (isPenTool(tt)) {
                    lastPenSeen = SystemClock.uptimeMillis()
                    when (mode) {
                        Mode.INK -> {}
                        Mode.NAV -> { endNav(e, false, cancelled = true); startInk(e, idx, false) }
                        else -> startInk(e, idx, false)
                    }
                } else {
                    when (mode) {
                        Mode.NONE -> when {
                            fingersSuppressed() -> mode = Mode.IGNORE
                            fingerDraws && e.pointerCount == 1 -> startInk(e, idx, true)
                            else -> startNav(e)
                        }
                        Mode.NAV -> navPointerChange(e, -1)
                        Mode.INK -> if (inkFromFinger && e.eventTime - inkStartTime < 160) {
                            cancelInk(); startNav(e)
                        }
                        Mode.IGNORE -> {}
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.INK -> inkMove(e)
                Mode.NAV -> navMove(e)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                when (mode) {
                    Mode.INK -> if (e.getPointerId(idx) == inkPointerId) { inkUp(e, idx); mode = Mode.IGNORE }
                    Mode.NAV -> navPointerChange(e, idx)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.INK -> if (e.getPointerId(0) == inkPointerId) inkUp(e, 0)
                    Mode.NAV -> endNav(e, true, cancelled = false)
                    else -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.INK -> cancelInk()
                    Mode.NAV -> endNav(e, false, cancelled = true)
                    else -> {}
                }
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun startInk(e: MotionEvent, idx: Int, fromFinger: Boolean) {
        requestUnbufferedDispatch(e)
        scroller.forceFinished(true)
        mode = Mode.INK
        inkPointerId = e.getPointerId(idx)
        inkFromFinger = fromFinger
        inkStartTime = e.eventTime
        val tt = e.getToolType(idx)
        val sideButton = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        inkTool = if (tt == MotionEvent.TOOL_TYPE_ERASER || (sideButton && tool == Tool.PEN)) Tool.ERASER else tool
        val x = e.getX(idx); val y = e.getY(idx)
        when (inkTool) {
            Tool.PEN -> penDown(x, y, pressureOf(e, idx, -1))
            Tool.ERASER -> eraseDown(x, y)
            Tool.LASSO -> lassoDown(x, y)
        }
    }

    private fun inkMove(e: MotionEvent) {
        val idx = e.findPointerIndex(inkPointerId)
        if (idx < 0) return
        if (!inkFromFinger) lastPenSeen = SystemClock.uptimeMillis()
        when (inkTool) {
            Tool.PEN -> {
                for (h in 0 until e.historySize) penSample(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), pressureOf(e, idx, h))
                penSample(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1))
                emitWet()
            }
            Tool.ERASER -> {
                for (h in 0 until e.historySize) eraseTo(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseTo(e.getX(idx), e.getY(idx))
            }
            Tool.LASSO -> lassoMove(e.getX(idx), e.getY(idx))
        }
    }

    private fun inkUp(e: MotionEvent, idx: Int) {
        if (!inkFromFinger) lastPenSeen = SystemClock.uptimeMillis()
        when (inkTool) {
            Tool.PEN -> { penSample(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1)); emitWet(); penUp() }
            Tool.ERASER -> { eraseTo(e.getX(idx), e.getY(idx)); eraseUp() }
            Tool.LASSO -> { lassoMove(e.getX(idx), e.getY(idx)); lassoUp() }
        }
        inkPointerId = -1
    }

    private fun cancelInk() {
        when (inkTool) {
            Tool.PEN -> {
                renderer?.cancel()
                frontActive = false
                pts.clear(); spts.clear()
                if (pendingScene) { pendingScene = false; invalidateScene() }
            }
            Tool.ERASER -> eraseUp()
            Tool.LASSO -> { lassoScreen = null; lassoWorld = null; selDrag = 0; selection?.transform?.reset(); invalidateScene() }
        }
        inkPointerId = -1
        mode = Mode.NONE
    }

    private fun pressureOf(e: MotionEvent, idx: Int, h: Int): Float {
        val tt = e.getToolType(idx)
        if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) return 0.62f
        val p = if (h < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, h)
        return p.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- pen

    private val pts = FloatList()   // world x, y, r
    private val spts = FloatList()  // screen x, y, r
    private var emitted = 0         // samples already drawn to the front buffer
    private var smoothP = -1f
    private var strokeColor = INK_BLACK
    private var baseR = 1f

    private fun penDown(x: Float, y: Float, p: Float) {
        frontActive = true
        pts.clear(); spts.clear(); emitted = 0; smoothP = -1f
        strokeColor = penColor
        baseR = PEN_SIZES[penSize.coerceIn(0, 2)] / 2f
        penSample(x, y, p)
        renderer?.renderFrontBufferedLayer(WetBatch(strokeColor, spts.copyRange(0, 3), 1))
        emitted = 1
    }

    private fun penSample(x: Float, y: Float, p: Float) {
        val n = spts.size / 3
        if (n > 0) {
            val dx = x - spts[spts.size - 3]; val dy = y - spts[spts.size - 2]
            if (dx * dx + dy * dy < 0.36f) return
        }
        smoothP = if (smoothP < 0f) p else smoothP + 0.45f * (p - smoothP)
        val r = baseR * (0.28f + 0.92f * smoothP.pow(0.75f))
        pts.add(toWorldX(x)); pts.add(toWorldY(y)); pts.add(r)
        spts.add(x); spts.add(y); spts.add(r * scale)
    }

    private fun emitWet() {
        val n = spts.size / 3
        if (n - emitted < 1 || emitted == 0) return
        val start = emitted - 1
        val count = n - start
        if (count < 2) return
        renderer?.renderFrontBufferedLayer(WetBatch(strokeColor, spts.copyRange(start * 3, n * 3), count))
        emitted = n
    }

    private fun penUp() {
        val d = doc
        val n = pts.size / 3
        if (d != null && n > 0) {
            val xs = FloatArray(n) { pts[3 * it] }
            val ys = FloatArray(n) { pts[3 * it + 1] }
            val rs = FloatArray(n) { pts[3 * it + 2] }
            d.perform(Edit.Add(listOf(Stroke(d.newId(), strokeColor, xs, ys, rs))))
            listener?.onHistoryChanged()
        }
        frontActive = false
        pendingScene = false
        frame = buildFrame()
        renderer?.commit()
    }

    // ---------------------------------------------------------------- eraser

    private class EraseSession(val origIndex: HashMap<Long, Int>) {
        val removed = ArrayList<IndexedValue<Stroke>>()
        val added = LinkedHashMap<Long, Stroke>()
        var lastX = 0f
        var lastY = 0f
    }

    private var erase: EraseSession? = null

    private fun eraseDown(x: Float, y: Float) {
        val d = doc ?: return
        val map = HashMap<Long, Int>(d.strokes.size * 2)
        d.strokes.forEachIndexed { i, s -> map[s.id] = i }
        erase = EraseSession(map).also { it.lastX = toWorldX(x); it.lastY = toWorldY(y) }
        eraseAt(toWorldX(x), toWorldY(y))
        cursor = floatArrayOf(x, y, eraserRadiusPx)
        invalidateScene()
    }

    private fun eraseTo(x: Float, y: Float) {
        val s = erase ?: return
        val wx = toWorldX(x); val wy = toWorldY(y)
        val r = eraserRadiusPx / scale
        val dist = hypot(wx - s.lastX, wy - s.lastY)
        val steps = max(1, (dist / (r * 0.5f)).toInt())
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            eraseAt(s.lastX + (wx - s.lastX) * t, s.lastY + (wy - s.lastY) * t)
        }
        s.lastX = wx; s.lastY = wy
        cursor = floatArrayOf(x, y, eraserRadiusPx)
        invalidateScene()
    }

    private fun eraseAt(cx: Float, cy: Float) {
        val d = doc ?: return
        val s = erase ?: return
        val r = eraserRadiusPx / scale
        val hit = ArrayList<Stroke>()
        val pieces = ArrayList<Stroke>()
        for (st in d.strokes) {
            val b = st.bounds
            if (cx + r < b.left || cx - r > b.right || cy + r < b.top || cy - r > b.bottom) continue
            if (partialEraser) {
                val n = st.size
                val keep = BooleanArray(n)
                val brk = BooleanArray(n)
                var changed = false
                for (i in 0 until n) {
                    val dx = st.xs[i] - cx; val dy = st.ys[i] - cy
                    val lim = r + st.rs[i] * 0.5f
                    keep[i] = dx * dx + dy * dy > lim * lim
                    if (!keep[i]) changed = true
                }
                for (i in 0 until n - 1) {
                    if (keep[i] && keep[i + 1] &&
                        Geometry.distSqToSegment(cx, cy, st.xs[i], st.ys[i], st.xs[i + 1], st.ys[i + 1]) < r * r
                    ) { brk[i] = true; changed = true }
                }
                if (changed) { hit.add(st); pieces.addAll(st.split(keep, brk) { d.newId() }) }
            } else if (strokeHit(st, cx, cy, r)) {
                hit.add(st)
            }
        }
        if (hit.isEmpty()) return
        for (st in hit) {
            if (s.added.remove(st.id) == null) {
                s.removed.add(IndexedValue(s.origIndex[st.id] ?: d.strokes.size, st))
            }
        }
        d.removeStrokesById(hit.mapTo(HashSet()) { it.id })
        if (pieces.isNotEmpty()) {
            pieces.forEach { s.added[it.id] = it }
            d.appendStrokes(pieces)
        }
    }

    private fun strokeHit(st: Stroke, cx: Float, cy: Float, r: Float): Boolean {
        val n = st.size
        if (n == 1) {
            val lim = r + st.rs[0]
            val dx = st.xs[0] - cx; val dy = st.ys[0] - cy
            return dx * dx + dy * dy < lim * lim
        }
        for (i in 1 until n) {
            val lim = r + max(st.rs[i], st.rs[i - 1])
            if (Geometry.distSqToSegment(cx, cy, st.xs[i - 1], st.ys[i - 1], st.xs[i], st.ys[i]) < lim * lim) return true
        }
        return false
    }

    private fun eraseUp() {
        val s = erase ?: return
        erase = null
        val d = doc
        if (d != null && (s.removed.isNotEmpty() || s.added.isNotEmpty())) {
            d.record(Edit.Erase(ArrayList(s.removed), ArrayList(s.added.values)))
            listener?.onHistoryChanged()
        }
        cursor = if (penHovering && tool == Tool.ERASER) cursor else null
        invalidateScene()
    }

    // ---------------------------------------------------------------- lasso & selection

    class Selection(
        val strokes: List<Stroke>,
        val images: List<ImageItem>,
        val bounds: RectF,
        val chunk: Chunk?,
    ) {
        val strokeIds: Set<Long> = strokes.mapTo(HashSet()) { it.id }
        val imageIds: Set<Long> = images.mapTo(HashSet()) { it.id }
        val transform = Matrix()
        val hasStrokes get() = strokes.isNotEmpty()
    }

    var selection: Selection? = null
        private set
    private var lassoWorld: FloatList? = null
    private var selDrag = 0 // 0 lasso, 1 move, 2 scale
    private var dragX = 0f
    private var dragY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var dragLen = 1f

    /** Selection rectangle in screen coordinates (including padding), or null. */
    fun selectionScreenRect(): RectF? {
        val s = selection ?: return null
        val r = RectF(s.bounds)
        s.transform.mapRect(r)
        r.set(r.left * scale + tx, r.top * scale + ty, r.right * scale + tx, r.bottom * scale + ty)
        r.inset(-6f * density, -6f * density)
        return r
    }

    private fun lassoDown(x: Float, y: Float) {
        val sel = selection
        val sr = selectionScreenRect()
        if (sel != null && sr != null) {
            val handleHit = hypot(x - sr.right, y - sr.bottom) < 18f * density
            val pad = 4f * density
            if (handleHit) {
                selDrag = 2
                anchorX = sel.bounds.left; anchorY = sel.bounds.top
                dragLen = max(1e-3f, hypot(toWorldX(x) - anchorX, toWorldY(y) - anchorY))
                return
            }
            if (x >= sr.left - pad && x <= sr.right + pad && y >= sr.top - pad && y <= sr.bottom + pad) {
                selDrag = 1
                dragX = toWorldX(x); dragY = toWorldY(y)
                return
            }
            clearSelection()
        }
        selDrag = 0
        lassoScreen = FloatList().apply { add(x); add(y) }
        lassoWorld = FloatList().apply { add(toWorldX(x)); add(toWorldY(y)) }
        invalidateScene()
    }

    private fun lassoMove(x: Float, y: Float) {
        when (selDrag) {
            0 -> {
                val ls = lassoScreen ?: return
                val dx = x - ls[ls.size - 2]; val dy = y - ls[ls.size - 1]
                if (dx * dx + dy * dy < 4f) return
                ls.add(x); ls.add(y)
                lassoWorld?.apply { add(toWorldX(x)); add(toWorldY(y)) }
                invalidateScene()
            }
            1 -> {
                val sel = selection ?: return
                sel.transform.setTranslate(toWorldX(x) - dragX, toWorldY(y) - dragY)
                invalidateScene(); listener?.onSelectionChanged()
            }
            2 -> {
                val sel = selection ?: return
                val s = (hypot(toWorldX(x) - anchorX, toWorldY(y) - anchorY) / dragLen).coerceIn(0.05f, 50f)
                sel.transform.setScale(s, s, anchorX, anchorY)
                invalidateScene(); listener?.onSelectionChanged()
            }
        }
    }

    private fun lassoUp() {
        when (selDrag) {
            0 -> finishLasso()
            else -> commitTransform()
        }
        selDrag = 0
    }

    private fun finishLasso() {
        val d = doc ?: return
        val poly = lassoWorld?.toArray()
        val screen = lassoScreen?.toArray()
        lassoScreen = null; lassoWorld = null
        invalidateScene()
        if (poly == null || screen == null) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until screen.size / 2) {
            minX = min(minX, screen[2 * i]); maxX = max(maxX, screen[2 * i])
            minY = min(minY, screen[2 * i + 1]); maxY = max(maxY, screen[2 * i + 1])
        }
        val strokes: List<Stroke>
        val imgs: List<ImageItem>
        if (poly.size < 6 || hypot(maxX - minX, maxY - minY) < 12f * density) {
            // A tap: pick the topmost thing under the pen.
            val px = poly[0]; val py = poly[1]
            val r = 8f * density / scale
            val st = d.strokes.lastOrNull { strokeHit(it, px, py, r) }
            strokes = listOfNotNull(st)
            imgs = if (st == null) listOfNotNull(d.images.lastOrNull { it.rect.contains(px, py) }) else emptyList()
        } else {
            val pb = RectF(
                toWorldX(minX), toWorldY(minY), toWorldX(maxX), toWorldY(maxY)
            )
            strokes = d.strokes.filter { st ->
                if (!RectF.intersects(st.bounds, pb)) return@filter false
                var inside = 0
                for (i in 0 until st.size) if (Geometry.pointInPolygon(st.xs[i], st.ys[i], poly)) inside++
                inside * 2 >= st.size
            }
            imgs = d.images.filter { Geometry.pointInPolygon(it.rect.centerX(), it.rect.centerY(), poly) }
        }
        if (strokes.isEmpty() && imgs.isEmpty()) return
        setSelection(strokes, imgs)
    }

    private fun setSelection(strokes: List<Stroke>, imgs: List<ImageItem>) {
        if (strokes.isEmpty() && imgs.isEmpty()) { clearSelection(); return }
        val b = RectF()
        strokes.forEach { b.union(it.bounds) }
        imgs.forEach { b.union(it.rect) }
        val chunk = if (strokes.isNotEmpty()) ChunkRecorder.record(strokes) else null
        selection = Selection(strokes, imgs, b, chunk)
        for (k in chunks.indices) chunks[k] = null
        invalidateScene()
        listener?.onSelectionChanged()
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        for (k in chunks.indices) chunks[k] = null
        invalidateScene()
        listener?.onSelectionChanged()
    }

    private fun commitTransform() {
        val sel = selection ?: return
        val d = doc ?: return
        val m = Matrix(sel.transform)
        val v = FloatArray(9); m.getValues(v)
        val moved = abs(v[Matrix.MTRANS_X]) * scale > 1f || abs(v[Matrix.MTRANS_Y]) * scale > 1f ||
            abs(v[Matrix.MSCALE_X] - 1f) > 1e-3f
        if (!moved) { sel.transform.reset(); invalidateScene(); return }
        val s = sqrt(abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y]))
        val ns = sel.strokes.map { it.transformed(m, s) }
        val ni = sel.images.map { it.transformed(m) }
        d.perform(Edit.Replace(sel.strokes, ns, sel.images, ni))
        listener?.onHistoryChanged()
        if (autoReturnAfterLasso) finishOperation() else setSelection(ns, ni)
    }

    fun deleteSelection() {
        val sel = selection ?: return
        val d = doc ?: return
        d.perform(Edit.Remove(sel.strokeIds, sel.imageIds))
        clearSelection()
        listener?.onHistoryChanged()
        finishOperation()
    }

    fun recolorSelection(color: Int) {
        val sel = selection ?: return
        val d = doc ?: return
        if (!sel.hasStrokes) return
        val ns = sel.strokes.map { it.withColor(color) }
        d.perform(Edit.Replace(sel.strokes, ns))
        listener?.onHistoryChanged()
        if (autoReturnAfterLasso) finishOperation() else setSelection(ns, sel.images)
    }

    /** Copies sit slightly offset and become the new selection, ready to be dragged. */
    fun duplicateSelection() {
        val sel = selection ?: return
        val d = doc ?: return
        val off = 18f * density / scale
        val ns = sel.strokes.map { it.copyWithId(d.newId(), off, off) }
        val ni = sel.images.map { it.copyWithId(d.newId(), off, off) }
        d.perform(Edit.Add(ns, ni))
        listener?.onHistoryChanged()
        setSelection(ns, ni)
    }

    /** After a lasso operation, optionally jump back to the tool used before the lasso. */
    private fun finishOperation() {
        if (!autoReturnAfterLasso) return
        clearSelection()
        if (tool == Tool.LASSO) {
            tool = if (returnTool == Tool.LASSO) Tool.PEN else returnTool
            invalidateScene()
            listener?.onToolChanged()
        }
    }

    /** Places an imported picture in the middle of the view and selects it. */
    fun insertImage(file: String, w: Int, h: Int) {
        val d = doc ?: return
        val vis = visibleWorld()
        val f = min(min(vis.width() * 0.6f / w, vis.height() * 0.6f / h), 0.5f)
        val iw = w * f; val ih = h * f
        val rect = RectF(vis.centerX() - iw / 2, vis.centerY() - ih / 2, vis.centerX() + iw / 2, vis.centerY() + ih / 2)
        val item = ImageItem(d.newId(), file, rect)
        d.perform(Edit.Add(emptyList(), listOf(item)))
        listener?.onHistoryChanged()
        if (tool != Tool.LASSO) {
            returnTool = tool
            tool = Tool.LASSO
            listener?.onToolChanged()
        }
        setSelection(emptyList(), listOf(item))
    }

    // ---------------------------------------------------------------- undo / redo

    fun undo() {
        val d = doc ?: return
        clearSelection()
        if (d.undo()) { listener?.onHistoryChanged(); listener?.onHint("撤销") }
    }

    fun redo() {
        val d = doc ?: return
        clearSelection()
        if (d.redo()) { listener?.onHistoryChanged(); listener?.onHint("重做") }
    }

    // ---------------------------------------------------------------- navigation

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var navFx = 0f
    private var navFy = 0f
    private var navSpan = 0f
    private var navCount = 0
    private var navStart = 0L
    private var navMaxPointers = 0
    private var navMovement = 0f
    private var flingX = 0
    private var flingY = 0

    private fun startNav(e: MotionEvent) {
        mode = Mode.NAV
        scroller.forceFinished(true)
        navStart = e.eventTime
        navMaxPointers = e.pointerCount
        navMovement = 0f
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        navReference(e, -1)
    }

    private fun navReference(e: MotionEvent, exclude: Int) {
        var sx = 0f; var sy = 0f; var c = 0
        for (i in 0 until e.pointerCount) { if (i == exclude) continue; sx += e.getX(i); sy += e.getY(i); c++ }
        if (c == 0) { navCount = 0; return }
        val fx = sx / c; val fy = sy / c
        var span = 0f
        for (i in 0 until e.pointerCount) { if (i == exclude) continue; span += hypot(e.getX(i) - fx, e.getY(i) - fy) }
        navFx = fx; navFy = fy; navSpan = if (c > 1) span / c else 0f; navCount = c
    }

    private fun navPointerChange(e: MotionEvent, liftingIndex: Int) {
        navMaxPointers = max(navMaxPointers, e.pointerCount)
        velocity?.addMovement(e)
        navReference(e, liftingIndex)
    }

    private fun navMove(e: MotionEvent) {
        velocity?.addMovement(e)
        val prevFx = navFx; val prevFy = navFy; val prevSpan = navSpan; val prevCount = navCount
        navReference(e, -1)
        if (navCount != prevCount) return
        val dx = navFx - prevFx; val dy = navFy - prevFy
        tx += dx; ty += dy
        navMovement += abs(dx) + abs(dy)
        if (navCount >= 2 && prevSpan > 0f && navSpan > 0f) {
            zoomBy(navSpan / prevSpan, navFx, navFy)
            navMovement += abs(navSpan - prevSpan)
        }
        afterViewportChange(false)
    }

    private fun endNav(e: MotionEvent, allowFling: Boolean, cancelled: Boolean) {
        val vt = velocity
        velocity = null
        val dur = e.eventTime - navStart
        if (!cancelled && twoFingerTapUndo && navMaxPointers >= 2 && dur < 300 && navMovement < 16f * density) {
            if (navMaxPointers == 2) undo() else redo()
        } else if (allowFling && navMaxPointers == 1 && vt != null) {
            vt.addMovement(e)
            vt.computeCurrentVelocity(1000)
            val vx = vt.xVelocity; val vy = vt.yVelocity
            if (hypot(vx, vy) > 400f * density) startFling(vx, vy)
        }
        vt?.recycle()
        mode = Mode.NONE
        afterViewportChange(true)
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val x = scroller.currX; val y = scroller.currY
                tx += (x - flingX); ty += (y - flingY)
                flingX = x; flingY = y
                afterViewportChange(false)
                postOnAnimation(this)
            } else afterViewportChange(true)
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        flingX = 0; flingY = 0
        scroller.fling(0, 0, vx.toInt(), vy.toInt(), -1_000_000, 1_000_000, -1_000_000, 1_000_000)
        postOnAnimation(flingRunnable)
    }
}

/** Minimal growable float array. */
class FloatList(cap: Int = 192) {
    private var a = FloatArray(cap)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): FloatArray = a.copyOf(size)
    fun copyRange(from: Int, to: Int): FloatArray = a.copyOfRange(from, to)
}
