package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;

/**
 * Walks the bot onto dropped items so nothing is left on the ground.
 *
 * <p>Passive pickup is not enough. A broken block's drop can fly a couple of blocks from where it
 * was broken, which is well outside the ~1 block vanilla pickup radius, so a bot that never moves
 * would steadily leak shards onto the floor to despawn five minutes later.
 *
 * <p>Movement is a short sneaking walk driven by ordinary vanilla inputs, never the pathfinder.
 * That is deliberate: the pathfinder is entitled to decide that the way to reach something is to
 * mine through it, and inside the rig that is exactly what must never happen. A hand walk can only
 * ever move the bot. Everything stays inside {@code maxDistance} of the anchor, and the bot walks
 * back to the anchor afterwards so it does not drift off its stand position over hours of running.
 */
public final class DropCollector {

    public enum Status { IDLE, CHASING, RETURNING, DONE, FAILED }

    private final Object owner;

    private @Nullable Integer targetEntityId;
    private int chaseTicks;
    private int stuckTicks;
    private double lastX, lastY, lastZ;
    private String failReason = "";

    /**
     * Drops we could not get to. Without this, a shard that lands somewhere unwalkable - inside the
     * rig, on a ledge, behind a block - is re-found by the very next scan and chased again, so the
     * bot livelocks chasing and failing on the same item instead of harvesting.
     */
    private final Set<Integer> unreachable = new HashSet<>();

    public DropCollector(final Object owner) {
        this.owner = owner;
    }

    public String failReason() {
        return failReason;
    }

    public void reset() {
        targetEntityId = null;
        chaseTicks = 0;
        stuckTicks = 0;
        failReason = "";
    }

    /** Clears the unreachable list, e.g. after the bot moves to a new stand position. */
    public void clearUnreachable() {
        unreachable.clear();
    }

    /**
     * Writes this drop off and stops chasing it. Reported as FAILED so the caller can log it, but
     * the entity is remembered so the next scan does not immediately pick it again.
     */
    private Status abandonCurrent(final String reason) {
        failReason = reason;
        if (targetEntityId != null) {
            unreachable.add(targetEntityId);
            // Bound it. Item entity ids are never reused within a session, but a long AFK run
            // should not accumulate an unbounded set.
            if (unreachable.size() > 256) unreachable.clear();
        }
        targetEntityId = null;
        chaseTicks = 0;
        stuckTicks = 0;
        return Status.FAILED;
    }

    /**
     * True when there is a matching drop worth walking to: outside the pickup radius but inside the
     * allowed wander distance of the anchor.
     */
    public boolean hasWorkNear(final Predicate<ItemStack> wanted, final double anchorX,
                               final double anchorY, final double anchorZ, final double maxDistance) {
        return findNearestDrop(wanted, anchorX, anchorY, anchorZ, maxDistance) != null;
    }

    /**
     * Drives one tick of collection. Returns DONE when there is nothing left to chase within range.
     */
    public Status tick(final Predicate<ItemStack> wanted, final AutoAmethystConfig.Collection cfg,
                       final double anchorX, final double anchorY, final double anchorZ,
                       final int priority) {
        final Entity target = resolveTarget(wanted, anchorX, anchorY, anchorZ, cfg.maxDistance);
        if (target == null) {
            targetEntityId = null;
            return Status.DONE;
        }

        if (++chaseTicks > Math.max(20, cfg.chaseTimeoutTicks)) {
            return abandonCurrent("gave up chasing a drop after " + chaseTicks + " ticks");
        }
        if (!trackProgress(Math.max(20, cfg.stuckTicks))) {
            return abandonCurrent("stopped moving while chasing a drop");
        }

        // Refuse to step outside the leash even if the item drifts. Better to leave one shard than
        // to wander out of the rig.
        final double distFromAnchor = MathHelper.distance3d(
            target.getX(), target.getY(), target.getZ(), anchorX, anchorY, anchorZ);
        if (distFromAnchor > cfg.maxDistance) {
            targetEntityId = null;
            chaseTicks = 0;
            return Status.CHASING;
        }

        walkToward(target.getX(), target.getZ(), cfg.sneakWhileCollecting, priority);
        return Status.CHASING;
    }

    /** Walks back to the anchor. Returns DONE once within {@code tolerance} horizontally. */
    public Status tickReturn(final double anchorX, final double anchorZ, final double tolerance,
                             final boolean sneak, final int priority, final int stuckLimit) {
        if (horizontalDistance(anchorX, anchorZ) <= tolerance) {
            reset();
            return Status.DONE;
        }
        if (!trackProgress(Math.max(20, stuckLimit))) {
            failReason = "stopped moving while returning to the stand position";
            reset();
            return Status.FAILED;
        }
        // hasWorkNear also consults the unreachable set, so a returning bot cannot be re-triggered
        // by the same item it just gave up on
        walkToward(anchorX, anchorZ, sneak, priority);
        return Status.RETURNING;
    }

    /** Re-resolves the tracked entity each tick; items are pushed around and can be picked up. */
    private @Nullable Entity resolveTarget(final Predicate<ItemStack> wanted, final double anchorX,
                                           final double anchorY, final double anchorZ,
                                           final double maxDistance) {
        if (targetEntityId != null) {
            final Entity existing = CACHE.getEntityCache().get(targetEntityId);
            if (existing != null && existing.getEntityType() == EntityType.ITEM
                && matches(existing, wanted)) {
                return existing;
            }
            // it despawned, was collected, or changed identity: pick a new one
            targetEntityId = null;
            chaseTicks = 0;
        }
        final Entity next = findNearestDrop(wanted, anchorX, anchorY, anchorZ, maxDistance);
        if (next != null) {
            targetEntityId = next.getEntityId();
            chaseTicks = 0;
            snapshotPosition();
        }
        return next;
    }

    private @Nullable Entity findNearestDrop(final Predicate<ItemStack> wanted, final double anchorX,
                                             final double anchorY, final double anchorZ,
                                             final double maxDistance) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        final double maxSq = maxDistance * maxDistance;
        for (final Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM) continue;
            if (unreachable.contains(entity.getEntityId())) continue;
            if (!matches(entity, wanted)) continue;
            final double anchorDistSq = MathHelper.distanceSq3d(
                entity.getX(), entity.getY(), entity.getZ(), anchorX, anchorY, anchorZ);
            if (anchorDistSq > maxSq) continue;
            final double selfDistSq = MathHelper.distanceSq3d(
                entity.getX(), entity.getY(), entity.getZ(),
                CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ());
            // already close enough that the server will hand it over without us moving
            if (selfDistSq <= PICKUP_RADIUS_SQ) continue;
            if (selfDistSq < bestDist) {
                bestDist = selfDistSq;
                best = entity;
            }
        }
        return best;
    }

    /** Vanilla item pickup reaches about one block; treat anything inside that as already ours. */
    private static final double PICKUP_RADIUS_SQ = 1.2 * 1.2;

    private static boolean matches(final Entity entity, final Predicate<ItemStack> wanted) {
        final ItemStack stack = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
        return stack != null && wanted.test(stack);
    }

    private void walkToward(final double x, final double z, final boolean sneak, final int priority) {
        final float yaw = RotationHelper.yawToXZ(x, z);
        INPUTS.submit(InputRequest.builder()
            .owner(owner)
            .input(Input.builder().pressingForward(true).sneaking(sneak).build())
            .yaw(yaw)
            .priority(priority)
            .build());
    }

    private double horizontalDistance(final double x, final double z) {
        final double dx = x - CACHE.getPlayerCache().getX();
        final double dz = z - CACHE.getPlayerCache().getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean trackProgress(final int limit) {
        final double x = CACHE.getPlayerCache().getX();
        final double y = CACHE.getPlayerCache().getY();
        final double z = CACHE.getPlayerCache().getZ();
        if (MathHelper.distanceSq3d(x, y, z, lastX, lastY, lastZ) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        return stuckTicks < limit;
    }

    private void snapshotPosition() {
        lastX = CACHE.getPlayerCache().getX();
        lastY = CACHE.getPlayerCache().getY();
        lastZ = CACHE.getPlayerCache().getZ();
        stuckTicks = 0;
    }
}
