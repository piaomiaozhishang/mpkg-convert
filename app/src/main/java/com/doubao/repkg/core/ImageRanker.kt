package com.doubao.repkg.core

/**
 * Ranks static picture candidates to find the real wallpaper art rather than
 * preview thumbnails, UI overlays, masks or particle strips.
 *
 * Ported from the heuristic used by the community dsh-mpkg-wallpaper plugin:
 *   score = pixelArea * formatFactor * pathPenalty
 * followed by an aspect-ratio / grayscale quality gate for the winner.
 */
object ImageRanker {

    /** Auxiliary/effect texture path keywords (heavily penalised). */
    private val PATH_PENALTY = Regex(
        """(^|[\\/])(masks?|effects?)([\\/]|$)|[\\/]workshop[\\/]|_mask|mask_|normal|depth|""" +
                """ripple|foliagesway|cloudmotion|shake|pulse|xray|opacity|lens|cursor|flow|grad|""" +
                """noise|particle|vignette|blur|sync|_anim|frame|seq|preview|thumb""",
        RegexOption.IGNORE_CASE
    )

    /** UI overlay keywords — these are never the wallpaper art. */
    private val UI_PENALTY = Regex(
        """提示框|赞助|水印|按钮|图标|边框|弹窗|文字|角标|鼠标|指针|网盘|箭头|""" +
                """prompt|text_|button|icon|logo|dialog|input|chat|badge|bubble|tooltip|border|""" +
                """sponsor|watermark|topbar|titlebar|headbar|notify|hud|overlay|panel|label|""" +
                """cursor|pointer|drive|arrow|hand""",
        RegexOption.IGNORE_CASE
    )

    data class Candidate(
        val path: String,
        val width: Int,
        val height: Int,
        /** TEX header format (only meaningful when [imageFormat] == -1). */
        val texFormat: Int,
        /** Container FreeImageFormat, -1 when unknown. */
        val imageFormat: Int
    )

    data class Ranked(val candidate: Candidate, val score: Double)

    /** Format -> art-likelihood multiplier. */
    private fun formatFactor(c: Candidate): Double {
        if (c.imageFormat >= 0) {
            return when (c.imageFormat) {
                2 /* JPEG */, 13 /* PNG */ -> 1.0
                else -> 0.5
            }
        }
        return when (c.texFormat) {
            0 /* RGBA8888 */ -> 1.0
            4, 6, 7 /* DXT5/3/1 */ -> 0.5
            8, 9 /* RG88, R8 */ -> 0.01
            else -> 0.2
        }
    }

    private fun pathPenalty(path: String): Double {
        var p = if (PATH_PENALTY.containsMatchIn(path)) 0.02 else 1.0
        if (UI_PENALTY.containsMatchIn(path)) p *= 0.02
        return p
    }

    /** True when the path looks like real art (no helper/preview/UI keywords). */
    fun isArtPath(path: String): Boolean =
        !PATH_PENALTY.containsMatchIn(path) && !UI_PENALTY.containsMatchIn(path)

    fun rank(candidates: List<Candidate>): List<Ranked> {
        return candidates.map { c ->
            val area = c.width.toDouble() * c.height.toDouble()
            val score = area * formatFactor(c) * pathPenalty(c.path)
            Ranked(c, score)
        }.sortedByDescending { it.score }
    }

    // --- Quality gate on decoded RGBA pixels ---

    /**
     * Samples decoded pixels and rejects grayscale masks (>88% gray) and flat
     * solid fills (near-zero variance).
     */
    fun frameAcceptable(width: Int, height: Int, rgba: ByteArray): Boolean {
        val total = width * height
        if (total == 0) return false

        // Deterministic stride sampling (stable, no RNG needed).
        val n = minOf(2000, total)
        val step = (total / n).coerceAtLeast(1)
        var gray = 0
        var sr = 0L; var sg = 0L; var sb = 0L
        val sampled = ArrayList<Int>(n)
        var k = 0
        var idx = 0
        while (k < n && idx < total) {
            sampled.add(idx)
            val o = idx * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            sr += r; sg += g; sb += b
            if (maxOf(r, g, b) - minOf(r, g, b) <= 24) gray++
            k++
            idx += step
        }
        val actual = sampled.size
        val mr = sr / actual; val mg = sg / actual; val mb = sb / actual
        var v = 0.0
        for (i in sampled) {
            val o = i * 4
            v += Math.abs((rgba[o].toInt() and 0xFF) - mr) +
                    Math.abs((rgba[o + 1].toInt() and 0xFF) - mg) +
                    Math.abs((rgba[o + 2].toInt() and 0xFF) - mb)
        }
        val meanVar = v / (actual * 3)
        val grayRatio = gray.toDouble() / actual

        if (grayRatio > 0.88) return false
        if (meanVar < 3) return false
        return true
    }

    /** Aspect-ratio gate: rejects atlas/particle strips. */
    fun aspectAcceptable(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toDouble() / height
        if (ratio > 3.0 || ratio < 0.45) return false
        return true
    }
}
