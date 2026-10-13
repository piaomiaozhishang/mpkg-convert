package com.doubao.repkg

/**
 * Shared downsampling math for decoding large images without blowing up memory.
 * Kept dependency-free so both the UI loader and the history store can use it.
 */
object BitmapSampling {

    /**
     * Power-of-two `inSampleSize` such that after the (hardware) subsampling both
     * edges are at or below roughly [maxEdge]. Returns 1 when unknown/too small.
     */
    fun inSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longer = maxOf(width, height)
        while (longer / (sample * 2) > maxEdge) sample *= 2
        return sample
    }
}
