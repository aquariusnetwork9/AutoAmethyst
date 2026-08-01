package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalComposite;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.mc.block.BlockPos;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;

/**
 * Sweeps the geode for dropped shards and gets the bot onto them.
 *
 * <h2>Sweep, not leash</h2>
 * Every tick this looks at the whole geode box (grown by a margin, because a break throws its drop
 * a block or two and shards then fall) and counts what is on the ground. There is no radius around
 * the bot. An earlier version leashed chases to six blocks measured from wherever the bot happened
 * to be standing, and that is a three-dimensional distance - so a shard that fell to the bottom
 * level was outside the leash on the vertical alone, and the bot ignored it without a word. The box
 * is the farm; a shard in the box gets collected.
 *
 * <h2>Three ways to reach one, in order</h2>
 * <ol>
 *   <li><b>Stand on it.</b> A {@link GoalBlock} on the drop's own block, for every drop at once as
 *       a {@link GoalComposite} so the pathfinder walks to the nearest one it can actually reach.
 *       This is first because standing on top of a drop is the only thing that reliably makes 2b2t
 *       hand the item over - being merely adjacent often is not enough.</li>
 *   <li><b>Stand beside it.</b> {@link GoalPickup}, used only once the exact goal has proven to
 *       have no route. Most of a geode floor is budding amethyst and the pathfinder will not stand
 *       on that, so for bottom-level shards this is as close as it can plan.</li>
 *   <li><b>Walk the last block by hand.</b> Ordinary forward input aimed at the drop. This is what
 *       finishes the job: a vanilla client walks across budding amethyst perfectly happily, it is
 *       only the path <i>planner</i> that refuses, so once the bot is beside the shard a couple of
 *       ticks of walking put it on top.</li>
 * </ol>
 * The hand walk is deliberately tiny - a few blocks, with a timeout and a stuck detector - and is
 * nothing like the open-ended hand walking that used to get the bot wedged against half blocks.
 *
 * <h2>Nothing is written off permanently</h2>
 * A drop that cannot be reached goes on a timed cooldown and is tried again later. The previous
 * version put it in a set cleared only by a successful deposit, so a single transient "no path" -
 * which the pathfinder returns instantly when the goal is unstandable - meant that shard was
 * ignored for the rest of the run.
 */
public final class DropCollector {

    public enum Status { CHASING, DONE, FAILED }

    private enum Mode { NONE, PATH_EXACT, PATH_NEAR, NUDGE }

    /** Minimum ticks between handing the pathfinder a new goal, so a bouncing item cannot thrash it. */
    private static final int REISSUE_COOLDOWN_TICKS = 10;
    /** Movement below this in a tick counts as not having moved at all. */
    private static final double MOVED_EPSILON_SQ = 0.0025; // 0.05 blocks

    /** Region searched for drops: the geode box, already grown by the configured margin. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(final double x, final double y, final double z) {
            return x >= minX && x < maxX + 1
                && y >= minY && y < maxY + 1
                && z >= minZ && z < maxZ + 1;
        }
    }

    private record Drop(int id, int x, int y, int z,
                        double ex, double ey, double ez,
                        int amount, double distSq) { }

    private final Object owner;
    private final List<Drop> visible = new ArrayList<>();
    /** entity id -> tick at which it may be chased again at all. */
    private final Map<Integer, Long> cooldown = new HashMap<>();
    /** entity id -> tick at which walking straight at it may be tried again. */
    private final Map<Integer, Long> nudgeBlocked = new HashMap<>();

    private Mode mode = Mode.NONE;
    private long issuedSignature = 0;
    private int reissueCooldown = 0;
    private int chaseTicks = 0;
    private int idleTicks = 0;
    private int nudgeTicks = 0;
    private int nudgeStuck = 0;
    private int nudgeTarget = -1;
    private double lastX, lastY, lastZ;
    private int lastItems = -1;

    private int groundStacks = 0;
    private int groundItems = 0;
    private int stranded = 0;
    private int truncated = 0;
    private String failReason = "";

    public DropCollector(final Object owner) {
        this.owner = owner;
    }

    public String failReason() { return failReason; }

    /** Item entities on the ground in the box right now, excluding ones on cooldown. */
    public int groundStacks() { return groundStacks; }

    /** Total shards on the ground in the box right now, excluding ones on cooldown. */
    public int groundItems() { return groundItems; }

    /** Drops in the box currently on the retry cooldown, so not being chased. */
    public int strandedStacks() { return stranded; }

    /** Drops left out of the current goal because the batch cap was hit. */
    public int truncated() { return truncated; }

    public String modeName() { return mode.name(); }

    public void reset() {
        stopAll();
        lastItems = -1;
        failReason = "";
    }

    /** Forgets every cooldown, so previously unreachable drops are tried again immediately. */
    public void clearCooldowns() {
        cooldown.clear();
        nudgeBlocked.clear();
    }

    /**
     * Sweeps and reports whether anything is worth walking to. Also refreshes the ground counts, so
     * calling this from the idle loop keeps the status output current.
     */
    public boolean hasWork(final Predicate<ItemStack> wanted, final Bounds bounds, final long tick) {
        sweep(wanted, bounds, tick);
        return !visible.isEmpty();
    }

    /** Drives one tick of collection. */
    public Status tick(final Predicate<ItemStack> wanted, final AutoAmethystConfig.Collection cfg,
                       final Bounds bounds, final long tick, final int priority, final boolean sprint) {
        sweep(wanted, bounds, tick);

        if (visible.isEmpty()) {
            final int left = stranded;
            stopAll();
            lastItems = -1;
            if (left > 0) {
                failReason = left + " drop(s) still out of reach, retrying within "
                    + (Math.max(20, cfg.retryCooldownTicks) / 20) + "s";
                return Status.FAILED;
            }
            return Status.DONE;
        }

        // Any reduction in what is on the floor means the trip is working, whichever drop it was.
        if (lastItems >= 0 && groundItems < lastItems) {
            chaseTicks = 0;
            idleTicks = 0;
            nudgeTicks = 0;
            nudgeStuck = 0;
        }
        lastItems = groundItems;

        truncated = Math.max(0, visible.size() - Math.max(1, cfg.maxGoalsPerSweep));

        // Nearest one we can simply walk at - not necessarily the nearest one overall, since the
        // closest drop may be the one a direct walk has already failed on.
        Drop walkable = null;
        for (final Drop d : visible) {
            if (withinNudgeRange(d, cfg)) {
                walkable = d;
                break;
            }
        }
        if (walkable != null) {
            return nudge(walkable, cfg, tick, priority, sprint);
        }
        if (mode == Mode.NUDGE) {
            mode = Mode.NONE;
            nudgeTarget = -1;
        }
        return path(cfg, tick);
    }

    // ------------------------------------------------------------------ walking straight at it

    private boolean withinNudgeRange(final Drop d, final AutoAmethystConfig.Collection cfg) {
        if (nudgeBlocked.containsKey(d.id())) return false;
        final double dx = d.ex() - BOT.getX();
        final double dz = d.ez() - BOT.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > Math.max(1.0, cfg.nudgeRadius)) return false;
        return Math.abs(d.ey() - BOT.getY()) <= Math.max(0.5, cfg.nudgeHeight);
    }

    /**
     * Presses forward toward the drop. No forged packets - this is the same input a player holding
     * W produces, and Zenith's own physics turns it into position updates.
     */
    private Status nudge(final Drop d, final AutoAmethystConfig.Collection cfg, final long tick,
                         final int priority, final boolean sprint) {
        final double dx = d.ex() - BOT.getX();
        final double dz = d.ez() - BOT.getZ();
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Close enough that walking further would only carry us past it. Stand still and let the
        // server hand it over.
        final boolean standingOnIt = horizontal <= 0.15;

        if (mode != Mode.NUDGE || nudgeTarget != d.id()) {
            stopPath();
            mode = Mode.NUDGE;
            nudgeTarget = d.id();
            nudgeTicks = 0;
            nudgeStuck = 0;
            markPosition();
        } else {
            // Only count stalled ticks while actually trying to move. Standing on the drop waiting
            // for the server to notice is not being stuck, and counting it that way wrote the drop
            // off in half a second.
            if (!standingOnIt
                && MathHelper.distanceSq3d(BOT.getX(), BOT.getY(), BOT.getZ(), lastX, lastY, lastZ)
                   < MOVED_EPSILON_SQ) {
                nudgeStuck++;
            } else {
                nudgeStuck = 0;
            }
            markPosition();
        }

        if (++nudgeTicks > Math.max(10, cfg.nudgeTimeoutTicks)
            || nudgeStuck > Math.max(4, cfg.nudgeStuckTicks)) {
            // Cannot simply walk at it: a wall in the way, a ledge, a shard wedged somewhere. Hand
            // it back to the pathfinder for a while rather than grinding into the obstacle.
            nudgeBlocked.put(d.id(), tick + Math.max(20, cfg.nudgeRetryTicks));
            mode = Mode.NONE;
            nudgeTarget = -1;
            return Status.CHASING;
        }

        INPUTS.submit(InputRequest.builder()
            .owner(owner)
            .priority(priority)
            .yaw(RotationHelper.yawToXZ(d.ex(), d.ez()))
            .input(Input.builder()
                .pressingForward(!standingOnIt)
                // Sprinting over a block or two only overshoots and oscillates.
                .sprinting(sprint && horizontal > 2.0)
                // Up a step, or shoulder-first into something that a hop clears.
                .jumping(d.ey() - BOT.getY() > 0.55 || nudgeStuck >= 6)
                .build())
            .build());
        return Status.CHASING;
    }

    private void markPosition() {
        lastX = BOT.getX();
        lastY = BOT.getY();
        lastZ = BOT.getZ();
    }

    // ------------------------------------------------------------------ pathfinder

    private Status path(final AutoAmethystConfig.Collection cfg, final long tick) {
        final int n = Math.min(Math.max(1, cfg.maxGoalsPerSweep), visible.size());
        final long signature = signature(n);
        if (reissueCooldown > 0) reissueCooldown--;

        if (mode != Mode.PATH_EXACT && mode != Mode.PATH_NEAR) {
            mode = Mode.PATH_EXACT;
            chaseTicks = 0;
            issueGoal(n, signature, true);
            return Status.CHASING;
        }
        if (signature != issuedSignature && reissueCooldown <= 0) {
            issueGoal(n, signature, mode == Mode.PATH_EXACT);
            return Status.CHASING;
        }

        if (BARITONE.isActive()) {
            idleTicks = 0;
            if (++chaseTicks > Math.max(60, cfg.chaseTimeoutTicks)) {
                // Walking, but nothing collected for a long time. Park whichever drop it was most
                // likely heading for and let the rest of the batch carry on.
                cooldown.put(visible.get(0).id(), tick + Math.max(20, cfg.retryCooldownTicks));
                stopPath();
                return Status.CHASING;
            }
            return Status.CHASING;
        }

        // Standing still with a goal set: either the route is still being computed, or there is no
        // route at all - which comes back immediately when every goal is unstandable.
        if (++idleTicks > Math.max(10, cfg.pathGiveUpTicks)) {
            if (mode == Mode.PATH_EXACT) {
                // Nothing can be stood on. Settle for getting beside them and let the hand walk
                // finish it off.
                mode = Mode.PATH_NEAR;
                issueGoal(n, signature, false);
                return Status.CHASING;
            }
            final long until = tick + Math.max(20, cfg.retryCooldownTicks);
            for (int i = 0; i < n; i++) cooldown.put(visible.get(i).id(), until);
            stopPath();
            failReason = "no route to " + n + " drop(s), retrying within "
                + (Math.max(20, cfg.retryCooldownTicks) / 20) + "s";
            return Status.FAILED;
        }
        return Status.CHASING;
    }

    private void issueGoal(final int n, final long signature, final boolean exact) {
        final Goal[] goals = new Goal[n];
        for (int i = 0; i < n; i++) {
            final Drop d = visible.get(i);
            goals[i] = exact
                ? new GoalBlock(new BlockPos(d.x(), d.y(), d.z()))
                : new GoalPickup(d.x(), d.y(), d.z());
        }
        BARITONE.pathTo(n == 1 ? goals[0] : new GoalComposite(goals));
        issuedSignature = signature;
        reissueCooldown = REISSUE_COOLDOWN_TICKS;
        idleTicks = 0;
    }

    /** Identity of the batch handed to the pathfinder, so a change to it forces a re-issue. */
    private long signature(final int n) {
        long h = 1125899906842597L;
        for (int i = 0; i < n; i++) {
            final Drop d = visible.get(i);
            h = h * 31 + d.id();
            h = h * 31 + d.x();
            h = h * 31 + d.y();
            h = h * 31 + d.z();
        }
        return h;
    }

    private void stopPath() {
        if (mode == Mode.PATH_EXACT || mode == Mode.PATH_NEAR) {
            if (BARITONE.isActive()) BARITONE.stop();
        }
        mode = Mode.NONE;
        issuedSignature = 0;
        reissueCooldown = 0;
        chaseTicks = 0;
        idleTicks = 0;
    }

    private void stopAll() {
        stopPath();
        nudgeTarget = -1;
        nudgeTicks = 0;
        nudgeStuck = 0;
    }

    // ------------------------------------------------------------------ sweep

    /** Rebuilds {@link #visible} from the entity cache, nearest first. */
    private void sweep(final Predicate<ItemStack> wanted, final Bounds bounds, final long tick) {
        cooldown.values().removeIf(until -> tick >= until);
        nudgeBlocked.values().removeIf(until -> tick >= until);
        visible.clear();
        stranded = 0;
        int items = 0;
        final double px = BOT.getX(), py = BOT.getY(), pz = BOT.getZ();

        for (final Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM) continue;
            final ItemStack stack = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            if (stack == null || !wanted.test(stack)) continue;
            final double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            if (!bounds.contains(x, y, z)) continue;
            if (cooldown.containsKey(entity.getEntityId())) {
                stranded++;
                continue;
            }
            visible.add(new Drop(entity.getEntityId(),
                MathHelper.floorI(x), MathHelper.floorI(y), MathHelper.floorI(z),
                x, y, z,
                stack.getAmount(), MathHelper.distanceSq3d(x, y, z, px, py, pz)));
            items += stack.getAmount();
        }

        visible.sort(Comparator.comparingDouble(Drop::distSq));
        groundStacks = visible.size();
        groundItems = items;
    }
}
