package com.hippo.ehviewer.sync

import androidx.annotation.StringRes

/**
 * Progress of a running OneDrive sync for the UI: step [step] of [steps] named [title], with [done] of [total] items
 * of that step when they are known.
 */
data class SyncRunProgress(
    @StringRes val title: Int,
    val step: Int,
    val steps: Int,
    val done: Int = 0,
    val total: Int = 0,
    /** The step of [FavoritesIndexSync], which reports its own [StepProgress]. */
    val favoritesIndex: Boolean = false,
) {
    /** Progress of the whole sync from 0 to 1: the steps done, plus the known part of the current one. */
    val fraction: Float
        get() {
            val stepFraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            return ((step - 1 + stepFraction) / steps).coerceIn(0f, 1f)
        }

    val percent: Int
        get() = (fraction * 100).toInt()

    fun withDetail(detail: StepProgress) = copy(title = detail.title, done = detail.done, total = detail.total)
}

/** Progress inside one step, [done] of [total] items when they are known. */
data class StepProgress(@StringRes val title: Int, val done: Int = 0, val total: Int = 0) {
    val fraction: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    val percent: Int?
        get() = fraction?.let { (it * 100).toInt() }
}
