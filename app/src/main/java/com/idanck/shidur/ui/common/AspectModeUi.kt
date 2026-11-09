package com.idanck.shidur.ui.common

import androidx.annotation.StringRes
import com.idanck.shidur.R
import com.idanck.shidur.data.prefs.AspectMode

/**
 * The one user-facing name per aspect mode, shared by Settings and the player's aspect
 * control. Exhaustive, so adding a mode cannot compile without naming it — the alternative
 * was two hand-written maps that a new entry would silently miss.
 */
@StringRes
fun AspectMode.labelRes(): Int = when (this) {
    AspectMode.FIT -> R.string.aspect_fit
    AspectMode.FILL -> R.string.aspect_fill
    AspectMode.ZOOM -> R.string.aspect_zoom
    AspectMode.STRETCH -> R.string.aspect_stretch
    AspectMode.RATIO_16_9 -> R.string.aspect_16_9
    AspectMode.RATIO_4_3 -> R.string.aspect_4_3
    AspectMode.ORIGINAL -> R.string.aspect_original
}
