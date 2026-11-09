package com.hippo.ehviewer.updater

/**
 * Versions of the fork's releases. Releases are tagged with the fork's own version "vX.Y.Z" (e.g. "v3.18.0"); older
 * ones were "<upstream version>-t<N>" ("v1.15.0-t129", "v1.15.0-t12"). The numbers before "-t" compare first, then N
 * (0 without "-t"), so every new-style release is newer than the old ones (3.18.0 > 1.15.0-t129). See the Build APK
 * workflow and app/build.gradle.kts.
 */
object ForkVersion {
    private val PATTERN = Regex("""^v?(\d+(?:\.\d+)*)(?:-t(\d+))?$""")

    private data class Parsed(val numbers: List<Int>, val build: Int)

    private fun parse(version: String): Parsed? {
        val match = PATTERN.matchEntire(version.trim()) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        val build = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        return Parsed(numbers, build)
    }

    /**
     * Whether release [latest] is newer than the installed [current] version. A build without a release version (a
     * local "-SNAPSHOT" build) is older than any release.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val new = parse(latest) ?: return false
        val old = parse(current) ?: return true
        val length = maxOf(new.numbers.size, old.numbers.size)
        for (i in 0 until length) {
            val a = new.numbers.getOrElse(i) { 0 }
            val b = old.numbers.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return new.build > old.build
    }
}
