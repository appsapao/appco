package com.yundac.apkcloner.core.clone

/** Validation and derivation of clone package names (an Android application ID). */
object PackageNames {

    const val MAX_LENGTH: Int = 120

    private val SEGMENT = Regex("[A-Za-z][A-Za-z0-9_]{0,62}")

    fun isValid(packageName: String): Boolean {
        if (packageName.isEmpty() || packageName.length > MAX_LENGTH) return false
        val segments = packageName.split('.')
        if (segments.size < 2) return false
        return segments.all { SEGMENT.matches(it) }
    }

    /** Human readable reason why [packageName] is rejected, or null when it is valid. */
    fun problem(packageName: String): String? = when {
        packageName.isEmpty() -> "Package name is empty"
        packageName.length > MAX_LENGTH -> "Package name is longer than $MAX_LENGTH characters"
        packageName.contains('/') -> "Package name must not contain '/'"
        packageName.split('.').size < 2 -> "Package name needs at least two segments (e.g. com.example.app)"
        packageName.split('.').any { it.isEmpty() } -> "Package name has an empty segment"
        packageName.split('.').any { !SEGMENT.matches(it) } ->
            "Each segment must start with a letter and contain only letters, digits and '_'"
        else -> null
    }

    /**
     * Suggests the package name for clone number [index] of [sourcePackage].
     *
     * The suffix keeps the original package as a prefix, which makes clones easy
     * to recognise and never collides with the source application ID.
     */
    fun suggest(sourcePackage: String, index: Int): String {
        require(index >= 1) { "Clone index starts at 1" }
        val suffix = ".clone$index"
        if (sourcePackage.length + suffix.length <= MAX_LENGTH) return sourcePackage + suffix

        // Keep whole segments, from the front, while they still fit. At least one
        // segment is always kept, which leaves "com.clone1" -- still a valid ID.
        val segments = sourcePackage.split('.')
        val kept = mutableListOf(segments.first())
        for (segment in segments.drop(1)) {
            val candidate = (kept + segment).joinToString(".")
            if (candidate.length + suffix.length > MAX_LENGTH) break
            kept.add(segment)
        }
        return kept.joinToString(".") + suffix
    }

    /** Suggests the first free `.<suffix>N` name, given the package names already in use. */
    fun suggestFree(sourcePackage: String, taken: Set<String>, startIndex: Int = 1): String {
        var index = startIndex
        while (index < startIndex + 1000) {
            val candidate = suggest(sourcePackage, index)
            if (candidate !in taken) return candidate
            index++
        }
        throw IllegalStateException("Could not find a free clone package name for $sourcePackage")
    }
}
