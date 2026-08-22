package io.github.bbzq

/**
 * Pure Kotlin helpers for the version tags used by the fork's GitHub releases.
 *
 * Current releases use [version]-[versionCode] (for example, v1.2.0-238),
 * while older releases used [versionCode]-[version]-[versionCode].  Keeping
 * this parser free of Android and JSON dependencies makes the release
 * protocol straightforward to test.
 */
data class ParsedUpdateTag(
    val versionName: String,
    val versionCode: Int,
)

object UpdateVersionParser {
    private val legacyTagPattern = Regex("^(\\d+)-(.+)-(\\d+)$")
    private val forkTagPattern = Regex("^(.+)-(\\d+)$")

    /** Parse a release tag, preserving the `v` prefix in [ParsedUpdateTag.versionName]. */
    fun parseTag(tagName: String): ParsedUpdateTag? {
        val tag = tagName.trim()
        if (tag.isEmpty()) return null

        // Check the legacy form first: otherwise the new-form expression would
        // treat the leading versionCode as part of the version name.
        legacyTagPattern.matchEntire(tag)?.let { match ->
            val leadingCode = match.groupValues[1].toIntOrNull() ?: return@let
            val trailingCode = match.groupValues[3].toIntOrNull() ?: return@let
            if (leadingCode > 0 && leadingCode == trailingCode) {
                return ParsedUpdateTag(
                    versionName = match.groupValues[2],
                    versionCode = leadingCode,
                )
            }
        }

        forkTagPattern.matchEntire(tag)?.let { match ->
            val code = match.groupValues[2].toIntOrNull() ?: 0
            if (code > 0) {
                return ParsedUpdateTag(
                    versionName = match.groupValues[1],
                    versionCode = code,
                )
            }
        }

        // Keep supporting a plain version tag/name as the version-only
        // fallback used by older or manually-created releases.
        return ParsedUpdateTag(versionName = tag, versionCode = 0)
    }

    /** Compare semantic numeric components; remote > local returns a positive value. */
    fun compareVersions(remote: String, local: String): Int {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)
        val partCount = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until partCount) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart.compareTo(localPart)
        }
        return 0
    }

    /**
     * Compare a parsed remote release with the local version.  Valid version
     * codes take precedence; if either side lacks one, compare version names.
     */
    fun isUpdateAvailable(
        remote: ParsedUpdateTag,
        localVersion: String,
        localVersionCode: Int,
    ): Boolean {
        if (remote.versionCode > 0 && localVersionCode > 0) {
            return remote.versionCode > localVersionCode
        }
        return compareVersions(remote.versionName, localVersion) > 0
    }

    /** Remove an optional `v`/`V` prefix and surrounding whitespace. */
    fun normalizeVersion(version: String): String =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .trim()

    private fun parseVersionParts(version: String): List<Int> =
        normalizeVersion(version)
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
