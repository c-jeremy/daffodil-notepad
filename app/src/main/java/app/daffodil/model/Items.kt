package app.daffodil.model

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF

/** Immutable ink stroke in world units. rs holds the radius at each sample. */
class Stroke(
    val id: Long,
    val color: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val rs: FloatArray,
) {
    val size: Int get() = xs.size

    val bounds: RectF = RectF().also { b ->
        if (xs.isEmpty()) return@also
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
        for (i in xs.indices) {
            val rad = rs[i]
            if (xs[i] - rad < l) l = xs[i] - rad
            if (ys[i] - rad < t) t = ys[i] - rad
            if (xs[i] + rad > r) r = xs[i] + rad
            if (ys[i] + rad > bt) bt = ys[i] + rad
        }
        b.set(l, t, r, bt)
    }

    val path: Path by lazy { Geometry.strokePath(xs, ys, rs) }

    fun withColor(c: Int) = Stroke(id, c, xs, ys, rs)

    fun transformed(m: Matrix, scale: Float): Stroke {
        val pts = FloatArray(size * 2)
        for (i in 0 until size) { pts[2 * i] = xs[i]; pts[2 * i + 1] = ys[i] }
        m.mapPoints(pts)
        val nx = FloatArray(size) { pts[2 * it] }
        val ny = FloatArray(size) { pts[2 * it + 1] }
        val nr = FloatArray(size) { rs[it] * scale }
        return Stroke(id, color, nx, ny, nr)
    }

    fun copyWithId(newId: Long, dx: Float, dy: Float) =
        Stroke(newId, color, FloatArray(size) { xs[it] + dx }, FloatArray(size) { ys[it] + dy }, rs.copyOf())

    /**
     * Splits the stroke into pieces, keeping samples where keep[i] is true and
     * cutting the chain after i where breakAfter[i] is true.
     */
    fun split(keep: BooleanArray, breakAfter: BooleanArray?, idGen: () -> Long): List<Stroke> {
        val out = ArrayList<Stroke>()
        var start = -1
        fun flush(end: Int) {
            if (start >= 0 && end > start) {
                out.add(Stroke(idGen(), color, xs.copyOfRange(start, end), ys.copyOfRange(start, end), rs.copyOfRange(start, end)))
            }
            start = -1
        }
        for (i in 0 until size) {
            if (!keep[i]) { flush(i); continue }
            if (start < 0) start = i
            if (breakAfter != null && breakAfter[i]) flush(i + 1)
        }
        flush(size)
        return out
    }
}

/** An imported picture placed in world space. [file] lives in the document folder. */
class ImageItem(val id: Long, val file: String, val rect: RectF) {
    fun transformed(m: Matrix): ImageItem {
        val r = RectF(rect); m.mapRect(r); return ImageItem(id, file, r)
    }

    fun copyWithId(newId: Long, dx: Float, dy: Float) =
        ImageItem(newId, file, RectF(rect).apply { offset(dx, dy) })
}
