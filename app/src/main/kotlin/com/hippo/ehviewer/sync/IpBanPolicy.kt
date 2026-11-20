package com.hippo.ehviewer.sync

/**
 * Fork: parses E-Hentai IP ban durations (e.g. "The ban expires in 1 hours and 23 minutes") and decides how long
 * [CloudFavoritesWork] should pause before resuming from its saved checkpoint.
 */
object IpBanPolicy {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** Extra buffer past E-Hentai's stated expiry so the next request does not hit the tail of the ban. */
    private const val SAFETY_BUFFER_MS = 60_000L
    private const val MIN_COOLDOWN_MS = 5 * MINUTE_MS
    private const val MAX_COOLDOWN_MS = 6 * HOUR_MS
    private const val DEFAULT_COOLDOWN_MS = 15 * MINUTE_MS
    private const val MAX_DEFAULT_COOLDOWN_MS = 2 * HOUR_MS

    private val EXPIRES_PART = Regex("""expires\s+in\s+(.+)""", RegexOption.IGNORE_CASE)
    private val UNIT_REGEX = Regex("""(\d+)\s*(days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)\b""", RegexOption.IGNORE_CASE)

    /** Duration in ms stated in an E-Hentai IP ban message, or null if none is stated. */
    fun parseBanDurationMillis(message: String?): Long? {
        if (message.isNullOrBlank()) return null
        val tail = EXPIRES_PART.find(message)?.groupValues?.get(1) ?: return null
        var total = 0L
        var matched = false
        for (m in UNIT_REGEX.findAll(tail)) {
            val value = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2].lowercase()
            total += when {
                unit.startsWith("day") -> value * DAY_MS
                unit.startsWith("h") -> value * HOUR_MS
                unit.startsWith("m") -> value * MINUTE_MS
                unit.startsWith("s") -> value * 1_000L
                else -> 0L
            }
            matched = true
        }
        return if (matched && total > 0L) total else null
    }

    /** Cooldown duration in ms before auto-resuming after an IP ban; [consecutiveBans] starts from 1. */
    fun cooldownMillis(message: String?, consecutiveBans: Int): Long {
        val parsed = parseBanDurationMillis(message)
        if (parsed != null) {
            return (parsed + SAFETY_BUFFER_MS).coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
        }
        val shift = (consecutiveBans - 1).coerceIn(0, 3)
        return (DEFAULT_COOLDOWN_MS shl shift).coerceAtMost(MAX_DEFAULT_COOLDOWN_MS)
    }
}
