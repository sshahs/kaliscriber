package com.kaliscriber.core.export

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Answers HTTP Basic and Digest (RFC 7616, MD5, qop=auth) challenges. Baikal's CalDAV endpoint uses
 * Digest by default; many other servers use Basic.
 */
class BasicOrDigestAuthenticator(private val username: String, private val password: String) : Authenticator {
    private var nonceCount = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 3) return null
        val challenges = response.headers("WWW-Authenticate")
        val digest = challenges.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
        val header = if (digest != null) {
            digestHeader(digest, response.request.method, response.request.url.encodedPath +
                (response.request.url.encodedQuery?.let { "?$it" } ?: ""))
        } else {
            Credentials.basic(username, password, Charsets.UTF_8)
        }
        if (response.request.header("Authorization") == header) return null
        return response.request.newBuilder().header("Authorization", header).build()
    }

    private fun digestHeader(challenge: String, method: String, uri: String): String {
        val params = parseParams(challenge.trim().substringAfter(' '))
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val algorithm = params["algorithm"] ?: "MD5"
        val opaque = params["opaque"]
        val cnonce = randomHex(8)
        val nc = String.format(Locale.ROOT, "%08x", ++nonceCount)
        var ha1 = md5("$username:$realm:$password")
        if (algorithm.equals("MD5-sess", ignoreCase = true)) ha1 = md5("$ha1:$nonce:$cnonce")
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            append("algorithm=$algorithm, response=\"$response\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        internal fun parseParams(text: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val re = Regex("""(\w+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))""")
            for (m in re.findAll(text)) {
                result[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            return result
        }

        internal fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun randomHex(bytes: Int): String =
            ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
