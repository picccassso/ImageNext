package com.imagenext.feature.viewer

/**
 * Thumbnail bounds used to animate viewer enter/exit from the tapped grid cell.
 */
data class ViewerLaunchTransition(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    fun isValid(): Boolean =
        leftPx >= 0 &&
            topPx >= 0 &&
            widthPx > 0 &&
            heightPx > 0
}
