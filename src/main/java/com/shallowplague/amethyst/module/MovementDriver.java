package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.BlockTags;
import com.zenith.feature.player.World;
import com.zenith.util.math.MathHelper;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;

/**
 * Moves the bot between stand positions.
 *
 * <p>Three modes, none of which forge movement packets. STATIONARY never moves. WAYPOINT hands the
 * leg to Zenith's pathfinder. SCAFFOLD presses the ordinary vanilla movement inputs (forward, jump,
 * sneak) and lets Zenith's own physics simulation produce the position packets, which is the only
 * reason a hand driven scaffolding walk is viable at all: Zenith models scaffolding collision and
 * climbing faithfully (Bot#syncPlayerCollisionBox and Bot#applyMovementInput), it is only the path
 * <i>planner</i> that refuses to route through it.
 *
 * <p>A SCAFFOLD leg is three phases: walk to the shared vertical column, climb to the target Y,
 * then walk out to the target XZ. Any phase that stops making progress fails the leg rather than
 * grinding forever.
 */
public final class MovementDriver {

    public enum Mode { STATIONARY, WAYPOINT, SCAFFOLD }

    /** Result of one tick of driving. */
    public enum Status { BUSY, ARRIVED, FAILED }

    private enum Phase { NONE, PATHING, WALK_TO_COLUMN, CLIMB, WALK_TO_TARGET }

    /** Ticks a fresh pathing leg is given before its progress is judged. */
    private static final int PATH_GRACE_TICKS = 60;

    private final Object owner;
    private Phase phase = Phase.NONE;
    private int targetX, targetY, targetZ;
    private int legTicks;
    private int stuckCounter;
    private double lastX, lastY, lastZ;
    private String failReason = "";

    public MovementDriver(final Object owner) {
        this.owner = owner;
    }

    public String failReason() {
        return failReason;
    }

    public boolean isMoving() {
        return phase != Phase.NONE;
    }

    /** Parses a config mode string, falling back to STATIONARY on anything unrecognised. */
    public static Mode parseMode(final String raw) {
        if (raw == null) return Mode.STATIONARY;
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Mode.STATIONARY;
        }
    }

    public void reset() {
        if (phase == Phase.PATHING && BARITONE.isActive()) {
            BARITONE.stop();
        }
        phase = Phase.NONE;
        legTicks = 0;
        stuckCounter = 0;
        failReason = "";
    }

    /** Begins a leg to the given stand position. Must be followed by {@link #tick} each tick. */
    public void begin(final Mode mode, final int x, final int y, final int z) {
        reset();
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.legTicks = 0;
        this.stuckCounter = 0;
        this.failReason = "";
        snapshotPosition();
        switch (mode) {
            case WAYPOINT -> {
                phase = Phase.PATHING;
                BARITONE.pathTo(new GoalBlock(x, y, z));
            }
            case SCAFFOLD -> phase = Phase.WALK_TO_COLUMN;
            case STATIONARY -> phase = Phase.NONE;
        }
    }

    /**
     * Drives one tick of the current leg.
     *
     * <p>Returns ARRIVED once the bot is standing at the target, FAILED if the leg timed out, got
     * stuck, or left the climbable column mid-climb.
     */
    public Status tick(final Mode mode, final AutoAmethystConfig.Movement cfg, final int priority) {
        if (phase == Phase.NONE) return Status.ARRIVED;

        if (++legTicks > Math.max(20, cfg.legTimeoutTicks)) {
            return fail("leg timed out after " + legTicks + " ticks");
        }
        // The pathfinder calculates asynchronously, so for the first moment of a leg it is neither
        // active nor moving. Judging it during that window would fail every single leg instantly.
        final boolean settling = phase == Phase.PATHING && legTicks <= PATH_GRACE_TICKS;
        if (!settling && !trackProgress(Math.max(20, cfg.stuckTicks))) {
            return fail("no movement for " + stuckCounter + " ticks");
        }

        return switch (phase) {
            case PATHING -> tickPathing(cfg);
            case WALK_TO_COLUMN -> tickWalkToColumn(cfg, priority);
            case CLIMB -> tickClimb(cfg, priority);
            case WALK_TO_TARGET -> tickWalkToTarget(cfg, priority);
            case NONE -> Status.ARRIVED;
        };
    }

    // --- WAYPOINT ---

    private Status tickPathing(final AutoAmethystConfig.Movement cfg) {
        if (atTarget(cfg.arriveRadius)) {
            if (BARITONE.isActive()) BARITONE.stop();
            return arrive();
        }
        if (legTicks > PATH_GRACE_TICKS && !BARITONE.isActive()) {
            // pathfinder finished or gave up without putting us on the waypoint. On a scaffolding
            // rig this is the expected outcome - the path planner has no scaffolding movement.
            return fail("pathfinder stopped short of the waypoint"
                + " (scaffolding is not routable; use ladders or SCAFFOLD mode)");
        }
        return Status.BUSY;
    }

    // --- SCAFFOLD ---

    private Status tickWalkToColumn(final AutoAmethystConfig.Movement cfg, final int priority) {
        if (!cfg.columnSet) {
            // No column configured; only a same-level move is possible.
            if (Math.abs(CACHE.getPlayerCache().getY() - targetY) > 1.0) {
                return fail("vertical move requested but no scaffolding column is configured");
            }
            phase = Phase.WALK_TO_TARGET;
            resetLegProgress();
            return Status.BUSY;
        }
        if (Math.abs(CACHE.getPlayerCache().getY() - targetY) <= cfg.climbTolerance) {
            // already on the right level, no need to visit the column
            phase = Phase.WALK_TO_TARGET;
            resetLegProgress();
            return Status.BUSY;
        }
        if (horizontalDistanceTo(cfg.columnX, cfg.columnZ) <= cfg.arriveRadius) {
            phase = Phase.CLIMB;
            resetLegProgress();
            return Status.BUSY;
        }
        walkToward(cfg.columnX, cfg.columnZ, cfg.sneakWhileWalking, priority);
        return Status.BUSY;
    }

    private Status tickClimb(final AutoAmethystConfig.Movement cfg, final int priority) {
        final double y = CACHE.getPlayerCache().getY();
        final double dy = targetY - y;
        if (Math.abs(dy) <= cfg.climbTolerance) {
            phase = Phase.WALK_TO_TARGET;
            resetLegProgress();
            return Status.BUSY;
        }
        // Only ever climb while actually inside the climbable column. Holding jump in open air
        // would just bunny hop, and holding sneak off the edge of a platform would walk us off it.
        if (!inClimbable()) {
            return fail("left the climbable column mid-climb");
        }
        final Input input = dy > 0
            ? Input.builder().jumping(true).build()
            : Input.builder().sneaking(true).build();
        submit(input, priority, null);
        return Status.BUSY;
    }

    private Status tickWalkToTarget(final AutoAmethystConfig.Movement cfg, final int priority) {
        if (atTarget(cfg.arriveRadius)) return arrive();
        walkToward(targetX, targetZ, cfg.sneakWhileWalking, priority);
        return Status.BUSY;
    }

    // --- helpers ---

    /**
     * True when the block at the bot's feet can be climbed. Scaffolding carries the vanilla
     * climbable tag, so this covers ladders, vines and scaffolding alike.
     */
    private boolean inClimbable() {
        final int fx = MathHelper.floorI(CACHE.getPlayerCache().getX());
        final int fy = MathHelper.floorI(CACHE.getPlayerCache().getY());
        final int fz = MathHelper.floorI(CACHE.getPlayerCache().getZ());
        final Block feet = World.getBlock(fx, fy, fz);
        if (feet == BlockRegistry.SCAFFOLDING || feet.blockTags().contains(BlockTags.CLIMBABLE)) {
            return true;
        }
        // Standing on top of the column counts: sneaking sinks us into it, which is how a vanilla
        // client starts a descent.
        final Block below = World.getBlock(fx, fy - 1, fz);
        return below == BlockRegistry.SCAFFOLDING || below.blockTags().contains(BlockTags.CLIMBABLE);
    }

    /**
     * Walks toward an XZ target.
     *
     * <p>Sneaks by default. On a scaffolding rig that is worth far more than the speed it costs:
     * sneaking engages Zenith's port of vanilla ledge protection ({@code Bot#maybeBackOffFromEdge}),
     * so the bot cannot walk off the end of a platform, and the lower speed stops it overshooting
     * the stand position and oscillating around it until the leg times out. Growth takes hours per
     * cluster, so nothing here is in a hurry.
     */
    private void walkToward(final int x, final int z, final boolean sneak, final int priority) {
        final float yaw = RotationHelper.yawToXZ(x + 0.5, z + 0.5);
        submit(Input.builder().pressingForward(true).sneaking(sneak).build(), priority, yaw);
    }

    private void submit(final Input input, final int priority, final @Nullable Float yaw) {
        final InputRequest.Builder builder = InputRequest.builder()
            .owner(owner)
            .input(input)
            .priority(priority);
        if (yaw != null) builder.yaw(yaw);
        INPUTS.submit(builder.build());
    }

    private boolean atTarget(final double arriveRadius) {
        return horizontalDistanceTo(targetX, targetZ) <= arriveRadius
            && Math.abs(CACHE.getPlayerCache().getY() - targetY) <= 1.0;
    }

    private double horizontalDistanceTo(final int x, final int z) {
        final double dx = (x + 0.5) - CACHE.getPlayerCache().getX();
        final double dz = (z + 0.5) - CACHE.getPlayerCache().getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Returns false once the bot has failed to move for {@code limit} consecutive ticks. */
    private boolean trackProgress(final int limit) {
        final double x = CACHE.getPlayerCache().getX();
        final double y = CACHE.getPlayerCache().getY();
        final double z = CACHE.getPlayerCache().getZ();
        if (MathHelper.distanceSq3d(x, y, z, lastX, lastY, lastZ) < 0.0025) { // 0.05 blocks
            stuckCounter++;
        } else {
            stuckCounter = 0;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        return stuckCounter < limit;
    }

    private void snapshotPosition() {
        lastX = CACHE.getPlayerCache().getX();
        lastY = CACHE.getPlayerCache().getY();
        lastZ = CACHE.getPlayerCache().getZ();
    }

    private void resetLegProgress() {
        stuckCounter = 0;
        snapshotPosition();
    }

    private Status arrive() {
        phase = Phase.NONE;
        return Status.ARRIVED;
    }

    private Status fail(final String reason) {
        failReason = reason;
        reset();
        return Status.FAILED;
    }
}
