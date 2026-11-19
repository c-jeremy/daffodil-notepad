package app.daffodil.model

import java.io.File

enum class DocKind { CANVAS, PDF }

class DocMeta(
    val id: String,
    var title: String,
    val kind: DocKind,
    val created: Long,
    var modified: Long,
    var pageCount: Int = 0,
    // Saved view: world point at the screen centre and zoom (screen dp per world unit).
    var viewCx: Float = Float.NaN,
    var viewCy: Float = Float.NaN,
    var viewZoom: Float = Float.NaN,
    /** Paper pattern for blank canvases: 0 blank, 1 dots, 2 lines, 3 grid. */
    var paper: Int = 0,
)

interface DocListener {
    /** Strokes from [from] onward may have changed (inserted/removed/shifted). */
    fun onStrokesChanged(from: Int)
    fun onImagesChanged()
}

class Document(val dir: File, val meta: DocMeta) {
    val strokes = ArrayList<Stroke>()
    val images = ArrayList<ImageItem>()
    var nextId = 1L
    var listener: DocListener? = null

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    var revision = 0L
        private set

    fun newId(): Long = nextId++

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun perform(edit: Edit) {
        edit.apply(this)
        undoStack.addLast(edit)
        if (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        revision++
    }

    /** Pushes an edit whose effect has already been applied live (e.g. erasing). */
    fun record(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        revision++
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        e.revert(this); redoStack.addLast(e); revision++
        return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        e.apply(this); undoStack.addLast(e); revision++
        return true
    }

    // --- primitive mutations used by edits -------------------------------------------------

    internal fun appendStrokes(list: List<Stroke>) {
        val from = strokes.size
        strokes.addAll(list)
        listener?.onStrokesChanged(from)
    }

    internal fun removeStrokesById(ids: Set<Long>): List<IndexedValue<Stroke>> {
        val removed = ArrayList<IndexedValue<Stroke>>()
        var first = -1
        val it = strokes.listIterator()
        var idx = 0
        while (it.hasNext()) {
            val s = it.next()
            if (s.id in ids) {
                removed.add(IndexedValue(idx, s))
                if (first < 0) first = idx
                it.remove()
            }
            idx++ // original index (pre-removal)
        }
        if (first >= 0) listener?.onStrokesChanged(first)
        return removed
    }

    internal fun insertStrokes(entries: List<IndexedValue<Stroke>>) {
        if (entries.isEmpty()) return
        val sorted = entries.sortedBy { it.index }
        for (e in sorted) strokes.add(e.index.coerceAtMost(strokes.size), e.value)
        listener?.onStrokesChanged(sorted.first().index)
    }

    internal fun replaceStrokes(map: Map<Long, Stroke>) {
        var first = -1
        for (i in strokes.indices) {
            val n = map[strokes[i].id] ?: continue
            strokes[i] = n
            if (first < 0) first = i
        }
        if (first >= 0) listener?.onStrokesChanged(first)
    }

    internal fun appendImages(list: List<ImageItem>) { images.addAll(list); listener?.onImagesChanged() }

    internal fun removeImagesById(ids: Set<Long>): List<IndexedValue<ImageItem>> {
        val removed = ArrayList<IndexedValue<ImageItem>>()
        var idx = 0
        val it = images.listIterator()
        while (it.hasNext()) {
            val im = it.next()
            if (im.id in ids) { removed.add(IndexedValue(idx, im)); it.remove() }
            idx++
        }
        if (removed.isNotEmpty()) listener?.onImagesChanged()
        return removed
    }

    internal fun insertImages(entries: List<IndexedValue<ImageItem>>) {
        if (entries.isEmpty()) return
        for (e in entries.sortedBy { it.index }) images.add(e.index.coerceAtMost(images.size), e.value)
        listener?.onImagesChanged()
    }

    internal fun replaceImages(map: Map<Long, ImageItem>) {
        var any = false
        for (i in images.indices) {
            val n = map[images[i].id] ?: continue
            images[i] = n; any = true
        }
        if (any) listener?.onImagesChanged()
    }
}

/** Undoable change. Strokes and images are immutable, so edits just swap references. */
sealed class Edit {
    abstract fun apply(d: Document)
    abstract fun revert(d: Document)

    class Add(val strokes: List<Stroke>, val images: List<ImageItem> = emptyList()) : Edit() {
        override fun apply(d: Document) {
            if (strokes.isNotEmpty()) d.appendStrokes(strokes)
            if (images.isNotEmpty()) d.appendImages(images)
        }
        override fun revert(d: Document) {
            if (strokes.isNotEmpty()) d.removeStrokesById(strokes.mapTo(HashSet()) { it.id })
            if (images.isNotEmpty()) d.removeImagesById(images.mapTo(HashSet()) { it.id })
        }
    }

    class Remove(private val strokeIds: Set<Long>, private val imageIds: Set<Long> = emptySet()) : Edit() {
        private var removedStrokes: List<IndexedValue<Stroke>> = emptyList()
        private var removedImages: List<IndexedValue<ImageItem>> = emptyList()
        override fun apply(d: Document) {
            if (strokeIds.isNotEmpty()) removedStrokes = d.removeStrokesById(strokeIds)
            if (imageIds.isNotEmpty()) removedImages = d.removeImagesById(imageIds)
        }
        override fun revert(d: Document) {
            d.insertStrokes(removedStrokes)
            d.insertImages(removedImages)
        }
    }

    /** In-place replacement (recolour, move, scale). */
    class Replace(
        private val oldStrokes: List<Stroke>, private val newStrokes: List<Stroke>,
        private val oldImages: List<ImageItem> = emptyList(), private val newImages: List<ImageItem> = emptyList(),
    ) : Edit() {
        override fun apply(d: Document) {
            if (newStrokes.isNotEmpty()) d.replaceStrokes(newStrokes.associateBy { it.id })
            if (newImages.isNotEmpty()) d.replaceImages(newImages.associateBy { it.id })
        }
        override fun revert(d: Document) {
            if (oldStrokes.isNotEmpty()) d.replaceStrokes(oldStrokes.associateBy { it.id })
            if (oldImages.isNotEmpty()) d.replaceImages(oldImages.associateBy { it.id })
        }
    }

    /**
     * Result of one eraser gesture: originals that were removed (with their indices)
     * and the fragments that remain. Applied live while erasing, recorded at the end.
     */
    class Erase(
        private val removed: List<IndexedValue<Stroke>>,
        private val added: List<Stroke>,
    ) : Edit() {
        override fun apply(d: Document) {
            d.removeStrokesById(removed.mapTo(HashSet()) { it.value.id })
            if (added.isNotEmpty()) d.appendStrokes(added)
        }
        override fun revert(d: Document) {
            if (added.isNotEmpty()) d.removeStrokesById(added.mapTo(HashSet()) { it.id })
            d.insertStrokes(removed)
        }
    }
}
