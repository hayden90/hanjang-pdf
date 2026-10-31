package com.bubigter.hanjangpdf.core

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * PC판 annots._text_width와 같은 글자 폭(1pt 기준 advance). tools/gen_android_widths.py가 만든
 * assets/hs_widths.txt(.gz)를 읽는다. 0x1100 미만은 helv, 이상은 cjk 글꼴(표에 없으면 1.0).
 */
class Widths private constructor(private val low: DoubleArray, private val starts: IntArray,
                                 private val ends: IntArray, private val vals: DoubleArray) {
    fun advance(cp: Int): Double {
        if (cp < 0x1100) return low[cp]
        var lo = 0; var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < starts[mid] -> hi = mid - 1
                cp > ends[mid] -> lo = mid + 1
                else -> return vals[mid]
            }
        }
        return 1.0
    }

    /** _text_width(line, size): 글자(코드포인트)마다 advance×크기를 double로 더한다(파이썬과 같은 순서). */
    fun textWidth(line: String, size: Int): Double {
        var w = 0.0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            w += advance(cp) * size
            i += Character.charCount(cp)
        }
        return w
    }

    companion object {
        /** 저장소에는 hs_widths.txt.gz로 두지만 AGP가 APK에 넣을 때 풀어서 이 이름으로 넣는다. */
        const val ASSET = "hs_widths.txt"

        fun load(input: InputStream): Widths {
            val low = DoubleArray(0x1100) { Double.NaN }
            val s = ArrayList<Int>(); val e = ArrayList<Int>(); val v = ArrayList<Double>()
            val buf = java.io.BufferedInputStream(input)
            buf.mark(2)
            val gz = buf.read() == 0x1f && buf.read() == 0x8b
            buf.reset()
            (if (gz) GZIPInputStream(buf) else buf).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (ln in lines) {
                    if (ln.isEmpty() || ln.startsWith("#")) continue
                    val f = ln.split(' ')
                    val a = f[1].toInt(16); val b = f[2].toInt(16); val adv = f[3].toDouble()
                    when (f[0]) {
                        "L" -> for (cp in a..b) low[cp] = adv
                        "C" -> { s.add(a); e.add(b); v.add(adv) }
                        else -> error("폭 표 형식이 틀림: $ln")
                    }
                }
            }
            check(low.none { it.isNaN() }) { "폭 표에 0x1100 미만 글자가 빠짐" }
            return Widths(low, s.toIntArray(), e.toIntArray(), v.toDoubleArray())
        }
    }
}
