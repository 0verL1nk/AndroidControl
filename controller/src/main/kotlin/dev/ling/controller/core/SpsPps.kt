package dev.ling.controller.core

/**
 * H.264 配置包里的 SPS / PPS。
 *
 * MediaFormat 要求 `csd-0` / `csd-1` 各自带上 Annex-B 起始码。
 * 联发科等硬解码器只认这条路径：把配置包当普通帧喂进去会报
 * "CSD is not calculated" 并永远不出帧（真机实测）。
 */
class SpsPps private constructor(
    val sps: ByteArray,
    val pps: ByteArray,
) {

    val isComplete: Boolean get() = sps.isNotEmpty() && pps.isNotEmpty()

    companion object {
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private val EMPTY = ByteArray(0)

        /** 从 Annex-B 字节流里切出 SPS 与 PPS；找不到时对应项为空数组。 */
        fun parse(annexB: ByteArray): SpsPps {
            val starts = startCodeOffsets(annexB)
            if (starts.isEmpty()) return SpsPps(EMPTY, EMPTY)

            var sps = EMPTY
            var pps = EMPTY
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: annexB.size
                val nal = annexB.copyOfRange(start, end)
                val header = startCodeLength(nal)
                if (nal.size > header) {
                    when (nal[header].toInt() and 0x1F) {
                        NAL_SPS -> sps = nal
                        NAL_PPS -> pps = nal
                    }
                }
            }
            return SpsPps(sps, pps)
        }

        private fun startCodeOffsets(data: ByteArray): List<Int> {
            val offsets = mutableListOf<Int>()
            var i = 0
            while (i + 3 < data.size) {
                when {
                    isFourByteStartCode(data, i) -> {
                        offsets += i
                        i += 4
                    }
                    isThreeByteStartCode(data, i) -> {
                        offsets += i
                        i += 3
                    }
                    else -> i++
                }
            }
            return offsets
        }

        private fun isFourByteStartCode(data: ByteArray, i: Int): Boolean =
            data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1

        private fun isThreeByteStartCode(data: ByteArray, i: Int): Boolean =
            data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1

        private fun startCodeLength(nal: ByteArray): Int =
            if (nal.size > 3 && nal[2].toInt() == 1) 3 else 4
    }
}
