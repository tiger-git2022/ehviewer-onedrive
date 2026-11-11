package com.hippo.ehviewer.myuploads

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Fork: the rules of 「我的上传」 that need nothing of Android, see [MyUploads]. */
object MyUploadsRules {
    private val galleryLink = Regex("""e[-x]hentai\.org/g/(\d+)/([0-9a-f]{10})""")
    private val rowRegex = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val postedFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * The galleries linked from the gallery manager of upload.e-hentai.org, in its order (newest first), each once;
     * null when the page is not the manager (not signed in).
     */
    fun parseManagePage(html: String): List<Pair<Long, String>>? {
        if ("managegallery" !in html) return null
        val seen = HashSet<Long>()
        return rowRegex.findAll(html).mapNotNull { row ->
            galleryLink.find(row.groupValues[1])?.let { it.groupValues[1].toLong() to it.groupValues[2] }
        }.filter { seen.add(it.first) }.toList()
    }

    /**
     * Whether the page of [upload] is to be read again, like EH 上传助手: hourly while it is new or commented in the last
     * 3 days, every 6 hours while commented in the last 30 days, else daily. 5 minutes early is fine, so the hourly work
     * in the background gets the hourly ones each time.
     */
    fun isDue(upload: MyUpload, now: Long): Boolean {
        if (upload.fetched == 0L) return true
        val since = now - upload.fetched
        val lastComment = upload.comments.maxOfOrNull { it.time } ?: 0
        val posted = postedMillis(upload.info.posted)
        val interval = when {
            now - posted < 3 * DAY || now - lastComment < 3 * DAY -> HOUR
            now - lastComment < 30 * DAY -> 6 * HOUR
            else -> DAY
        }
        return since > interval - 5 * MINUTE
    }

    /** The posted time of a gallery ("2026-11-08 12:34", UTC), ms; 0 when unknown. */
    fun postedMillis(posted: String?): Long = posted?.let {
        runCatching { LocalDateTime.parse(it.trim(), postedFormat).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    } ?: 0

    /** The comments read on two devices: the newest of each gallery. */
    fun mergeSeen(a: Map<Long, Long>, b: Map<Long, Long>): Map<Long, Long> = (a.keys + b.keys).associateWith { maxOf(a[it] ?: Long.MIN_VALUE, b[it] ?: Long.MIN_VALUE) }
}
