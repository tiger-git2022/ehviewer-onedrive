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
    /** Part of the step the items cover, from [partStart] to [partEnd]: a sub-step of [FavoritesIndexSync]. */
    val partStart: Float = 0f,
    val partEnd: Float = 1f,
    /** Fork: part of a category step (downloading, merging…), shown after [title]. */
    @StringRes val phase: Int? = null,
    /** [done] and [total] count bytes, shown as megabytes. */
    val bytes: Boolean = false,
) {
    /** Progress of the whole sync from 0 to 1: the steps done, plus the known part of the current one. */
    val fraction: Float
        get() {
            val items = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            val stepFraction = partStart + (partEnd - partStart) * items
            return ((step - 1 + stepFraction) / steps).coerceIn(0f, 1f)
        }

    val percent: Int
        get() = (fraction * 100).toInt()

    fun withDetail(detail: StepProgress) = copy(
        title = detail.title,
        done = detail.done,
        total = detail.total,
        partStart = detail.part.start,
        partEnd = detail.part.endInclusive,
    )
}

/**
 * Progress inside one step, [done] of [total] items when they are known. [part] is the share of the step this
 * sub-step takes, so the progress of the step only moves forward from one sub-step to the next.
 */
data class StepProgress(
    @StringRes val title: Int,
    val done: Int = 0,
    val total: Int = 0,
    val part: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val fraction: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    val percent: Int?
        get() = fraction?.let { (it * 100).toInt() }

    companion object {
        // Shares of a category step: the cloud file, the local data, merging, the upload and writing back
        val DOWNLOAD = 0f..0.3f
        val READ_LOCAL = 0.3f..0.45f
        val MERGE = 0.45f..0.55f
        val UPLOAD_SECTION = 0.55f..0.8f
        val APPLY = 0.8f..1f

        // Shares of the favorites index step: the newest pages of the cloud favorites, then a few Japanese titles
        val CHECK = 0f..0.5f
        val TITLES = 0.5f..0.9f
        val UPLOAD = 0.9f..1f
    }
}
