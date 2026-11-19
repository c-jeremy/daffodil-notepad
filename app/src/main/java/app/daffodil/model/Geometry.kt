package app.daffodil.model

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ink geometry. A stroke is a chain of circles (x, y, radius); its outline is the
 * union of "capsules" joining consecutive circles. Every contour is built with
 * the same winding direction, so a single WINDING-filled Path renders the union
 * with no seams. Wet (front-buffer) and dry rendering share this builder, so ink
 * does not change shape when the pen lifts.
 */
object Geometry {
    private const val RAD2DEG = (180.0 / Math.PI).toFloat()
    // Paths are built on both the UI thread and the render thread.
    private val ovals = ThreadLocal.withInitial { RectF() }

    /** Appends one capsule between circle 0 and circle 1 (counter-clockwise on screen). */
    fun addCapsule(path: Path, x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float) {
        val oval = ovals.get()!!
        val dx = x1 - x0
        val dy = y1 - y0
        val d = sqrt(dx * dx + dy * dy)
        if (d <= abs(r1 - r0) + 1e-4f) {
            // One circle contains the other: the bigger one is the whole shape.
            if (r1 >= r0) addCircle(path, x1, y1, r1) else addCircle(path, x0, y0, r0)
            return
        }
        val ux = dx / d
        val uy = dy / d
        val cosA = ((r0 - r1) / d).coerceIn(-1f, 1f)
        val a = acos(cosA)
        val ca = kotlin.math.cos(a)
        val sa = kotlin.math.sin(a)
        // n1 = u rotated by +a (screen-clockwise), n2 = u rotated by -a.
        val n1x = ux * ca - uy * sa
        val n1y = ux * sa + uy * ca
        val n2x = ux * ca + uy * sa
        val n2y = -ux * sa + uy * ca

        val baseAngle = atan2(uy, ux) * RAD2DEG
        val aDeg = a * RAD2DEG

        path.moveTo(x0 + r0 * n1x, y0 + r0 * n1y)
        path.lineTo(x1 + r1 * n1x, y1 + r1 * n1y)
        // Around the far end: from +a through 0 to -a (negative sweep).
        oval.set(x1 - r1, y1 - r1, x1 + r1, y1 + r1)
        path.arcTo(oval, baseAngle + aDeg, -2f * aDeg, false)
        path.lineTo(x0 + r0 * n2x, y0 + r0 * n2y)
        // Around the near end: from -a through 180 to +a (negative sweep).
        oval.set(x0 - r0, y0 - r0, x0 + r0, y0 + r0)
        path.arcTo(oval, baseAngle - aDeg, -(360f - 2f * aDeg), false)
        path.close()
    }

    /** Full circle, counter-clockwise on screen, consistent with [addCapsule]. */
    fun addCircle(path: Path, x: Float, y: Float, r: Float) {
        val oval = ovals.get()!!
        oval.set(x - r, y - r, x + r, y + r)
        path.arcTo(oval, 0f, -180f, true)
        path.arcTo(oval, 180f, -180f, false)
        path.close()
    }

    fun strokePath(xs: FloatArray, ys: FloatArray, rs: FloatArray): Path {
        val p = Path()
        p.fillType = Path.FillType.WINDING
        val n = xs.size
        if (n == 0) return p
        if (n == 1) {
            addCircle(p, xs[0], ys[0], rs[0]); return p
        }
        var px = xs[0]; var py = ys[0]; var pr = rs[0]
        for (i in 1 until n) {
            val x = xs[i]; val y = ys[i]; val r = rs[i]
            val isLast = i == n - 1
            val dx = x - px; val dy = y - py
            val minStep = 0.3f * minOf(pr, r)
            if (!isLast && dx * dx + dy * dy < minStep * minStep && abs(r - pr) < 0.08f * pr) continue
            addCapsule(p, px, py, pr, x, y, r)
            px = x; py = y; pr = r
        }
        return p
    }

    fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val wx = px - ax; val wy = py - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 <= 1e-9f) 0f else ((wx * vx + wy * vy) / len2).coerceIn(0f, 1f)
        val cx = ax + t * vx - px
        val cy = ay + t * vy - py
        return cx * cx + cy * cy
    }

    /** Even-odd point-in-polygon; poly is packed x,y pairs. */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
