package com.tivicopy.player.ui.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tivicopy.player.R
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.monogram
import com.tivicopy.player.ui.common.monogramColour
import com.tivicopy.player.ui.home.HomeApps
import com.tivicopy.player.ui.home.LaunchableApp
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The row of installed streaming apps under the home banner (FR-UI-28, ADR-020).
 *
 * Which apps exist is `ui/home/HomeApps`, which is plain Kotlin and unit-tested; everything the
 * platform has to answer — is it installed, what is it called, what does its icon look like, how
 * is it started — is here, because none of it can run in this repository's JVM test tier.
 *
 * A device with none of the catalogue installed gets no strip at all, on the same rule an empty
 * rail follows: a heading over nothing is worse than no heading.
 */
internal data class HomeAppTile(val app: LaunchableApp, val icon: Drawable?)

/**
 * Resolves the catalogue against this device, off the main thread.
 *
 * Fifteen `PackageManager` lookups are each a binder round trip, and the home screen is the first
 * thing drawn on a cold start — doing them inside `remember` would put them on the frame that is
 * already competing with the player starting up. The strip simply appears a frame or two late.
 */
@Composable
internal fun rememberHomeApps(): List<HomeAppTile> {
    val context = LocalContext.current
    val apps = produceState(initialValue = emptyList<HomeAppTile>(), context) {
        value = withContext(Dispatchers.IO) { loadHomeApps(context) }
    }
    return apps.value
}

private fun loadHomeApps(context: Context): List<HomeAppTile> {
    val packages = context.packageManager
    return HomeApps.resolve(
        launchable = { name -> launchIntentFor(packages, name) != null },
        label = { name ->
            runCatching { packages.getApplicationInfo(name, 0).loadLabel(packages).toString() }.getOrNull()
        },
    ).map { app ->
        HomeAppTile(
            app = app,
            icon = runCatching { packages.getApplicationIcon(app.packageName) }.getOrNull(),
        )
    }
}

/**
 * The way in to another app: its leanback entry point on a television, its ordinary one anywhere
 * else. Null means "not installed, or installed with nothing to open", and both are the same
 * answer to this screen.
 *
 * Wrapped because a `PackageManager` call can throw for a package that is being upgraded
 * underneath us, and a home screen that crashes because Netflix is updating is not acceptable.
 */
private fun launchIntentFor(packages: PackageManager, name: String): Intent? = runCatching {
    packages.getLeanbackLaunchIntentForPackage(name) ?: packages.getLaunchIntentForPackage(name)
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeAppStrip(
    apps: List<HomeAppTile>,
    background: Color,
    /**
     * Room kept clear at the leading edge for the side menu's closed strip (ADR-027), which is
     * drawn over this row rather than beside it. The row itself stays full-bleed so its backdrop
     * reaches the screen edge under the strip.
     */
    startInset: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().background(background)) {
        // `TvHomeApps`: the h1 step in ink, and a caption saying where the list comes from.
        Row(
            modifier = Modifier.padding(start = startInset, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.home_apps), style = StudioTheme.type.h1, color = StudioTheme.colors.ink)
            Text(
                text = stringResource(R.string.home_apps_hint),
                style = StudioTheme.type.caption,
                color = StudioTheme.colors.ink2,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(start = startInset, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(apps, key = { it.app.id }) { tile ->
                AppTile(
                    tile = tile,
                    onClick = {
                        val intent = launchIntentFor(context.packageManager, tile.app.packageName)
                        if (intent != null) {
                            // The launcher's own flag set: this app stays where it is, and the
                            // other one comes back to whatever it was showing.
                            runCatching {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppTile(tile: HomeAppTile, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // `TvHomeApps`: each app is a card — the card colour inside a faint edge, raised to
    // `card-focus` in the cue ring when the remote is on it — with the app's own icon in the
    // middle and its name under it.
    val shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner)
    Column(
        modifier = Modifier.width(APP_TILE_WIDTH.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(APP_TILE_WIDTH.dp)
                .aspectRatio(16f / 9f)
                .focusLift(focused, shape = shape)
                .focusSurface(focused = focused, shape = shape, fill = FocusFill.RAISE)
                .onFocusChanged { focused = it.isFocused }
                // `clickable` is the focus target on its own.
                .clickable(onClick = onClick)
                .semantics { contentDescription = tile.app.label },
            contentAlignment = Alignment.Center,
        ) {
            if (tile.icon == null) {
                Box(
                    modifier = Modifier
                        .size(APP_ICON_SIZE.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(monogramColour(tile.app.label)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = monogram(tile.app.label), style = StudioTheme.type.rowLabel, color = Color.White)
                }
            } else {
                // Coil takes a `Drawable` as its model directly, which is what keeps the platform's
                // own adaptive icon — mask, background layer and all — instead of a flattened bitmap.
                AsyncImage(
                    model = tile.icon,
                    contentDescription = null,
                    modifier = Modifier.size(APP_ICON_SIZE.dp),
                )
            }
        }
        Text(
            text = tile.app.label,
            style = StudioTheme.type.caption,
            color = if (focused) StudioTheme.colors.ink else StudioTheme.colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `TvHomeApps`' tile: 200px at 1080p, 16:9, with the icon at 64px in it. */
private const val APP_TILE_WIDTH = 100
private const val APP_ICON_SIZE = 32
