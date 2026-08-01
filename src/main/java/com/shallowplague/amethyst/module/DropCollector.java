package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.block.BlockPos;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * Walks the bot onto dropped items so nothing is left on the ground.
 *
 * <p>Passive pickup is not enough on its own: a drop can fly a couple of blocks from where the
 * block broke, well outside the ~1 block vanilla pickup radius.
 *
 * <p><b>The pathfinder does the walking.</b> Every chase is a {@code GoalNear} on the item's block
 * and Baritone routes to it - around obstacles, up a ladder, down a level. An earlier version
 * hand-walked toward drops and only escalated to the pathfinder when that failed, which is how the
 * bot ended up stuck against half blocks and corners it could obviously have walked around.
 *
 * <p>Safe inside the rig only because {@link PathfinderGuard} has clamped {@code allowBreak} and
 * {@code allowPlace} off: it can route to a shard but can never mine its way there.
 *
 * <p>Drops it genuinely cannot reach are remembered, so the next scan does not pick the same one
 * and livelock on it. That matters because some shards do land where nothing can walk - a gap
 * behind the rig, a ledge with no standable block beside it.
 */
public final class DropCollector {

    public enum Status { CHASING, DONE, FAILED }

    private static final int REPATH_COOLDOWN_TICKS = 30;
    /** Vanilla item pickup reaches about a block; inside that the server hands it over unprompted. */
    private static final double PICKUP_RADIUS = 1.2;

    private @Nullable Integer targetEntityId;
    private int chaseTicks;
    private int repathCooldown;
    private int idleTicks;
    private int pathTargetX, pathTargetY, pathTargetZ;
    private boolean pathIssued;
    private String failReason = "";

    /** Item entity ids we could not reach. Bounded so a long AFK run cannot grow it forever. */
    private final Set<Integer> unreachable = new HashSet<>();

    public DropCollector() { }

    /** Owner is unused now that the pathfinder does the walking; kept so call sites read the same. */
    public DropCollector(final Object owner) { }

    public String failReason() { return failReason; }

    public void reset() {
        if (pathIssued && BARITONE.isActive()) BARITONE.stop();
        pathIssued = false;
        targetEntityId = null;
        chaseTicks = 0;
        repathCooldown = 0;
        idleTicks = 0;
        failReason = "";
    }

    public void clearUnreachable() {
        unreachable.clear();
    }

    /** True when a matching drop is worth walking to: outside pickup range, inside the leash. */
    public boolean hasWorkNear(final Predicate<ItemStack> wanted, final double anchorX,
                               final double anchorY, final double anchorZ, final double leash) {
        return findNearest(wanted, anchorX, anchorY, anchorZ, leash) != null;
    }

    /** Drives one tick. DONE when nothing reachable is left within the leash. */
    public Status tick(final Predicate<ItemStack> wanted, final AutoAmethystConfig.Collection cfg,
                       final double anchorX, final double anchorY, final double anchorZ) {
        final Entity target = resolve(wanted, anchorX, anchorY, anchorZ, cfg.maxDistance);
        if (target == null) {
            reset();
            return Status.DONE;
        }

        if (++chaseTicks > Math.max(60, cfg.chaseTimeoutTicks)) {
            return abandon("gave up after " + chaseTicks + " ticks");
        }

        final int bx = MathHelper.floorI(target.getX());
        final int by = MathHelper.floorI(target.getY());
        final int bz = MathHelper.floorI(target.getZ());

        if (repathCooldown > 0) {
            repathCooldown--;
            return Status.CHASING;
        }
        if (BARITONE.isActive()) {
            idleTicks = 0;
            // Follow the item if it has drifted to a different block since we asked.
            if (bx != pathTargetX || by != pathTargetY || bz != pathTargetZ) {
                issuePath(bx, by, bz);
            }
            return Status.CHASING;
        }

        // Not moving and not planning. Either we have not asked yet, or the pathfinder came back
        // with no route. A couple of retries, then write it off rather than standing here.
        if (pathIssued && ++idleTicks > 3) {
            return abandon("no route to it");
        }
        issuePath(bx, by, bz);
        return Status.CHASING;
    }

    private void issuePath(final int x, final int y, final int z) {
        BARITONE.pathTo(new GoalNear(new BlockPos(x, y, z), 1));
        pathTargetX = x;
        pathTargetY = y;
        pathTargetZ = z;
        pathIssued = true;
        repathCooldown = REPATH_COOLDOWN_TICKS;
    }

    /** Re-resolves each tick: items get pushed around, picked up, and despawn. */
    private @Nullable Entity resolve(final Predicate<ItemStack> wanted, final double anchorX,
                                     final double anchorY, final double anchorZ, final double leash) {
        if (targetEntityId != null) {
            final Entity existing = CACHE.getEntityCache().get(targetEntityId);
            if (existing != null && existing.getEntityType() == EntityType.ITEM
                && matches(existing, wanted)) {
                return existing;
            }
            targetEntityId = null;
            chaseTicks = 0;
            pathIssued = false;
        }
        final Entity next = findNearest(wanted, anchorX, anchorY, anchorZ, leash);
        if (next != null) {
            targetEntityId = next.getEntityId();
            chaseTicks = 0;
            idleTicks = 0;
            pathIssued = false;
            repathCooldown = 0;
        }
        return next;
    }

    private @Nullable Entity findNearest(final Predicate<ItemStack> wanted, final double anchorX,
                                         final double anchorY, final double anchorZ, final double leash) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        final double leashSq = leash * leash;
        final double pickupSq = PICKUP_RADIUS * PICKUP_RADIUS;
        for (final Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM) continue;
            if (unreachable.contains(entity.getEntityId())) continue;
            if (!matches(entity, wanted)) continue;
            final double fromAnchor = MathHelper.distanceSq3d(
                entity.getX(), entity.getY(), entity.getZ(), anchorX, anchorY, anchorZ);
            if (fromAnchor > leashSq) continue;
            final double fromSelf = MathHelper.distanceSq3d(
                entity.getX(), entity.getY(), entity.getZ(), BOT.getX(), BOT.getY(), BOT.getZ());
            if (fromSelf <= pickupSq) continue; // already close enough to be collected for free
            if (fromSelf < bestDist) {
                bestDist = fromSelf;
                best = entity;
            }
        }
        return best;
    }

    private static boolean matches(final Entity entity, final Predicate<ItemStack> wanted) {
        final ItemStack stack = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
        return stack != null && wanted.test(stack);
    }

    private Status abandon(final String reason) {
        if (targetEntityId != null) {
            unreachable.add(targetEntityId);
            if (unreachable.size() > 256) unreachable.clear();
        }
        reset();
        failReason = reason; // after reset, which clears it
        return Status.FAILED;
    }
}
