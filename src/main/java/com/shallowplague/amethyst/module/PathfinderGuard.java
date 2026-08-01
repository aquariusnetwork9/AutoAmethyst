package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;

import java.util.ArrayList;

import static com.zenith.Globals.CONFIG;

/**
 * Makes Zenith's pathfinder non-destructive for as long as this module is enabled.
 *
 * <p><b>Why this exists.</b> {@code CONFIG.client.extra.pathfinder.allowBreak} and {@code allowPlace}
 * both default to <b>true</b>. Left alone, the path planner treats breaking a wall or pillaring up
 * as just another movement with a cost, so any pathing at all - moving between waypoints, walking
 * to a dropped shard, going to the deposit - can mine or build its way through the rig. In a geode
 * farm that means it can quietly eat budding amethyst, which is unobtainable and gone forever.
 *
 * <p>So the module clamps them off on enable and restores whatever the user had on disable. This is
 * the outer fence; the per-target allowlist in {@link HarvestPolicy} is the inner one. Both exist
 * because they fail differently: the allowlist governs the blocks we deliberately click, this
 * governs the ones the pathfinder would decide to remove on its own initiative.
 *
 * <p>{@code allowBreakAnyway} is cleared too. It is an explicit override consulted precisely when
 * {@code allowBreak} is false ({@code CalculationContext:75}), so leaving it populated would punch
 * a hole straight through the fence we just put up.
 *
 * <p>The snapshot lives in the plugin's own config file rather than in a field, because Zenith
 * writes its config to disk while we are running. If the proxy were killed mid-session the clamped
 * values would be all that is left on disk, and an in-memory snapshot would die with the process -
 * so the next startup would faithfully "restore" the clamp. Persisting it means a crashed session
 * still gets put back.
 *
 * <p>None of this blocks {@code BARITONE.breakBlock} or {@code placeBlock}: those run through
 * {@code InteractWithProcess}, not through movement cost calculation. Deliberate interactions still
 * work, self-directed ones stop.
 */
public final class PathfinderGuard {

    private final AutoAmethystConfig.Internal state;

    public PathfinderGuard(final AutoAmethystConfig.Internal state) {
        this.state = state;
    }

    /**
     * Clamps the pathfinder. If a previous session was killed while clamped, the values currently
     * on disk are ours, not the user's, so the existing snapshot is kept rather than overwritten.
     */
    public void apply() {
        apply(true);
    }

    /**
     * @param sprint whether the pathfinder may sprint. Zenith's {@code allowSprint} defaults to
     *               <b>false</b>, so a waypoint leg walks at base speed unless this turns it on -
     *               which looks exactly like "the bot is sneaking everywhere".
     */
    public void apply(final boolean sprint) {
        final var pf = CONFIG.client.extra.pathfinder;
        if (!state.pathfinderClampActive) {
            state.savedAllowBreak = pf.allowBreak;
            state.savedAllowPlace = pf.allowPlace;
            state.savedAllowParkourPlace = pf.allowParkourPlace;
            state.savedAllowSprint = pf.allowSprint;
            state.savedAllowBreakAnyway = new ArrayList<>(pf.allowBreakAnyway);
            state.pathfinderClampActive = true;
        }
        pf.allowBreak = false;
        pf.allowPlace = false;
        pf.allowParkourPlace = false;
        pf.allowBreakAnyway.clear();
        pf.allowSprint = sprint;
    }

    public void restore() {
        if (!state.pathfinderClampActive) return;
        final var pf = CONFIG.client.extra.pathfinder;
        pf.allowBreak = state.savedAllowBreak;
        pf.allowPlace = state.savedAllowPlace;
        pf.allowParkourPlace = state.savedAllowParkourPlace;
        pf.allowSprint = state.savedAllowSprint;
        pf.allowBreakAnyway.clear();
        if (state.savedAllowBreakAnyway != null) {
            pf.allowBreakAnyway.addAll(state.savedAllowBreakAnyway);
        }
        state.pathfinderClampActive = false;
    }

    public boolean isApplied() {
        return state.pathfinderClampActive;
    }

    /**
     * True when the clamp is still in force. Something else writing these settings while we run
     * would silently re-arm the pathfinder, so the module re-checks every tick rather than trusting
     * {@link #apply()} to have stuck.
     */
    public boolean stillClamped() {
        final var pf = CONFIG.client.extra.pathfinder;
        return !pf.allowBreak && !pf.allowPlace && pf.allowBreakAnyway.isEmpty();
    }
}
