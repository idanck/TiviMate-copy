package com.tivicopy.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.player.PanelPlayer
import com.tivicopy.player.ui.tv.SportLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sport mode's state (ADR-041): four panels, which one has the remote, and whether it fills the
 * screen.
 *
 * Held by [AppViewModel] and driven from two places: the screen moves the focus and picks
 * channels, and the activity owns Back, the channel keys and the lifecycle, as it does for every
 * other screen on the television. Which channels were on screen outlives the session, in the
 * settings, so Sport mode opens on the grid it was left with.
 *
 * The sound follows the remote: whichever panel is in focus is the one heard, and moving the focus
 * moves the sound with it. An empty panel in focus is silence.
 */
class SportSession(
    context: Context,
    private val settings: Settings,
    /** The request profile a channel's own playlist asks for. */
    private val profileFor: (Channel) -> RequestProfile,
) {

    /**
     * One panel: the channel it shows, if any, and the player showing it. The player belongs to the
     * channel rather than to the place, so two panels that swap channels swap players too, and
     * nothing reconnects.
     */
    class Slot(val channel: Channel?, val panel: PanelPlayer)

    private val main = Handler(Looper.getMainLooper())

    private val _anyPlaying = MutableStateFlow(false)

    /** Whether any panel is showing a running picture: what the screensaver asks (ADR-040). */
    val anyPlaying: StateFlow<Boolean> = _anyPlaying

    private val _slots = MutableStateFlow(
        List(SportLayout.SLOTS) {
            Slot(
                null,
                PanelPlayer(
                    context.applicationContext,
                    settings,
                    main,
                    onChanged = ::refresh,
                    onGaveUp = ::retryStarved,
                ),
            )
        },
    )
    val slots: StateFlow<List<Slot>> = _slots

    private val _focused = MutableStateFlow(0)

    /** The panel with the remote, and so the one heard. */
    val focused: StateFlow<Int> = _focused

    private val _expanded = MutableStateFlow(false)

    /** Whether the panel in focus fills the screen, the others still playing behind it. */
    val expanded: StateFlow<Boolean> = _expanded

    private val _picking = MutableStateFlow<Int?>(null)

    /** The panel the channel picker is choosing for; null while it is closed. */
    val picking: StateFlow<Int?> = _picking

    private val _choices = MutableStateFlow<List<Channel>?>(null)

    /** The channels on offer, in playlist order; null while they are still being read. */
    val choices: StateFlow<List<Channel>?> = _choices

    private val _paused = MutableStateFlow(false)

    /** Whether the remote's play/pause key has stopped every panel. */
    val paused: StateFlow<Boolean> = _paused

    /** Whether Sport mode is the screen. Everything below is a no-op while it is not. */
    var active = false
        private set

    /** Set while the activity is stopped: the panels are released, the channels remembered. */
    private var suspended = false

    /**
     * The panel [startFocusedFirst] started first, while the others are held back for it; null
     * when nothing is held back.
     */
    private var head: PanelPlayer? = null

    private val startWaitingTask = Runnable { startWaiting() }

    /** Opens Sport mode on an empty grid, while the channels are read. */
    fun begin() {
        if (active) return
        active = true
        suspended = false
        cancelWaiting()
        _expanded.value = false
        _picking.value = null
        _choices.value = null
        _focused.value = 0
        _paused.value = false
        _slots.value = _slots.value.map { slot ->
            slot.panel.release()
            slot.panel.setPaused(false)
            Slot(null, slot.panel)
        }
        refresh()
    }

    /**
     * The channels Sport mode may show, and the ones it showed last time, by panel. The grid comes
     * back as it was left; a grid with nothing to come back to opens the picker on the first panel.
     */
    fun offer(choices: List<Channel>, restored: List<Channel?>) {
        if (!active) return
        _choices.value = choices
        // Something was already chosen while the list was being read; that choice stands.
        if (_slots.value.any { it.channel != null }) return
        val seen = HashSet<Long>()
        _slots.value = _slots.value.mapIndexed { i, slot ->
            Slot(restored.getOrNull(i)?.takeIf { seen.add(it.id) }, slot.panel)
        }
        val first = _slots.value.indexOfFirst { it.channel != null }
        _focused.value = first.coerceAtLeast(0)
        if (first < 0 && choices.isNotEmpty()) _picking.value = 0
        applyAudible()
        if (!suspended) startFocusedFirst()
    }

    /** Leaves Sport mode: every panel stops and lets go of its decoders and its connection. */
    fun close() {
        if (!active) return
        active = false
        suspended = false
        cancelWaiting()
        _slots.value.forEach { it.panel.release() }
        _expanded.value = false
        _picking.value = null
        refresh()
    }

    /** The channel in the panel with the remote, which is what plays on when Sport mode is left. */
    fun focusedChannel(): Channel? = _slots.value.getOrNull(_focused.value)?.channel

    fun focus(slot: Int) {
        if (slot !in _slots.value.indices || _focused.value == slot) return
        _focused.value = slot
        applyAudible()
        // The picture being watched and heard comes first: a panel held back for the first one,
        // or stopped for want of a decoder, starts the moment the viewer turns to it.
        if (active && !suspended) startIfIdle(_slots.value[slot])
    }

    /** Fills the screen with the panel in focus, or puts the grid back. An empty panel stays put. */
    fun setExpanded(value: Boolean) {
        _expanded.value = value && focusedChannel() != null
    }

    fun openPicker(slot: Int) {
        if (active && slot in _slots.value.indices) _picking.value = slot
    }

    fun closePicker() {
        _picking.value = null
    }

    /**
     * Puts [channel] in [slot]. A channel already on screen moves rather than opening a second
     * connection to the same stream: the two panels swap, players and all.
     */
    fun assign(slot: Int, channel: Channel) {
        val current = _slots.value
        if (!active || slot !in current.indices) return
        val from = current.indexOfFirst { it.channel?.id == channel.id }
        val next = current.toMutableList()
        when {
            from == slot -> Unit
            from >= 0 -> {
                next[slot] = current[from]
                next[from] = current[slot]
                // Either may still be held back by [startFocusedFirst]: chosen now, it starts now.
                if (!suspended) listOf(next[slot], next[from]).forEach(::startIfIdle)
            }
            else -> {
                val panel = current[slot].panel
                if (!suspended) panel.play(channel, profileFor(channel))
                next[slot] = Slot(channel, panel)
            }
        }
        _slots.value = next
        _picking.value = null
        _focused.value = slot
        applyAudible()
        persist()
    }

    /** Empties [slot]; its player stops and lets go of its connection. */
    fun clear(slot: Int) {
        val current = _slots.value
        val entry = current.getOrNull(slot) ?: return
        entry.panel.release()
        _slots.value = current.toMutableList().also { it[slot] = Slot(null, entry.panel) }
        _picking.value = null
        if (_focused.value == slot) _expanded.value = false
        applyAudible()
        persist()
        refresh()
        retryStarved()
    }

    /**
     * A video decoder came free — a panel was emptied, or gave up on its channel: every panel that
     * went without one tries again. Those in the background are offered hardware decoders only, so
     * the ones that lose the race stop again at once rather than starting a software decoder.
     */
    private fun retryStarved() {
        if (!active || suspended) return
        _slots.value.filter { it.panel.status.value.starved }.forEach(::startIfIdle)
    }

    /**
     * The channel keys: the next or previous channel on offer, in the panel with the remote.
     * Channels already in another panel are stepped over, so a press never swaps two panels.
     */
    fun step(delta: Int) {
        val choices = _choices.value ?: return
        if (!active || choices.isEmpty() || _picking.value != null || delta == 0) return
        val slot = _focused.value
        val current = _slots.value
        val elsewhere = current.filterIndexed { i, _ -> i != slot }.mapNotNull { it.channel?.id }.toSet()
        val at = choices.indexOfFirst { it.id == current[slot].channel?.id }
        // From an empty panel, up starts at the top of the list and down at the bottom.
        val base = when {
            at >= 0 -> at
            delta > 0 -> -1
            else -> choices.size
        }
        for (k in 1..choices.size) {
            val candidate = choices[(base + delta * k).mod(choices.size)]
            if (candidate.id !in elsewhere) {
                if (candidate.id != current[slot].channel?.id) assign(slot, candidate)
                return
            }
        }
    }

    /** Starts a failed panel's channel again from the top. */
    fun retry(slot: Int) {
        val entry = _slots.value.getOrNull(slot) ?: return
        val channel = entry.channel ?: return
        if (active && !suspended) entry.panel.play(channel, profileFor(channel))
    }

    /** The remote's play/pause: every panel at once. */
    fun togglePause() {
        if (!active) return
        val value = !_paused.value
        _paused.value = value
        _slots.value.forEach { it.panel.setPaused(value) }
    }

    /** Every panel quiet at once, ahead of [suspend] or [close]; see [PanelPlayer.silence]. */
    fun silence() {
        if (active) _slots.value.forEach { it.panel.silence() }
    }

    /**
     * The activity is going out of sight: every panel stops and lets go, as the full-screen player
     * does when there is no floating window to go to. The channels stay, for [resume].
     */
    fun suspend() {
        if (!active || suspended) return
        suspended = true
        cancelWaiting()
        _slots.value.forEach { it.panel.release() }
        refresh()
    }

    /**
     * Back in front of the viewer: every panel starts its channel again, except one [allowed] now
     * refuses — a parental lock that came back on while the app was away.
     */
    fun resume(allowed: (Channel) -> Boolean) {
        if (!active || !suspended) return
        suspended = false
        _slots.value = _slots.value.map { slot -> Slot(slot.channel?.takeIf(allowed), slot.panel) }
        applyAudible()
        startFocusedFirst()
    }

    /**
     * Starts the panel with the remote first, and the rest once it has its video decoder, is ready
     * or has failed — or after [FOCUSED_HEAD_START_MS], whichever is sooner.
     *
     * A television box may hold fewer hardware video decoders than there are panels, and they go
     * to whichever streams reach them first. Started together, the one the viewer is looking at
     * and listening to was as likely as any to be the panel left without one (device report,
     * 2026-10-17: the picture that kept moving was not necessarily the one in focus).
     */
    private fun startFocusedFirst() {
        cancelWaiting()
        val target = _focused.value
        val slots = _slots.value
        val first = slots.getOrNull(target)?.takeIf { it.channel != null }
        if (first != null) startIfIdle(first)
        val rest = slots.filterIndexed { i, slot -> i != target && slot.channel != null && slot.panel.player.value == null }
        if (rest.isEmpty()) return
        // Nothing to wait for: an empty panel in focus, or one that settled as it started.
        if (first == null || settled(first.panel)) {
            startWaiting()
            return
        }
        head = first.panel
        rest.forEach { it.panel.markPending() }
        main.postDelayed(startWaitingTask, FOCUSED_HEAD_START_MS)
    }

    /**
     * Whether [panel] no longer needs a head start: it has its decoder, is ready, has failed, or
     * is not playing at all any more (its panel was emptied).
     */
    private fun settled(panel: PanelPlayer): Boolean =
        panel.holdsVideoDecoder || panel.isReady || panel.status.value.failed || panel.player.value == null

    /** Starts every panel with a channel and no player: those [startFocusedFirst] held back. */
    private fun startWaiting() {
        cancelWaiting()
        if (!active || suspended) return
        _slots.value.forEach(::startIfIdle)
    }

    private fun startIfIdle(slot: Slot) {
        val channel = slot.channel ?: return
        if (slot.panel.player.value == null) slot.panel.play(channel, profileFor(channel))
    }

    private fun cancelWaiting() {
        main.removeCallbacks(startWaitingTask)
        head = null
    }

    /**
     * Silences every panel but the one in focus, then lets that one speak — in that order, so the
     * one about to be heard never asks for the sound while another still holds it.
     *
     * The same goes for the software video decoder: only the panel in focus may fall back to one
     * ([PanelPlayer.setSoftwareAllowed]). The others are offered hardware decoders only, and say
     * so when there is none left, rather than freezing on a decoder that cannot keep up.
     */
    private fun applyAudible() {
        val target = _focused.value
        val slots = _slots.value
        slots.forEachIndexed { i, slot ->
            if (i != target) {
                slot.panel.setAudible(false)
                slot.panel.setSoftwareAllowed(false)
            }
        }
        slots.getOrNull(target)?.panel?.let {
            it.setSoftwareAllowed(true)
            it.setAudible(true)
        }
    }

    private fun persist() {
        settings.setSportSlots(_slots.value.map { it.channel?.id })
    }

    private fun refresh() {
        _anyPlaying.value = active && !suspended && _slots.value.any { it.panel.status.value.playing }
        head?.let { if (settled(it)) startWaiting() }
    }

    private companion object {
        /**
         * The longest the other panels wait for the one in focus (see [startFocusedFirst]). Only a
         * backstop: they go as soon as it has its decoder, which is usually well inside this.
         */
        const val FOCUSED_HEAD_START_MS = 8_000L
    }
}
