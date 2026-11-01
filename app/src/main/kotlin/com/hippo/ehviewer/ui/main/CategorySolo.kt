package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.client.EhUtils

/**
 * Fork: the categories of the search, with the ones picked before a long press. A long press on a category keeps only
 * it, the others wait in [before]; a long press on it again brings them back. A plain tap forgets them.
 * [before] is [EhUtils.NONE] when nothing waits.
 */
data class CategoryPick(val category: Int, val before: Int = EhUtils.NONE) {
    val waiting get() = before != EhUtils.NONE

    fun tap(bit: Int) = CategoryPick(category xor bit)

    fun longPress(bit: Int) = when {
        // Only this one, after a long press: back to the ones before
        category == bit && waiting -> CategoryPick(before)
        // Already the only one, nothing to keep
        category == bit -> this
        // Another one alone: the ones from the first long press still wait
        else -> CategoryPick(bit, if (waiting) before else category)
    }
}
