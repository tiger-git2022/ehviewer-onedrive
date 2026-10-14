package com.hippo.ehviewer.updater

/**
 * Versions of the fork's releases: "<upstream version>-t<N>" (tag "v1.15.0-t3"), see the Build APK workflow and
 * app/build.gradle.kts. A higher upstream version is newer, then a higher N.
 */
object ForkVersion {
    private val PATTERN = Regex("""^v?(\d+(?:\.\d+)*)-t(\d+)$""")

    private data class Parsed(val upstream: List<Int>, val build: Int)

    private fun parse(version: String): Parsed? {
        val match = PATTERN.matchEntire(version.trim()) ?: return null
        val upstream = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        val build = match.groupValues[2].toIntOrNull() ?: return null
        return Parsed(upstream, build)
    }

    /**
     * Whether release [latest] is newer than the installed [current] version. A build without a fork version (a
     * local or older build) is older than any release.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val new = parse(latest) ?: return false
        val old = parse(current) ?: return true
        val length = maxOf(new.upstream.size, old.upstream.size)
        for (i in 0 until length) {
            val a = new.upstream.getOrElse(i) { 0 }
            val b = old.upstream.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return new.build > old.build
    }
}
