package com.bubigter.hanjangpdf.core

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * PC판(PyMuPDF) 좌표 계산을 글자 하나까지 같게 옮긴 것.
 *
 * PyMuPDF의 Point/Rect 값은 파이썬 double이지만, 행렬 곱(Rect * Matrix, Point * Matrix)과
 * 교집합(&)은 MuPDF C 함수(float32)로 계산된다. 그래서 여기서도 같은 자리에서만 Float로 계산하고
 * 나머지는 Double로 계산한다. 좌표는 모두 '돌리기 전 쪽 좌표'(PyMuPDF 규약)이다.
 */

/** 파이썬 max(a, b): 앞의 것을 두고, 뒤의 것이 '더 클 때만' 바꾼다(-0.0 처리까지 같게). */
fun pyMax(a: Double, b: Double): Double = if (b > a) b else a

/** 파이썬 min(a, b). */
fun pyMin(a: Double, b: Double): Double = if (b < a) b else a

/** 파이썬 f"{x:.2f}": 이진 값 그대로를 반올림(짝수 쪽)하고 음수 0은 "-0.00". */
fun pyFmt2(x: Double): String {
    val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
    val s = BigDecimal(Math.abs(x)).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
    return if (neg) "-$s" else s
}

/** MuPDF fz_matrix(float). */
data class PMatrix(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    companion object {
        val IDENTITY = PMatrix(1f, 0f, 0f, 1f, 0f, 0f)

        /** PyMuPDF util_invert_matrix(파이썬 double로 계산)와 같은 역행렬. */
        fun invert(m: PMatrix): PMatrix {
            val sa = m.a.toDouble(); val sb = m.b.toDouble(); val sc = m.c.toDouble()
            val sd = m.d.toDouble(); val se = m.e.toDouble(); val sf = m.f.toDouble()
            val det = sa * sd - sb * sc
            require(det < -2.220446049250313e-16 || det > 2.220446049250313e-16) { "역행렬 없음" }
            val rdet = 1 / det
            val da = sd * rdet
            val db = -sb * rdet
            val dc = -sc * rdet
            val dd = sa * rdet
            val de = -se * da - sf * dc
            val df = -se * db - sf * dd
            return PMatrix(da.toFloat(), db.toFloat(), dc.toFloat(), dd.toFloat(), de.toFloat(), df.toFloat())
        }

        /**
         * PyMuPDF page.rotation_matrix(돌리기 전 → 화면 방향). w0·h0은 돌리기 전 쪽 폭·높이.
         * 값(부호 있는 0 포함)은 PyMuPDF 2.0이 내는 값을 그대로 옮겼다.
         */
        fun rotation(rot: Int, w0: Float, h0: Float): PMatrix = when (((rot % 360) + 360) % 360) {
            90 -> PMatrix(0f, 1f, -1f, 0f, h0, 0f)
            180 -> PMatrix(-1f, 0f, 0f, -1f, w0, h0)
            270 -> PMatrix(0f, -1f, 1f, 0f, 0f, w0)
            else -> IDENTITY
        }
    }
}

data class PPoint(val x: Double, val y: Double) {
    /** pymupdf Point * Matrix (fz_transform_point, float). */
    operator fun times(m: PMatrix): PPoint {
        val px = x.toFloat(); val py = y.toFloat()
        val nx = px * m.a + py * m.c + m.e
        val ny = px * m.b + py * m.d + m.f
        return PPoint(nx.toDouble(), ny.toDouble())
    }
}

data class PRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    // 파이썬 max(0, x1 - x0): 0(int)이 앞이라 같으면 0
    val width: Double get() = if (x1 - x0 > 0) x1 - x0 else 0.0
    val height: Double get() = if (y1 - y0 > 0) y1 - y0 else 0.0
    val isEmpty: Boolean get() = x0 >= x1 || y0 >= y1
    val tl get() = PPoint(x0, y0)

    fun normalize(): PRect {
        var a = x0; var b = x1; var c = y0; var d = y1
        if (b < a) { val t = a; a = b; b = t }
        if (d < c) { val t = c; c = d; d = t }
        return PRect(a, c, b, d)
    }

    /**
     * pymupdf Rect * Matrix (fz_transform_rect, float). 회전·이동 행렬만 쓰므로(성분이 0·±1)
     * 곱은 정확하고 결과는 네 모서리를 float로 옮긴 bbox와 같다.
     */
    operator fun times(m: PMatrix): PRect {
        val fx0 = x0.toFloat(); val fy0 = y0.toFloat(); val fx1 = x1.toFloat(); val fy1 = y1.toFloat()
        val p = arrayOf(tp(fx0, fy0, m), tp(fx1, fy0, m), tp(fx0, fy1, m), tp(fx1, fy1, m))
        return PRect(p.minOf { it[0] }.toDouble(), p.minOf { it[1] }.toDouble(),
            p.maxOf { it[0] }.toDouble(), p.maxOf { it[1] }.toDouble())
    }

    /** pymupdf Rect & Rect (Rect.intersect: 빈 사각형 처리 + fz_intersect_rect). */
    infix fun and(r: PRect): PRect {
        if (r.isEmpty) return r
        if (isEmpty) return this
        val a0 = x0.toFloat(); val b0 = y0.toFloat(); val a1 = x1.toFloat(); val b1 = y1.toFloat()
        val c0 = r.x0.toFloat(); val d0 = r.y0.toFloat(); val c1 = r.x1.toFloat(); val d1 = r.y1.toFloat()
        return PRect((if (a0 < c0) c0 else a0).toDouble(), (if (b0 < d0) d0 else b0).toDouble(),
            (if (a1 > c1) c1 else a1).toDouble(), (if (b1 > d1) d1 else b1).toDouble())
    }

    /** pymupdf Rect.intersects(파이썬 double 비교). */
    fun intersects(r: PRect): Boolean =
        !isEmpty && !r.isEmpty && x0 < r.x1 && r.x0 < x1 && y0 < r.y1 && r.y0 < y1

    /** 점 포함(fz_is_point_inside_rect, float, 오른쪽·아래 끝은 빼고). */
    fun contains(p: PPoint): Boolean {
        val px = p.x.toFloat(); val py = p.y.toFloat()
        return px >= x0.toFloat() && px < x1.toFloat() && py >= y0.toFloat() && py < y1.toFloat()
    }

    /** 사각형 포함(파이썬 double 비교). */
    fun containsRect(r: PRect): Boolean = x0 <= r.x0 && r.x0 <= r.x1 && r.x1 <= x1 && y0 <= r.y0 && r.y0 <= r.y1 && r.y1 <= y1

    companion object {
        const val FLT_EPS = 1.1920929E-7f
        private fun tp(x: Float, y: Float, m: PMatrix): FloatArray =
            floatArrayOf(x * m.a + y * m.c + m.e, x * m.b + y * m.d + m.f)
    }
}
