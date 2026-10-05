package com.maghizhan.tabby.data.remote

/**
 * Parses and validates an OAuth callback URL before anything is exchanged.
 *
 * Kept as pure string logic (no Android `Uri`) so every rule below is unit
 * testable on the JVM, and so the validation cannot be skipped by a caller that
 * happens to have a raw string.
 *
 * Validation matters because the callback arrives via an exported intent filter:
 * any app on the device can send us one. An unvalidated handler would hand
 * attacker-chosen codes to the token endpoint, so the scheme and host are
 * checked against our own redirect before a code is ever read.
 */
object OAuthCallback {

    /** Must match the `android:scheme` in the manifest's callback intent filter. */
    const val SCHEME = "com.maghizhan.tabby"

    /** Must match the `android:host` in that same filter. */
    const val HOST = "auth-callback"

    /** The redirect URL registered with Supabase. */
    const val REDIRECT_URL = "$SCHEME://$HOST"

    sealed interface Result {
        /** A valid callback carrying a PKCE authorization code. */
        data class Code(val value: String) : Result

        /** The provider reported a failure (e.g. the user denied consent). */
        data class ProviderError(val code: String, val description: String?) : Result

        /** Not one of our callbacks at all; the caller should ignore it. */
        data object NotACallback : Result

        /** Our callback, but unusable — malformed or missing a code. */
        data class Invalid(val reason: String) : Result
    }

    /**
     * Classifies [url].
     *
     * Both the query string and the fragment are inspected: PKCE returns the
     * code as a query parameter, but error responses and implicit-flow
     * redirects can arrive in the fragment instead, and silently ignoring the
     * fragment would turn a reported provider error into a mysterious hang.
     */
    fun parse(url: String?): Result {
        if (url.isNullOrBlank()) return Result.NotACallback

        val prefix = "$SCHEME://$HOST"
        if (!url.startsWith(prefix, ignoreCase = true)) return Result.NotACallback

        var remainder = url.substring(prefix.length)

        // Exact authority: no port, and no look-alike host such as
        // `auth-callback.evil.test`. Only a path/query/fragment boundary may
        // follow the host.
        if (remainder.isNotEmpty() && remainder.first() !in listOf('/', '?', '#')) {
            return Result.NotACallback
        }

        // Exact path. The registered redirect has NO path, so the only
        // acceptable forms are the bare host or a single trailing slash.
        // Accepting arbitrary paths widened the identity we answer to beyond
        // what is actually registered, which means a URL we never published
        // would still be honoured.
        if (remainder.startsWith("/")) {
            val pathEnd = remainder.indexOfFirst { it == '?' || it == '#' }
                .let { if (it == -1) remainder.length else it }
            val path = remainder.substring(0, pathEnd)
            if (path != "/") return Result.NotACallback
            remainder = remainder.substring(pathEnd)
        }

        val parameters = parseParameters(remainder)

        parameters["error"]?.let { error ->
            return Result.ProviderError(
                code = error,
                description = parameters["error_description"]
            )
        }

        val code = parameters["code"]
        return when {
            code.isNullOrBlank() -> Result.Invalid("Callback URL contained no authorization code.")
            else -> Result.Code(code)
        }
    }

    /** Collects key/value pairs from both the query and the fragment. */
    private fun parseParameters(remainder: String): Map<String, String> {
        val segments = remainder
            .substringAfter('?', remainder.substringAfter('#', ""))
            .let { query ->
                // When both are present, read the query AND the fragment.
                val fragment = remainder.substringAfter('#', "")
                if (fragment.isNotEmpty() && !query.contains(fragment)) "$query&$fragment" else query
            }

        return segments.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (key.isBlank()) null else key to decode(value)
            }
            .toMap()
    }

    /** Minimal percent-decoding; the values we read are codes and messages. */
    private fun decode(value: String): String =
        value.replace("+", " ").replace(Regex("%([0-9A-Fa-f]{2})")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
}
