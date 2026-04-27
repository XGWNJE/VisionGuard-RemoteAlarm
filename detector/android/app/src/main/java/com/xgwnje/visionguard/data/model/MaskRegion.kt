package com.xgwnje.visionguard.data.model

/**
 * 遮罩区域，使用相对坐标（0.0 ~ 1.0），适配不同分辨率。
 *
 * @param left   左边界相对画面宽度的比例
 * @param top    上边界相对画面高度的比例
 * @param right  右边界相对画面宽度的比例
 * @param bottom 下边界相对画面高度的比例
 */
data class MaskRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left in 0.0f..1.0f) { "left must be in [0, 1]" }
        require(top in 0.0f..1.0f) { "top must be in [0, 1]" }
        require(right in 0.0f..1.0f) { "right must be in [0, 1]" }
        require(bottom in 0.0f..1.0f) { "bottom must be in [0, 1]" }
        require(left < right) { "left must be < right" }
        require(top < bottom) { "top must be < bottom" }
    }
}
