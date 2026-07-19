package com.workoutmaker.app.util

// Two very different things throw on the way back from an edge function:
//
//   * the server, which sends a JSON body ({"error": "..."}) whose text is
//     already written for a person (quota messages, "no key configured", ...);
//   * the transport, which throws raw Ktor/OkHttp prose like
//     "Socket timeout has expired [url=..., socket_timeout=unknown] ms".
//
// The first should be shown as-is; the second must never reach a banner. Sibling
// of friendlyAuthError() in ui/AuthGate.kt, which does the same job for GoTrue.
// Callers still log the raw throwable via AppLog — this is only what the user reads.

private val JSON_ERROR = Regex("\"(?:error|detail|message)\"\\s*:\\s*\"([^\"]+)\"")

/** The server's own error text, when the failure carried a JSON body. */
fun serverErrorText(t: Throwable): String? =
    t.message?.let { JSON_ERROR.find(it)?.groupValues?.get(1) }

/**
 * A message safe to put in front of a user for any edge-function failure.
 * [fallback] is used when the cause isn't one we recognise, so each call site
 * can say what it was trying to do.
 */
fun friendlyFnError(t: Throwable, fallback: String = "Something went wrong. Try again."): String {
    serverErrorText(t)?.let { return it }
    val m = t.message ?: return fallback
    return when {
        m.contains("timeout", true) || m.contains("timed out", true) ->
            "That took too long to come back. Try again."
        m.contains("Unable to resolve host", true) || m.contains("Failed to connect", true) ||
            m.contains("No address associated", true) || m.contains("Network is unreachable", true) ->
            "Can't reach the server. Check your internet connection."
        else -> fallback
    }
}
