package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
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
    private boolean tightened;
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
        tightened = false;
        failReason = "";
        active = true;
    }

    /**
     * Asks for a closer stand position after arriving without a clean shot at the target.
     *
     * <p>The first goal is a {@link GoalNear} sized to interaction reach, and that range is a
     * <b>three-dimensional</b> distance - so standing one floor below a cluster satisfies it
     * completely. The bot then has the cluster in range but a ceiling in the way, which with line of
     * sight required means it can never break it, and without line of sight required means it breaks
     * it through the floor and the shards land on a level it has no route to. Either way the answer
     * is to go up and stand next to the thing, which is what {@link GoalGetToBlock} demands.
     *
     * @return false if this leg has already been tightened, so the caller should give up on it
     */
    public boolean tighten() {
        // Deliberately does NOT require the leg to still be active. It is called straight after a
        // tick returned ARRIVED, and arriving calls stop(), which clears that flag - so checking it
        // here made tighten() refuse every single time and the caller skipped the cluster within a
        // couple of seconds. The target is still held, so the leg can simply be re-armed.
        if (tightened) return false;
        tightened = true;
        if (BARITONE.isActive()) BARITONE.stop();
        ticks = 0;
        repathCooldown = 0;
        idleTicks = 0;
        active = true;
        return true;
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
        // Once tightened, being merely within reach is what already failed, so distance alone no
        // longer counts as arrival - the caller's line-of-sight test decides, and the pathfinder is
        // left to finish getting beside the block.
        if (dist <= arriveDistance && !tightened) {
            stop();
            return Status.ARRIVED;
        }
        if (tightened && !BARITONE.isActive() && repathCooldown <= 0 && idleTicks > 3) {
            stop();
            return Status.ARRIVED; // as close as it is going to get
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
            if (++idleTicks > 3 && !tightened) {
                return fail("no route (the pathfinder cannot reach it without breaking blocks)");
            }
            if (tightened) {
                // Get beside the block, on its own level, rather than merely within reach of it.
                BARITONE.pathTo(new GoalGetToBlock(new BlockPos(targetX, targetY, targetZ)));
            } else {
                // rangeSq sized to interaction reach: stand near enough to touch it, not on it.
                final int rangeSq = Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2));
                BARITONE.pathTo(new GoalNear(new BlockPos(targetX, targetY, targetZ), rangeSq));
            }
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
