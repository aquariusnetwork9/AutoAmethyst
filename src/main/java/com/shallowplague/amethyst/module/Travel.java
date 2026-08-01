package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.block.BlockPos;
import com.zenith.util.math.MathHelper;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;

/**
 * Walks the bot to a block, using the pathfinder and nothing else.
 *
 * <p>There is no route, no waypoint list and no hand-driven walking here. The bot is told "get
 * within reach of this block" and Baritone works out how - around obstacles, up ladders, down a
 * level - which is the entire reason a pathfinder exists. Earlier versions hand-walked and tried to
 * escalate to the pathfinder only when the hand walk failed; that produced a bot that bounced off
 * corners and got stuck on half blocks it could plainly have walked around.
 *
 * <p>Two things make this safe inside a geode. {@link PathfinderGuard} has clamped {@code allowBreak}
 * and {@code allowPlace} off, so the route can only ever be walked, never mined or bridged. And the
 * goal is a {@link GoalNear} sized to the interaction reach, so the bot stops as soon as it can
 * touch the target rather than trying to stand on it - clusters are not standable, and demanding an
 * exact block is what produced "No path found" for things standing right there.
 */
public final class Travel {

    public enum Status { BUSY, ARRIVED, FAILED }

    /** Path requests are async; re-issuing every tick starves the calculation and nothing moves. */
    private static final int REPATH_COOLDOWN_TICKS = 30;

    private boolean active;
    private int targetX, targetY, targetZ;
    private int ticks;
    private int repathCooldown;
    private int idleTicks;
    private String failReason = "";

    public boolean isActive() { return active; }
    public String failReason() { return failReason; }
    public int ticks() { return ticks; }

    public void begin(final int x, final int y, final int z) {
        stop();
        targetX = x;
        targetY = y;
        targetZ = z;
        ticks = 0;
        repathCooldown = 0;
        idleTicks = 0;
        failReason = "";
        active = true;
    }

    public void stop() {
        if (active && BARITONE.isActive()) BARITONE.stop();
        active = false;
        ticks = 0;
        repathCooldown = 0;
        idleTicks = 0;
    }

    /**
     * @param arriveDistance how close counts as arrived, in blocks from the eye to the block centre
     */
    public Status tick(final AutoAmethystConfig.Movement cfg, final double arriveDistance) {
        if (!active) return Status.ARRIVED;

        final double dist = distanceToTarget();
        if (dist <= arriveDistance) {
            stop();
            return Status.ARRIVED;
        }
        if (dist > cfg.maxTravelDistance) {
            return fail("target is " + (int) dist + " blocks away, past the travel limit");
        }
        if (++ticks > Math.max(100, cfg.legTimeoutTicks)) {
            return fail("could not get there in " + ticks + " ticks");
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (!BARITONE.isActive()) {
            // Not moving and not planning. Either we have not asked yet, or the last request came
            // back with no route. Ask again a bounded number of times, then give up on this target
            // rather than standing here - the caller blacklists it and moves to the next one.
            if (++idleTicks > 3) {
                return fail("no route (the pathfinder cannot reach it without breaking blocks)");
            }
            // rangeSq sized to interaction reach: stand near enough to touch it, not on it.
            final int rangeSq = Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2));
            BARITONE.pathTo(new GoalNear(new BlockPos(targetX, targetY, targetZ), rangeSq));
            repathCooldown = REPATH_COOLDOWN_TICKS;
        } else {
            idleTicks = 0;
        }
        return Status.BUSY;
    }

    private double distanceToTarget() {
        return MathHelper.distance3d(
            targetX + 0.5, targetY + 0.5, targetZ + 0.5,
            BOT.getX(), BOT.getEyeY(), BOT.getZ());
    }

    private Status fail(final String reason) {
        stop();
        // set after stop(), which clears state - the same trap that made every earlier failure
        // report an empty reason
        failReason = reason;
        return Status.FAILED;
    }
}
