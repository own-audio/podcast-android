package audio.own.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.Palette
import audio.own.core.designsystem.theme.Tokens
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps Tokens.kt and Accent.kt in step with docs/design-tokens.json, the mirror of iOS's Theme.swift. */
class TokenTest {
    private val json = Json.parseToJsonElement(File("../../docs/design-tokens.json").readText()).jsonObject

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun check(palette: Palette, mode: String) {
        val colors = json["colors"]!!.jsonObject
        val actual = mapOf(
            "background" to palette.background, "backgroundAlt" to palette.backgroundAlt, "card" to palette.card,
            "foreground" to palette.foreground, "muted" to palette.muted, "border" to palette.border,
            "success" to palette.success, "error" to palette.error, "markArcs" to palette.markArcs,
        )
        assertEquals(colors.keys, actual.keys)
        actual.forEach { (name, color) ->
            assertEquals("$name $mode", colors[name]!!.jsonObject[mode]!!.jsonPrimitive.content, hex(color))
        }
    }

    @Test fun neutralsMatch() {
        check(Tokens.light, "light")
        check(Tokens.dark, "dark")
    }

    @Test fun accentIsOneOfTheApps() {
        val accents = json["accent"]!!.jsonObject.values.map { it as JsonObject }
        assertTrue(accents.any {
            it["light"]!!.jsonPrimitive.content == hex(AppBrand.accentLight) && it["dark"]!!.jsonPrimitive.content == hex(AppBrand.accentDark)
        })
    }

    @Test fun familyMatches() {
        assertEquals(json["family"]!!.jsonArray.map { it.jsonPrimitive.content }, Tokens.family.map(::hex))
        assertEquals(json["markCore"]!!.jsonPrimitive.content, hex(Tokens.markCore))
    }
}
