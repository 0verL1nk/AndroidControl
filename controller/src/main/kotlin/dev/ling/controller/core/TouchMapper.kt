package dev.ling.controller.core

/**
 * 把控件上的触点换算成长辈端的视频帧坐标。
 *
 * 画面是等比缩放的（letterbox），所以不能简单地按控件宽高做比例——
 * 必须先把黑边去掉，否则点哪儿偏哪儿（而且偏得越大越明显）。
 */
object TouchMapper {

    /** 画面在控件内实际占据的矩形。 */
    data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

    fun displayRect(
        viewWidth: Float,
        viewHeight: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): Rect? {
        if (viewWidth <= 0f || viewHeight <= 0f || frameWidth <= 0 || frameHeight <= 0) return null
        val videoRatio = frameWidth.toFloat() / frameHeight
        val viewRatio = viewWidth / viewHeight
        return if (videoRatio > viewRatio) {
            // 画面更宽：以控件宽度为准，上下留黑边
            val h = viewWidth / videoRatio
            Rect(0f, (viewHeight - h) / 2f, viewWidth, h)
        } else {
            // 画面更窄：以控件高度为准，左右留黑边
            val w = viewHeight * videoRatio
            Rect((viewWidth - w) / 2f, 0f, w, viewHeight)
        }
    }

    /**
     * 返回视频帧坐标；触点落在黑边上时返回 null（对应"点到黑边不转发"）。
     */
    fun toFrame(
        viewX: Float,
        viewY: Float,
        viewWidth: Float,
        viewHeight: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): Pair<Int, Int>? {
        val rect = displayRect(viewWidth, viewHeight, frameWidth, frameHeight) ?: return null
        if (viewX < rect.left || viewX > rect.left + rect.width) return null
        if (viewY < rect.top || viewY > rect.top + rect.height) return null

        val fx = ((viewX - rect.left) / rect.width * frameWidth).toInt()
        val fy = ((viewY - rect.top) / rect.height * frameHeight).toInt()
        return fx.coerceIn(0, frameWidth - 1) to fy.coerceIn(0, frameHeight - 1)
    }
}
