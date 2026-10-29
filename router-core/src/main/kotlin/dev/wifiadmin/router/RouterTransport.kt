package dev.wifiadmin.router

/**
 * Posts one JSON request to the router CGI and returns the raw reply body.
 *
 * Implementations must not log request bodies: they carry the password hash,
 * the challenge token and the session id.
 */
fun interface RouterTransport {
    /**
     * @param url the full endpoint URL.
     * @param body the JSON request body.
     * @param timeoutSeconds the per-request timeout.
     * @return the raw reply body.
     * @throws java.io.IOException when the request could not be delivered.
     */
    fun post(url: String, body: String, timeoutSeconds: Long): String
}
