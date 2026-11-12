package de.grad.keycloak.steam

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SteamApiClientTest {

    private val steam = FakeHttpServer()
    private val client = SteamApiClient("secret-key", steam.url)

    @AfterEach
    fun tearDown() = steam.close()

    private val fullResponse = """
        {
          "response": {
            "players": [
              {
                "steamid": "76561197960435530",
                "communityvisibilitystate": 3,
                "profilestate": 1,
                "personaname": "Robin",
                "profileurl": "https://steamcommunity.com/id/robinwalker/",
                "avatar": "https://avatars.steamstatic.com/abc.jpg",
                "avatarfull": "https://avatars.steamstatic.com/abc_full.jpg",
                "personastate": 0,
                "realname": "Robin Walker",
                "loccountrycode": "US"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses a full player summary`() {
        steam.body = fullResponse

        val summary = client.getPlayerSummary("76561197960435530")

        assertEquals(
            SteamPlayerSummary(
                steamId = "76561197960435530",
                personaName = "Robin",
                avatarFull = "https://avatars.steamstatic.com/abc_full.jpg",
                profileUrl = "https://steamcommunity.com/id/robinwalker/",
                realName = "Robin Walker",
                countryCode = "US",
            ),
            summary,
        )
    }

    @Test
    fun `sends api key and steam id as query parameters`() {
        steam.body = fullResponse

        client.getPlayerSummary("76561197960435530")

        val req = steam.requests.single()
        assertEquals("GET", req.method)
        assertEquals("secret-key", req.query["key"])
        assertEquals("76561197960435530", req.query["steamids"])
    }

    @Test
    fun `optional fields missing from a private profile fall back to defaults`() {
        steam.body = """{"response":{"players":[{"steamid":"76561197960435530","personaname":"Robin"}]}}"""

        val summary = client.getPlayerSummary("76561197960435530")!!

        assertEquals("Robin", summary.personaName)
        assertEquals("", summary.avatarFull)
        assertEquals("", summary.profileUrl)
        assertNull(summary.realName)
        assertNull(summary.countryCode)
    }

    @Test
    fun `unescapes quotes and backslashes in persona names`() {
        steam.body = """{"response":{"players":[{"steamid":"1","personaname":"say \"hi\" \\o/"}]}}"""

        assertEquals("say \"hi\" \\o/", client.getPlayerSummary("1")!!.personaName)
    }

    @Test
    fun `returns null when no player is found`() {
        steam.body = """{"response":{"players":[]}}"""

        assertNull(client.getPlayerSummary("76561197960435530"))
    }

    @Test
    fun `returns null when the response has no players key`() {
        steam.body = """{"response":{}}"""

        assertNull(client.getPlayerSummary("76561197960435530"))
    }

    @Test
    fun `returns null on non-200 status`() {
        steam.status = 403
        steam.body = "Forbidden"

        assertNull(client.getPlayerSummary("76561197960435530"))
    }

    @Test
    fun `returns null when Steam is unreachable`() {
        steam.close()

        assertNull(client.getPlayerSummary("76561197960435530"))
    }
}
