package com.tivicopy.player.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Studio's own icon set (`docs/design/studio/canvas/Icon.dc.html`, the "סט האייקונים" board),
 * which replaced Material's filled icons everywhere in Studio stage 9 (ADR-033).
 *
 * One drawing rule for all of them: a 2-unit line on a 24-unit grid with round ends and joins, so a
 * glyph keeps one weight at every size it is drawn. Only four are solid — play, pause, record and
 * a favourite that is set ([StarOn]) — because those are the ones that state something is
 * happening or chosen, and a solid shape is how the set says so.
 *
 * The paths are the canvas's, verbatim, with one exception. The canvas is drawn right to left, so
 * its `back` points right and its `chevron-left` points left — towards the end of a Hebrew row.
 * Here those two are drawn left to right and marked `autoMirror`, so they point the right way in
 * either language (ADR-009): [Back] towards the start, [ChevronEnd] towards the end. Media glyphs
 * ([Rewind], [Forward], [Restart]) are not mirrored; a player's arrows are the same in every
 * language.
 *
 * Built lazily: an icon costs nothing until a screen draws it.
 */
object StudioIcons {
    val Play: ImageVector by lazy { glyph("play", "M7 4.5v15l12-7.5z", filled = true) }
    val Pause: ImageVector by lazy { glyph("pause", "M7 5h3.5v14H7zM13.5 5H17v14h-3.5z", filled = true) }
    val Guide: ImageVector by lazy { glyph("guide", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM3 10h18M9 10v10") }
    val Star: ImageVector by lazy { glyph("star", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z") }
    val Bell: ImageVector by lazy { glyph("bell", "M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0") }
    val Record: ImageVector by lazy { glyph("record", "M12 6a6 6 0 1 1 0 12a6 6 0 1 1 0-12z", filled = true) }
    val Search: ImageVector by lazy { glyph("search", "M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13zM20 20l-4.2-4.2") }
    val Settings: ImageVector by lazy { glyph("settings", "M4 7h10M18 7h2M4 17h4M12 17h8M16 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM10 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z") }
    val ChevronEnd: ImageVector by lazy { glyph("chevron-left", "M9 6l6 6-6 6", autoMirror = true) }
    val ChevronUp: ImageVector by lazy { glyph("chevron-up", "M6 15l6-6 6 6") }
    val ChevronDown: ImageVector by lazy { glyph("chevron-down", "M6 9l6 6 6-6") }
    val Check: ImageVector by lazy { glyph("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Lock: ImageVector by lazy { glyph("lock", "M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    val Trash: ImageVector by lazy { glyph("trash", "M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13") }
    val Rewind: ImageVector by lazy { glyph("rewind", "M11 6l-6 6 6 6M19 6l-6 6 6 6") }
    val Forward: ImageVector by lazy { glyph("forward", "M5 6l6 6-6 6M13 6l6 6-6 6") }
    val Restart: ImageVector by lazy { glyph("restart", "M6 5v14M18 6l-8 6 8 6z") }
    val Subtitles: ImageVector by lazy { glyph("subtitles", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7 15h4M13 15h4M7 11h10") }
    val Audio: ImageVector by lazy { glyph("audio", "M5 10v4h3l4 4V6L8 10zM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11") }
    val Aspect: ImageVector by lazy { glyph("aspect", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM7 9v6M17 9v6") }
    val Plus: ImageVector by lazy { glyph("plus", "M12 5v14M5 12h14") }
    val Tv: ImageVector by lazy { glyph("tv", "M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9 21h6") }
    val Radio: ImageVector by lazy { glyph("radio", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14") }
    /**
     * Sport mode's entry in the side menu (ADR-041): a ball with its seams, drawn to the set's rule.
     * Not on the canvas's board, which predates the mode.
     */
    val Sport: ImageVector by lazy { glyph("sport", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18zM3 12h18M12 3v18M5.6 5.6a9 9 0 0 1 0 12.8M18.4 5.6a9 9 0 0 0 0 12.8") }

    /**
     * News mode's (ADR-042): a newspaper, a picture and lines of text on its page, its back page
     * folded behind. Not on the canvas's board, which predates the mode.
     */
    val News: ImageVector by lazy {
        glyph("news", "M4 5h13v13a2 2 0 0 0 2 2H6a2 2 0 0 1-2-2zM17 9h3v9a2 2 0 0 1-2 2M7 9h3v3H7zM12.5 9H14M12.5 12H14M7 15.5h7")
    }
    val Hide: ImageVector by lazy { glyph("hide", "M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 8.5 4.5 9 6-.3.9-1.2 2.4-2.6 3.7M6.6 7.7C4.6 9 3.4 10.9 3 12c.5 1.5 4 6 9 6 1.6 0 3-.4 4.2-1.1") }
    val Move: ImageVector by lazy { glyph("move", "M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4") }
    val Info: ImageVector by lazy { glyph("info", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18zM12 11v6M12 7.5v.5") }
    val Account: ImageVector by lazy { glyph("account", "M12 4a4 4 0 1 1 0 8a4 4 0 1 1 0-8zM4.5 20a7.5 7.5 0 0 1 15 0") }
    val Alert: ImageVector by lazy { glyph("alert", "M12 4l9 16H3zM12 10v4M12 17v.5") }
    val Update: ImageVector by lazy { glyph("update", "M12 4v11M7 10l5 5 5-5M5 20h14") }
    val Back: ImageVector by lazy { glyph("back", "M15 6l-6 6 6 6", autoMirror = true) }
    val Home: ImageVector by lazy { glyph("home", "M4 11l8-7 8 7M6 9.5V20h4v-6h4v6h4V9.5") }
    val Menu: ImageVector by lazy { glyph("menu", "M4 7h16M4 12h16M4 17h16") }
    val Appearance: ImageVector by lazy { glyph("appearance", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18zM12 3v18M12 8h7M12 13h8.5M12 18h6") }
    val Network: ImageVector by lazy { glyph("network", "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0M12 19.5v.5") }
    val Backup: ImageVector by lazy { glyph("backup", "M12 15V4M8 8l4-4 4 4M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4") }
    val Pulse: ImageVector by lazy { glyph("pulse", "M3 12h4l2-5 4 10 2-5h6") }
    val StarOn: ImageVector by lazy { glyph("star-on", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z", filled = true) }
    val Close: ImageVector by lazy { glyph("close", "M6 6l12 12M18 6L6 18") }
    val History: ImageVector by lazy { glyph("history", "M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4M12 8v4l3 2") }
    val Grid: ImageVector by lazy { glyph("grid", "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z") }
    val Music: ImageVector by lazy { glyph("music", "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z") }
    val Movie: ImageVector by lazy { glyph("movie", "M4 8h16v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1zM4 8l1.5-4h13L20 8M9.5 4L8 8M15.5 4L14 8") }
    val Fullscreen: ImageVector by lazy { glyph("fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5") }
    /**
     * Changing what a Sport or News mode panel shows (ADR-041 decision 11): two arrows passing each
     * other, drawn to the set's rule. Not on the canvas's board, which predates the mode.
     */
    val Swap: ImageVector by lazy { glyph("swap", "M4 8h15M15 4l4 4-4 4M20 16H5M9 12l-4 4 4 4") }
    val Storage: ImageVector by lazy { glyph("storage", "M4 4h16v7H4zM4 13h16v7H4zM8 7.5h3M8 16.5h3") }
}

/** One glyph: the canvas's path, stroked at 2 with round caps, or filled for the solid four. */
private fun glyph(name: String, path: String, filled: Boolean = false, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = "Studio.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).addPath(
        pathData = addPathNodes(path),
        // The colour is a placeholder: `Icon` tints the whole vector with its own.
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = if (filled) null else SolidColor(Color.Black),
        strokeLineWidth = if (filled) 0f else STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** The set's one line weight, in grid units. */
private const val STROKE = 2f
