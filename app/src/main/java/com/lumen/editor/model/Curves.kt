package com.lumen.editor.model

/** Monotone cubic (Fritsch–Carlson) interpolation through curve points, sampled to a LUT. */
object Curves {
    const val LUT_SIZE = 256

    fun sample(points: List<CurvePoint>, out: FloatArray = FloatArray(LUT_SIZE)): FloatArray {
        val pts = points.sortedBy { it.x }
        val n = pts.size
        if (n < 2) {
            for (i in out.indices) out[i] = i / (out.size - 1f)
            return out
        }
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }
        val d = FloatArray(n - 1) { i ->
            val dx = xs[i + 1] - xs[i]
            if (dx <= 1e-6f) 0f else (ys[i + 1] - ys[i]) / dx
        }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
            }
        }
        var seg = 0
        for (k in out.indices) {
            val x = k / (out.size - 1f)
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (seg < n - 2 && x > xs[seg + 1]) seg++
                    val h = xs[seg + 1] - xs[seg]
                    val t = (x - xs[seg]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
                }
            }
            out[k] = y.coerceIn(0f, 1f)
        }
        return out
    }
}
