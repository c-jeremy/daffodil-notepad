package app.daffodil.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hold-to-perfect: turns a rough stroke into a straight line, an ellipse or a rectangle.
 * Returns the replacement outline as packed world x,y pairs, or null if nothing fits well.
 */
object Shapes {
    private const val DEG = (Math.PI / 180.0).toFloat()

    fun recognize(xs: FloatArray, ys: FloatArray, minSize: Float): FloatArray? {
        val n = xs.size
        if (n < 3) return null
        // Path length on a coarse resampling, so sensor jitter does not inflate it.
        val ext0 = max(xs.max() - xs.min(), ys.max() - ys.min())
        val step = max(minSize * 0.3f, ext0 * 0.04f)
        var len = 0f
        var px = xs[0]; var py = ys[0]
        for (i in 1 until n) {
            val d = hypot(xs[i] - px, ys[i] - py)
            if (d >= step || i == n - 1) { len += d; px = xs[i]; py = ys[i] }
        }
        if (len < minSize) return null
        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])

        // Straight line: the path is barely longer than its chord and stays close to it.
        if (chord > minSize && chord / len > 0.9f) {
            var maxDev = 0f
            val ux = (xs[n - 1] - xs[0]) / chord; val uy = (ys[n - 1] - ys[0]) / chord
            for (i in 0 until n) maxDev = max(maxDev, abs((xs[i] - xs[0]) * uy - (ys[i] - ys[0]) * ux))
            if (maxDev < max(0.08f * chord, minSize * 0.15f)) return line(xs[0], ys[0], xs[n - 1], ys[n - 1])
        }

        // Closed shapes: the end comes back near the start.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); minY = min(minY, ys[i]); maxY = max(maxY, ys[i]) }
        val extent = max(maxX - minX, maxY - minY)
        if (extent < minSize || chord > 0.3f * extent || len < 2.2f * extent) return null

        // Orientation: the angle whose bounding box is smallest (handles tilted shapes).
        var bestA = 0f
        var bestArea = Float.MAX_VALUE
        var a = 0f
        while (a < 90f) {
            val c = cos(a * DEG); val s = sin(a * DEG)
            var lx = Float.MAX_VALUE; var hx = -Float.MAX_VALUE; var ly = Float.MAX_VALUE; var hy = -Float.MAX_VALUE
            for (i in 0 until n) {
                val rx = xs[i] * c + ys[i] * s; val ry = -xs[i] * s + ys[i] * c
                lx = min(lx, rx); hx = max(hx, rx); ly = min(ly, ry); hy = max(hy, ry)
            }
            val area = (hx - lx) * (hy - ly)
            if (area < bestArea) { bestArea = area; bestA = a }
            a += 2f
        }
        // Prefer upright shapes when the tilt is small.
        if (bestA < 7f || bestA > 83f) bestA = 0f

        val c = cos(bestA * DEG); val s = sin(bestA * DEG)
        val rx = FloatArray(n) { xs[it] * c + ys[it] * s }
        val ry = FloatArray(n) { -xs[it] * s + ys[it] * c }
        val lx = rx.min(); val hx = rx.max(); val ly = ry.min(); val hy = ry.max()
        val cx = (lx + hx) / 2; val cy = (ly + hy) / 2
        val ea = (hx - lx) / 2; val eb = (hy - ly) / 2
        if (min(ea, eb) < 0.08f * max(ea, eb)) return null

        var ellErr = 0f
        var rectErr = 0f
        val m = min(ea, eb)
        for (i in 0 until n) {
            val dx = rx[i] - cx; val dy = ry[i] - cy
            ellErr += abs(sqrt((dx / ea) * (dx / ea) + (dy / eb) * (dy / eb)) - 1f)
            rectErr += min(min(abs(rx[i] - lx), abs(rx[i] - hx)), min(abs(ry[i] - ly), abs(ry[i] - hy))) / m
        }
        ellErr /= n; rectErr /= n

        val local = when {
            ellErr <= rectErr && ellErr < 0.14f -> ellipse(cx, cy, ea, eb)
            rectErr < ellErr && rectErr < 0.11f -> rect(lx, ly, hx, hy)
            else -> return null
        }
        // Rotate back into world space.
        val out = FloatArray(local.size)
        for (i in 0 until local.size / 2) {
            val px = local[2 * i]; val py = local[2 * i + 1]
            out[2 * i] = px * c - py * s
            out[2 * i + 1] = px * s + py * c
        }
        return out
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        var ex = x1; var ey = y1
        // Snap nearly horizontal / vertical / 45° lines.
        val ang = atan2(ey - y0, ex - x0) / DEG
        val len = hypot(ex - x0, ey - y0)
        val snapped = Math.round(ang / 45f) * 45f
        if (abs(ang - snapped) < 4f) { ex = x0 + len * cos(snapped * DEG); ey = y0 + len * sin(snapped * DEG) }
        val steps = max(2, (len / 2f).toInt())
        return FloatArray((steps + 1) * 2).also { o ->
            for (i in 0..steps) { val t = i / steps.toFloat(); o[2 * i] = x0 + (ex - x0) * t; o[2 * i + 1] = y0 + (ey - y0) * t }
        }
    }

    private fun ellipse(cx: Float, cy: Float, a: Float, b: Float): FloatArray {
        val steps = 96
        return FloatArray((steps + 1) * 2).also { o ->
            for (i in 0..steps) {
                val t = i * 2f * Math.PI.toFloat() / steps
                o[2 * i] = cx + a * cos(t); o[2 * i + 1] = cy + b * sin(t)
            }
        }
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val corners = floatArrayOf(l, t, r, t, r, b, l, b, l, t)
        val per = 16
        val out = FloatArray((4 * per + 1) * 2)
        var k = 0
        for (e in 0 until 4) {
            val x0 = corners[2 * e]; val y0 = corners[2 * e + 1]; val x1 = corners[2 * e + 2]; val y1 = corners[2 * e + 3]
            for (i in 0 until per) {
                val tt = i / per.toFloat()
                out[k++] = x0 + (x1 - x0) * tt; out[k++] = y0 + (y1 - y0) * tt
            }
        }
        out[k++] = l; out[k] = t
        return out
    }
}
