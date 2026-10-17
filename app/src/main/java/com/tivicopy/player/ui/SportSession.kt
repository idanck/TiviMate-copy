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
 * Sport mode's state (ADR-041), and News mode's (ADR-042): two panels, which one has the remote,
 * and whether it fills the screen.
 *
 * The two modes share one session because they are never on screen together: [mode] says which
 * one it is, and decides which channels are offered, where the grid is remembered, and whether a
 * panel may show the headlines instead of a channel — News mode's [Slot.flashes].
 *
 * Held by [AppViewModel] and driven from two places: the screen moves the focus and picks
 * channels, and the activity owns Back, the channel keys and the lifecycle, as it does for every
 * other screen on the television. Which channels were on screen outlives the session, in the
 * settings, so each mode opens on the grid it was left with.
 *
 * There are always [SportLayout.SLOTS] slots, each with its own player; only the first [count]
 * are on screen, and the rest are kept empty and released.
 *
 * The sound follows the remote: whichever panel is in focus is the one heard, and moving the focus
 * moves the sound with it. An empty panel in focus is silence; the headlines in focus leave the
 * sound with the picture beside them ([audibleSlot]).
 */
class SportSession(
    context: Context,
    private val settings: Settings,
    /** The request profile a channel's own playlist asks for. */
    private val profileFor: (Channel) -> RequestProfile,
) {

    /** Which of the two modes the session is: they differ in what they offer, not in how. */
    enum class Mode { SPORT, NEWS }

    /**
     * One panel: the channel it shows, if any, and the player showing it. The player belongs to the
     * channel rather than to the place, so two panels that swap channels swap players too, and
     * nothing reconnects. In News mode a panel may show the headlines instead ([flashes]): no
     * channel, its player released, and no connection of its own.
     */
    class Slot(val channel: Channel?, val panel: PanelPlayer, val flashes: Boolean = false)

    private val main = Handler(Looper.getMainLooper())

    private val _anyPlaying = MutableStateFlow(false)

    /** Whether any panel is showing a running picture: what the screensaver asks (ADR-040). */
    val anyPlaying: StateFlow<Boolean> = _anyPlaying

    private val _live = MutableStateFlow(false)

    /**
     * Whether a mode is the screen and in front of the viewer: what keeps News mode's headlines
     * fetching, and nothing while the app is out of sight.
     */
    val live: StateFlow<Boolean> = _live

    private val _slots = MutableStateFlow(
        List(SportLayout.SLOTS) { Slot(null, PanelPlayer(context.applicationContext, settings, main, ::refresh)) },
    )
    val slots: StateFlow<List<Slot>> = _slots

    private val _mode = MutableStateFlow(Mode.SPORT)

    /** Sport mode or News mode; the one last begun while neither is on screen. */
    val mode: StateFlow<Mode> = _mode

    private val _count = MutableStateFlow(SportLayout.SLOTS)

    /** How many panels are on screen: two, in both modes and on both shells (ADR-041, decision 10). */
    val count: StateFlow<Int> = _count

    private val _focused = MutableStateFlow(0)

    /** The panel with the remote, and so the one heard. */
    val focused: StateFlow<Int> = _focused

    private val _expanded = MutableStateFlow(false)

    /** Whether the panel in focus fills the screen, the others still playing behind it. */
    val expanded: StateFlow<Boolean> = _expanded

    private val _picking = MutableStateFlow<Int?>(null)

    /** The panel the channel picker is choosing for; null while it is closed. */
    val picking: StateFlow<Int?> = _picking

    private val _menu = MutableStateFlow<Int?>(null)

    /**
     * The panel whose menu is open on the television (full screen, change the channel, remove;
     * ADR-041 decision 11); null while none is. The phone reaches the same things by touch.
     */
    val menu: StateFlow<Int?> = _menu

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
     * Counts the openings, so the channels read for one are never offered to the next — Sport mode
     * left and News mode opened before Sport mode's list came back.
     */
    private var epoch = 0

    /**
     * Opens [mode] on an empty grid of [panels], while the channels are read, and returns the
     * opening's number for [offer]. Both modes have two panels (ADR-041, decision 10); the grid
     * itself can lay out four, and [panels] says how many to use.
     */
    fun begin(mode: Mode, panels: Int): Int {
        if (active) return epoch
        active = true
        epoch++
        _mode.value = mode
        suspended = false
        _expanded.value = false
        _picking.value = null
        _choices.value = null
        _focused.value = 0
        _paused.value = false
        _count.value = SportLayout.panels(panels)
        _slots.value = _slots.value.map { slot ->
            slot.panel.release()
            slot.panel.setPaused(false)
            Slot(null, slot.panel)
        }
        refresh()
        return epoch
    }

    /**
     * The channels the mode may show, and the ones it showed last time, by panel, with the panel
     * that showed the headlines at [flashesAt] (News mode; -1 for none). The grid comes back as it
     * was left. A grid with nothing to come back to opens the picker on the first panel — and in
     * News mode puts the headlines in the second, the one panel that needs no connection.
     *
     * @param opening what [begin] returned; an offer for an earlier opening is ignored
     */
    fun offer(opening: Int, choices: List<Channel>, restored: List<Channel?>, flashesAt: Int = -1) {
        if (!active || opening != epoch) return
        _choices.value = choices
        // Something was already chosen while the list was being read; that choice stands.
        if (_slots.value.any { it.channel != null || it.flashes }) return
        val seen = HashSet<Long>()
        val count = _count.value
        val news = _mode.value == Mode.NEWS
        val nothingBack = restored.take(count).none { it != null } && flashesAt !in 0 until count
        val headlines = when {
            !news -> -1
            nothingBack -> 1
            else -> flashesAt
        }
        _slots.value = _slots.value.mapIndexed { i, slot ->
            val flashes = i == headlines && i < count
            val channel = restored.getOrNull(i)?.takeIf { !flashes && i < count && seen.add(it.id) }
            if (channel != null && !suspended) slot.panel.play(channel, profileFor(channel))
            Slot(channel, slot.panel, flashes)
        }
        // The remote goes to the first channel; with none, to the first empty panel, where the
        // picker opens — never onto the headlines, which a first choice would replace.
        val first = _slots.value.indexOfFirst { it.channel != null }
        val empty = _slots.value.take(count).indexOfFirst { it.channel == null && !it.flashes }
        _focused.value = when {
            first >= 0 -> first
            empty >= 0 -> empty
            else -> 0
        }
        if (first < 0 && empty >= 0 && choices.isNotEmpty()) _picking.value = empty
        applyAudible()
        refresh()
    }

    /** Leaves Sport mode: every panel stops and lets go of its decoders and its connection. */
    fun close() {
        if (!active) return
        active = false
        suspended = false
        _slots.value.forEach { it.panel.release() }
        _expanded.value = false
        _picking.value = null
        _menu.value = null
        refresh()
    }

    /**
     * The channel being heard — the panel with the remote's, or beside the headlines the picture's
     * ([audibleSlot]) — which is what plays on when the mode is left; none while neither mode is
     * the screen, whatever the panels last showed.
     */
    fun focusedChannel(): Channel? {
        if (!active) return null
        val slots = _slots.value
        return slots.getOrNull(audibleSlot(slots, _focused.value))?.channel
    }

    fun focus(slot: Int) {
        if (slot !in 0 until _count.value || _focused.value == slot) return
        _focused.value = slot
        applyAudible()
    }

    /**
     * Fills the screen with the panel in focus — a channel, or the headlines — or puts the grid
     * back. An empty panel stays put.
     */
    fun setExpanded(value: Boolean) {
        val shown = focusedChannel() != null || (active && _slots.value.getOrNull(_focused.value)?.flashes == true)
        _expanded.value = value && shown
    }

    fun openPicker(slot: Int) {
        if (active && slot in 0 until _count.value) {
            _menu.value = null
            _picking.value = slot
        }
    }

    fun closePicker() {
        _picking.value = null
    }

    /** Opens the panel menu for [slot] on the television; a panel with nothing in it has none. */
    fun openMenu(slot: Int) {
        val entry = _slots.value.getOrNull(slot) ?: return
        if (active && slot < _count.value && (entry.channel != null || entry.flashes)) _menu.value = slot
    }

    fun closeMenu() {
        _menu.value = null
    }

    /**
     * Puts [channel] in [slot]. A channel already on screen moves rather than opening a second
     * connection to the same stream: the two panels swap, players and all.
     */
    fun assign(slot: Int, channel: Channel) {
        val current = _slots.value
        if (!active || slot !in 0 until _count.value) return
        val from = current.indexOfFirst { it.channel?.id == channel.id }
        val next = current.toMutableList()
        when {
            from == slot -> Unit
            from >= 0 -> {
                next[slot] = current[from]
                next[from] = current[slot]
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

    /**
     * News mode's headlines in [slot] instead of a channel; its player stops and lets go of its
     * connection. There is one panel of headlines at most: if another panel has them, the two
     * swap, players and all, as two channels do in [assign].
     */
    fun showFlashes(slot: Int) {
        val current = _slots.value
        if (!active || _mode.value != Mode.NEWS || slot !in 0 until _count.value) return
        val from = current.indexOfFirst { it.flashes }
        val next = current.toMutableList()
        when {
            from == slot -> Unit
            from >= 0 -> {
                next[slot] = current[from]
                next[from] = current[slot]
            }
            else -> {
                current[slot].panel.release()
                next[slot] = Slot(null, current[slot].panel, flashes = true)
            }
        }
        _slots.value = next
        _picking.value = null
        _focused.value = slot
        applyAudible()
        persist()
        refresh()
    }

    /** Empties [slot]; its player stops and lets go of its connection. */
    fun clear(slot: Int) {
        val current = _slots.value
        val entry = current.getOrNull(slot) ?: return
        entry.panel.release()
        _slots.value = current.toMutableList().also { it[slot] = Slot(null, entry.panel) }
        _picking.value = null
        _menu.value = null
        if (_focused.value == slot) _expanded.value = false
        applyAudible()
        persist()
        refresh()
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
        // The headlines have no channel to step from, and stepping would take their place.
        if (current.getOrNull(slot)?.flashes == true) return
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
        refresh()
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
        _slots.value = _slots.value.map { slot ->
            val channel = slot.channel?.takeIf(allowed)
            if (channel != null) slot.panel.play(channel, profileFor(channel))
            Slot(channel, slot.panel, slot.flashes)
        }
        applyAudible()
        refresh()
    }

    /**
     * Silences every panel but the one to be heard ([audibleSlot]), then lets that one speak — in
     * that order, so the one about to be heard never asks for the sound while another still holds
     * it.
     */
    private fun applyAudible() {
        val slots = _slots.value
        val target = audibleSlot(slots, _focused.value)
        slots.forEachIndexed { i, slot -> if (i != target) slot.panel.setAudible(false) }
        slots.getOrNull(target)?.panel?.setAudible(true)
    }

    private fun persist() {
        val ids = _slots.value.map { if (it.flashes) FLASHES_ID else it.channel?.id }
        when (_mode.value) {
            Mode.SPORT -> settings.setSportSlots(ids)
            Mode.NEWS -> settings.setNewsSlots(ids)
        }
    }

    private fun refresh() {
        _live.value = active && !suspended
        // The headlines move too, so a grid that shows them is not a still screen (ADR-040) —
        // unless the panels are paused, when the picture beside them is a still frame, as a paused
        // grid is in Sport mode.
        _anyPlaying.value = active && !suspended && !_paused.value &&
            _slots.value.take(_count.value).any { it.flashes || it.panel.status.value.playing }
    }

    companion object {
        /** What News mode's remembered grid says for the panel of headlines. */
        const val FLASHES_ID = -1L

        /**
         * The panel to be heard: the one in focus, unless it shows the headlines, which have no
         * sound — then the first panel with a channel, so the picture beside them goes on being
         * heard. -1 for none.
         */
        fun audibleSlot(slots: List<Slot>, focused: Int): Int =
            if (slots.getOrNull(focused)?.flashes == true) slots.indexOfFirst { it.channel != null } else focused
    }
}
