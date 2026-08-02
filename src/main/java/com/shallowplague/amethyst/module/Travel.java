package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.mc.block.BlockPos;
import com.zenith.util.math.MathHelper;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;

/**
 * Gets the bot to a place. Either "within reach of this block" or "standing in this exact cell".
 *
 * <p>Safe inside a geode because {@link PathfinderGuard} has clamped {@code allowBreak} and
 * {@code allowPlace} off, so a route can only ever be walked, never mined or bridged.
 *
 * <h2>Never trust {@code isActive()} alone</h2>
 * {@code Baritone.isActive()} is {@code goal != null || controlManager.isActive()}, so it cannot
 * distinguish "walking", "arrived", "gave up" or "never started". Two failure modes came from
 * leaning on it:
 *
 * <ul>
 *   <li><b>An already-satisfied goal is never searched for.</b>
 *       {@code PathingBehavior.secretInternalSetGoalAndPath} early-returns when the goal contains
 *       the player's feet, so re-issuing it does nothing and the bot stands still while this class
 *       counts the silence as "no route". Arrival is therefore also decided by asking the goal
 *       whether it contains our feet.</li>
 *   <li><b>A leg could hang for the full 600 tick timeout.</b> When the bot stands on budding
 *       amethyst - most of a geode floor - {@code canWalkOn} is false for that block, so
 *       {@code pathStart()} returns a neighbouring cell rather than the feet.
 *       {@code CustomGoalProcess} reports completion only when the goal contains BOTH the feet and
 *       {@code pathStart()}, so the two disagree, the process re-requests every tick, no search
 *       ever runs, and {@code isActive()} stays permanently true. So a leg must always have an
 *       exit that does not depend on the pathfinder ever going quiet.</li>
 * </ul>
 *
 * <h2>Why there is a hand-walked fallback</h2>
 * The planner will not stand on budding amethyst, but a vanilla client walks over it perfectly
 * happily - it is an ordinary full cube. So when the pathfinder reports no route to a stand cell,
 * the bot walks at it directly with ordinary movement inputs, letting Zenith's own physics produce
 * the position packets. This is bounded to a few seconds with a stuck detector; it is not the
 * open-ended hand walking that used to wedge the bot against corners.
 *
 * <p>{@code ARRIVED} is not a claim of success. It means "nothing further is going to move us";
 * the caller decides whether the target is actually workable from there.
 */
public final class Travel {

    public enum Status { BUSY, ARRIVED, FAILED }

    /** Path requests are async; re-issuing every tick starves the calculation and nothing moves. */
    private static final int REPATH_COOLDOWN_TICKS = 30;
    /** Hard budget for the hand-walked fallback. Monotonic. */
    private static final int DIRECT_BUDGET_TICKS = 70;
    /** Ticks of no movement during a hand walk before calling it blocked. */
    private static final int DIRECT_STUCK_TICKS = 12;
    private static final double MOVED_EPSILON_SQ = 0.0025; // 0.05 blocks

    private final Object owner;

    private boolean active;
    private int targetX, targetY, targetZ;
    /** True when the target is a cell to stand IN, false when it is a block to stand NEAR. */
    private boolean standMode;
    private int ticks;
    private int repathCooldown;
    private int idleTicks;
    private boolean direct;
    private int directTicks;
    private int directStuck;
    private double lastX, lastY, lastZ;
    private @Nullable Goal currentGoal;
    private String failReason = "";

    public Travel(final Object owner) {
        this.owner = owner;
    }

    public boolean isActive() { return active; }
    public String failReason() { return failReason; }
    public int ticks() { return ticks; }
    public boolean isWalkingByHand() { return direct; }

    /** Get within reach of a block. */
    public void begin(final int x, final int y, final int z) {
        reset(x, y, z);
        standMode = false;
        active = true;
    }

    /** Stand in this exact cell, hand-walking the last stretch if the planner will not route there. */
    public void beginStand(final int x, final int y, final int z) {
        reset(x, y, z);
        standMode = true;
        active = true;
    }

    private void reset(final int x, final int y, final int z) {
        stop();
        targetX = x;
        targetY = y;
        targetZ = z;
        ticks = 0;
        repathCooldown = 0;
        idleTicks = 0;
        direct = false;
        directTicks = 0;
        directStuck = 0;
        currentGoal = null;
        failReason = "";
    }

    public void stop() {
        if (active && BARITONE.isActive()) BARITONE.stop();
        active = false;
        ticks = 0;
        repathCooldown = 0;
        idleTicks = 0;
        currentGoal = null;
    }

    /**
     * @param arriveDistance how close counts as arrived, in blocks
     * @param priority       input priority for the hand-walked fallback
     */
    public Status tick(final AutoAmethystConfig.Movement cfg, final double arriveDistance,
                       final int priority) {
        if (!active) return Status.ARRIVED;

        if (arrived(arriveDistance)) {
            stop();
            return Status.ARRIVED;
        }
        // If the goal already contains our feet, the pathfinder will not move us again however busy
        // it looks, because an already-satisfied goal is never searched for.
        if (!direct && currentGoal != null && currentGoal.isInGoal(feetX(), feetY(), feetZ())) {
            stop();
            return Status.ARRIVED;
        }
        if (distanceToTarget() > cfg.maxTravelDistance) {
            return fail("target is " + (int) distanceToTarget() + " blocks away, past the travel limit");
        }
        if (++ticks > Math.max(100, cfg.legTimeoutTicks)) {
            return fail("could not get there in " + ticks + " ticks");
        }

        if (direct) return walkDirectly(cfg, priority);

        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (!BARITONE.isActive()) {
            // Not moving and not planning: either we have not asked yet, or there is no route.
            if (++idleTicks > 3) {
                if (standMode && !direct) {
                    // The planner will not route here - almost always because the floor is budding
                    // amethyst, which it refuses to stand on. Walk at it instead.
                    beginDirect();
                    return Status.BUSY;
                }
                return fail("no route (the pathfinder cannot reach it without breaking blocks)");
            }
            currentGoal = goalForLeg();
            BARITONE.pathTo(currentGoal);
            repathCooldown = REPATH_COOLDOWN_TICKS;
        } else {
            idleTicks = 0;
        }
        return Status.BUSY;
    }

    private Goal goalForLeg() {
        final BlockPos pos = new BlockPos(targetX, targetY, targetZ);
        // A stand cell is a place to BE, so it is an exact block goal; a cluster is a thing to
        // reach, so rangeSq is sized to interaction reach - near enough to touch it, not stand on
        // it, which clusters do not permit anyway.
        return standMode
            ? new GoalBlock(pos)
            : new GoalNear(pos, Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2)));
    }

    private void beginDirect() {
        if (BARITONE.isActive()) BARITONE.stop();
        direct = true;
        directTicks = 0;
        directStuck = 0;
        markPosition();
    }

    /** Ordinary forward input toward the cell. No forged packets - Zenith's physics does the rest. */
    private Status walkDirectly(final AutoAmethystConfig.Movement cfg, final int priority) {
        if (MathHelper.distanceSq3d(BOT.getX(), BOT.getY(), BOT.getZ(), lastX, lastY, lastZ)
            < MOVED_EPSILON_SQ) {
            directStuck++;
        } else {
            directStuck = 0;
        }
        markPosition();

        if (++directTicks > DIRECT_BUDGET_TICKS || directStuck > DIRECT_STUCK_TICKS) {
            stop();
            return Status.ARRIVED; // as close as hand walking will get it; caller re-tests sight
        }

        final double dx = (targetX + 0.5) - BOT.getX();
        final double dz = (targetZ + 0.5) - BOT.getZ();
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        INPUTS.submit(InputRequest.builder()
            .owner(owner)
            .priority(priority)
            .yaw(RotationHelper.yawToXZ(targetX + 0.5, targetZ + 0.5))
            .input(Input.builder()
                .pressingForward(horizontal > 0.2)
                .sprinting(cfg.sprint && horizontal > 2.0)
                // Step up onto the cell, or hop when shouldered into something.
                .jumping(targetY > feetY() || directStuck >= 6)
                .build())
            .build());
        return Status.BUSY;
    }

    private boolean arrived(final double arriveDistance) {
        if (standMode) {
            // Standing in the cell, or close enough that the caller's own reach test will decide.
            return (feetX() == targetX && feetZ() == targetZ && Math.abs(feetY() - targetY) <= 1)
                || horizontalDistanceToTarget() <= 0.6;
        }
        return distanceToTarget() <= arriveDistance;
    }

    private void markPosition() {
        lastX = BOT.getX();
        lastY = BOT.getY();
        lastZ = BOT.getZ();
    }

    private static int feetX() { return MathHelper.floorI(BOT.getX()); }
    private static int feetY() { return MathHelper.floorI(BOT.getY()); }
    private static int feetZ() { return MathHelper.floorI(BOT.getZ()); }

    private double distanceToTarget() {
        if (standMode) {
            return MathHelper.distance3d(
                targetX + 0.5, targetY + 0.5, targetZ + 0.5, BOT.getX(), BOT.getY(), BOT.getZ());
        }
        return MathHelper.distance3d(
            targetX + 0.5, targetY + 0.5, targetZ + 0.5, BOT.getX(), BOT.getEyeY(), BOT.getZ());
    }

    private double horizontalDistanceToTarget() {
        final double dx = (targetX + 0.5) - BOT.getX();
        final double dz = (targetZ + 0.5) - BOT.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Status fail(final String reason) {
        stop();
        // set after stop(), which clears state - the same trap that made every earlier failure
        // report an empty reason
        failReason = reason;
        return Status.FAILED;
    }
}
