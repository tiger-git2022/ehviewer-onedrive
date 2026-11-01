package com.hippo.ehviewer.util

// Fork: ads sit at the end of the gallery. A short gallery checks its last 3 pages, a longer one its last 7
// (upstream checked the last 9 of every gallery)
fun adPageCount(size: Int) = if (size <= 10) 3 else 7

fun isAdPageCandidate(index: Int, size: Int) = index >= size - adPageCount(size)
