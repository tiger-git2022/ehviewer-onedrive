package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.client.EhUtils

/**
 * Fork: the category a long press searched alone, see SearchFilter. The list doesn't take it for the categories
 * picked: they stay as they are, and the next plain search takes them all again. [EhUtils.NONE] when there is none.
 * Kept out of the screen, as a search from the toplists opens a new list page.
 */
@Volatile
var soloSearchCategory = EhUtils.NONE
