package de.grad.keycloak.steam

import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP client for the Steam Web API.
 *
 * Uses only the JDK 11+ [HttpClient] so there are no additional runtime
 * dependencies beyond Kotlin stdlib and the JDK itself.
 */
class SteamApiClient(private val apiKey: String) {

    private val log: Logger = Logger.getLogger(SteamApiClient::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * Fetches the player summary for [steamId] and returns a [SteamPlayerSummary],
     * or `null` if the lookup fails or the profile is private / not found.
     */
    fun getPlayerSummary(steamId: String): SteamPlayerSummary? {
        val url = buildString {
            append(SteamIdentityProviderConfig.STEAM_GET_PLAYER_SUMMARIES)
            append("?key=").append(apiKey)
            append("&steamids=").append(steamId)
        }

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                log.warnf("Steam API returned HTTP %d for steamId=%s", response.statusCode(), steamId)
                return null
            }

            parsePlayerSummary(response.body(), steamId)
        } catch (ex: Exception) {
            log.errorf(ex, "Error calling Steam Web API for steamId=%s", steamId)
            null
        }
    }

    // ── Minimal hand-rolled JSON extraction (no extra deps) ──────────────────

    /**
     * Extracts the first player object from a raw GetPlayerSummaries response.
     *
     * The response shape is:
     * ```json
     * { "response": { "players": [ { "steamid": "...", "personaname": "...", ... } ] } }
     * ```
     *
     * We avoid pulling in a JSON library so the shaded JAR stays small.
     */
    private fun parsePlayerSummary(json: String, steamId: String): SteamPlayerSummary? {
        // Fast-fail if no players array content
        val playersStart = json.indexOf("\"players\"")
        if (playersStart == -1) {
            log.warnf("No 'players' key in Steam API response for steamId=%s", steamId)
            return null
        }

        val arrayStart = json.indexOf('[', playersStart)
        val arrayEnd   = json.indexOf(']', arrayStart)
        if (arrayStart == -1 || arrayEnd == -1) return null

        val playersContent = json.substring(arrayStart + 1, arrayEnd).trim()
        if (playersContent.isEmpty() || playersContent == "{}") {
            log.warnf("Empty players array in Steam API response for steamId=%s", steamId)
            return null
        }

        return SteamPlayerSummary(
            steamId     = extractJsonString(json, "steamid")     ?: steamId,
            personaName = extractJsonString(json, "personaname") ?: "Unknown",
            avatarFull  = extractJsonString(json, "avatarfull")  ?: "",
            profileUrl  = extractJsonString(json, "profileurl")  ?: "",
            realName    = extractJsonString(json, "realname"),
            countryCode = extractJsonString(json, "loccountrycode"),
        )
    }

    /**
     * Extracts the string value of [key] from a flat JSON object fragment.
     * Handles basic escaped characters but is intentionally simple — the
     * Steam API returns well-formed ASCII/UTF-8 JSON.
     */
    private fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        val keyIdx = json.indexOf(needle)
        if (keyIdx == -1) return null

        val colonIdx = json.indexOf(':', keyIdx + needle.length)
        if (colonIdx == -1) return null

        // Find opening quote
        val openQuote = json.indexOf('"', colonIdx + 1)
        if (openQuote == -1) return null

        // Find closing quote (skip escaped quotes)
        var i = openQuote + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    '"'  -> { sb.append('"');  i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    'n'  -> { sb.append('\n'); i += 2; continue }
                    'r'  -> { sb.append('\r'); i += 2; continue }
                    't'  -> { sb.append('\t'); i += 2; continue }
                    else -> { sb.append(c); i++; continue }
                }
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/**
 * Subset of fields returned by `ISteamUser/GetPlayerSummaries`.
 */
data class SteamPlayerSummary(
    val steamId: String,
    val personaName: String,
    val avatarFull: String,
    val profileUrl: String,
    val realName: String?,
    val countryCode: String?,
)
