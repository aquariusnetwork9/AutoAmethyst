package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.RotationHelper;
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
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;

/**
 * Walks the bot onto dropped items so nothing is left on the ground.
 *
 * <p>Passive pickup is not enough. A broken block's drop can fly a couple of blocks from where it
 * was broken, which is well outside the ~1 block vanilla pickup radius, so a bot that never moves
 * would steadily leak shards onto the floor to despawn five minutes later.
 *
 * <p>Two ways to move. A short hand walk on ordinary vanilla inputs for anything nearby, and the
 * pathfinder for drops it cannot simply walk onto - another level of the rig, the far side of a
 * wall, anywhere needing a ladder. Handing those to the pathfinder is only safe because {@link
 * PathfinderGuard} has clamped its ability to break and place: it can route to a shard but can
 * never mine its way there, which inside a geode is the difference between a collector and a
 * demolition crew.
 *
 * <p>Everything stays inside a leash from the stand position, and the bot walks back afterwards, so
 * it cannot drift off station over hours of running. Drops it fails to reach are remembered so the
 * next scan does not immediately pick the same one and livelock on it.
 */
public final class DropCollector {

    public enum Status { IDLE, CHASING, RETURNING, DONE, FAILED }

    private final Object owner;

    /**
     * Ticks between path requests. Path calculation is async, so re-issuing every tick stalls it -
     * and dropped items drift, so a short cooldown had the bot recalculating a path every second
     * and filling the log with it. A shard is not going anywhere; it can wait two seconds.
     */
    private static final int REPATH_COOLDOWN_TICKS = 40;

    private @Nullable Integer targetEntityId;
    private int chaseTicks;
    private int stuckTicks;
    private double lastX, lastY, lastZ;
    private String failReason = "";

    /** True while the pathfinder is driving the chase, so we know to stop it and to stay off inputs. */
    private boolean pathing;
    private int repathCooldown;
    private int pathTargetX, pathTargetY, pathTargetZ;
    /** Consecutive ticks with a path requested but not running - the "no route" signature. */
    private int pathIdleTicks;
    /** Consecutive ticks where the distance to the target has not shrunk. */
    private int noProgressTicks;
    private double lastDistToTarget = -1;
    /** Latches once this target has been handed to the pathfinder, to stop the decision flapping. */
    private boolean escalated;
    /** Latches when the pathfinder refused this target, so the hand walk gets it instead. */
    private boolean pathRefused;

    /**
     * The leash actually in force. Pathed chases get a longer one, because shards fall to whatever
     * level is below the bot and a radius tight enough to keep a hand walk sane cannot reach them.
     */
    public static double effectiveLeash(final AutoAmethystConfig.Collection cfg) {
        return cfg.usePathfinder ? Math.max(cfg.maxDistance, cfg.pathMaxDistance) : cfg.maxDistance;
    }

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
        stopPathing();
        targetEntityId = null;
        chaseTicks = 0;
        stuckTicks = 0;
        noProgressTicks = 0;
        lastDistToTarget = -1;
        escalated = false;
        pathRefused = false;
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
        stopPathing();
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
        noProgressTicks = 0;
        lastDistToTarget = -1;
        escalated = false;
        pathRefused = false;
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
        // effectiveLeash, not maxDistance: finding targets with the narrow hand-walk radius would
        // mean a drop on the level below is never even considered, which defeats the whole point of
        // having a wider leash for pathed chases.
        final Entity target = resolveTarget(wanted, anchorX, anchorY, anchorZ, effectiveLeash(cfg));
        if (target == null) {
            // Stop any path we started, or it keeps running underneath the next state and fights
            // whatever asks the pathfinder for something else.
            stopPathing();
            targetEntityId = null;
            return Status.DONE;
        }

        // A pathed chase gets far longer than a hand walk: climbing down a ladder, crossing a level
        // and coming back is easily ten seconds, and the hand-walk timeout was cutting good trips
        // off part way.
        final int timeout = pathing
            ? Math.max(40, cfg.pathChaseTimeoutTicks)
            : Math.max(20, cfg.chaseTimeoutTicks);
        if (++chaseTicks > timeout) {
            stopPathing();
            return abandonCurrent("gave up chasing a drop after " + chaseTicks + " ticks");
        }

        // Refuse to step outside the leash even if the item drifts. Better to leave one shard than
        // to wander out of the rig.
        final double distFromAnchor = MathHelper.distance3d(
            target.getX(), target.getY(), target.getZ(), anchorX, anchorY, anchorZ);
        if (distFromAnchor > effectiveLeash(cfg)) {
            stopPathing();
            targetEntityId = null;
            chaseTicks = 0;
            return Status.CHASING;
        }

        final double dy = target.getY() - CACHE.getPlayerCache().getY();
        final boolean progressing = trackProgress(Math.max(20, cfg.stuckTicks));

        // Escalate on failure to CONVERGE, not merely on failure to move.
        //
        // trackProgress only notices a bot that has stopped dead. A bot walking into a wall at an
        // angle, sliding along it, or circling a shard it cannot step onto is moving the whole time
        // - so the stuck check never fired, the chase never escalated to the pathfinder, and it ran
        // out the full hand-walk timeout every time. Distance to the target is the honest measure.
        final double distToTarget = MathHelper.distance3d(
            target.getX(), target.getY(), target.getZ(),
            CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ());
        if (lastDistToTarget >= 0 && distToTarget >= lastDistToTarget - 0.02) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }
        lastDistToTarget = distToTarget;

        // A jump clears about 1.25 blocks. Anything higher than that cannot be reached by walking
        // at it, no matter how many times we try - the bot just bounces off the wall below it until
        // the chase times out, even with a ladder a few blocks away. Hand those to the pathfinder,
        // which knows how to climb. Same for a drop well below us, and for a hand walk that has
        // stopped making progress.
        // Once a target has been escalated it STAYS escalated until the target changes. Without
        // that latch the decision flips as the item settles or drifts across the height threshold,
        // and since giving up on a path resets the repath cooldown, the flapping fired a fresh path
        // request every other tick - which is the "Calculated path to goal" storm in the log.
        if (cfg.usePathfinder
            && !pathRefused
            && (escalated
                || Math.abs(dy) > cfg.pathfinderHeightThreshold
                || stuckTicks >= Math.max(1, cfg.escalateToPathTicks)
                || noProgressTicks >= Math.max(1, cfg.escalateToPathTicks))) {
            escalated = true;
            return pathToward(target, cfg);
        }
        // Only judge a HAND walk on progress. While the pathfinder has the leg the bot is legitimately
        // motionless during async path calculation, and chaseTimeoutTicks already bounds that.
        if (!progressing) {
            stopPathing();
            return abandonCurrent("stopped moving while chasing a drop");
        }
        if (pathing) stopPathing();

        // Jump for drops just above: on top of a block, on a ledge, or up a full block step (the
        // 0.6 auto-step handles slabs and stairs but not a whole block).
        final boolean jump = cfg.jumpForDrops
            && (dy > cfg.jumpHeightThreshold || stuckTicks >= Math.max(1, cfg.jumpAfterStuckTicks));
        walkToward(target.getX(), target.getZ(), cfg.sneakWhileCollecting,
                   cfg.sprintWhileCollecting, jump, priority);
        return Status.CHASING;
    }

    /**
     * Lets the pathfinder walk the bot to a drop it cannot reach on foot.
     *
     * <p>Critically, this submits <b>no inputs of its own</b> while a path is running. The module's
     * input priority sits above the pathfinder's, so continuing to press forward here would fight
     * the path it just asked for and the bot would go nowhere.
     *
     * <p>Paths are re-issued on a cooldown rather than every tick: path calculation is asynchronous,
     * so during the window before {@code isActive()} turns true a per-tick re-issue produces a storm
     * of instantly-satisfied requests and no actual movement.
     */
    private Status pathToward(final Entity target, final AutoAmethystConfig.Collection cfg) {
        final int bx = MathHelper.floorI(target.getX());
        final int by = MathHelper.floorI(target.getY());
        final int bz = MathHelper.floorI(target.getZ());

        // Some shards land where nothing can walk - a gap behind the rig, a ledge with no standable
        // block beside it. The pathfinder answers "No path found" after searching a couple of
        // million nodes, and the only visible symptom is the bot standing still. Detect it by the
        // path simply never starting, and write the drop off in a second rather than burning the
        // whole chase timeout on it.
        if (pathing) {
            if (BARITONE.isActive()) {
                pathIdleTicks = 0;
            } else if (++pathIdleTicks > Math.max(10, cfg.pathGiveUpTicks)) {
                // The pathfinder would not route here. Do NOT give up - hand the chase back to the
                // plain walk, which can reach plenty of places the planner refuses.
                //
                // The big one is budding amethyst. Zenith's MovementHelper#isBlockNormalCube
                // excludes every block whose name contains "amethyst" except plain amethyst_block,
                // so the planner treats a budding block as unwalkable even though it is an ordinary
                // full cube you can stand on in vanilla - and shards land on top of them constantly.
                // That rule lives in core and a plugin cannot change it, but the hand walk never
                // consults it.
                stopPathing();
                pathRefused = true;
                // Fresh budget for the hand walk. The pathed attempt has already spent part of the
                // chase, and handing the fallback a nearly-expired clock would fail it immediately.
                chaseTicks = 0;
                noProgressTicks = 0;
                lastDistToTarget = -1;
                return Status.CHASING;
            }
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (!BARITONE.isActive() || bx != pathTargetX || by != pathTargetY || bz != pathTargetZ) {
            // rangeSq 2 is close enough for the ~1 block pickup radius to finish the job
            BARITONE.pathTo(new GoalNear(new BlockPos(bx, by, bz), 2));
            pathTargetX = bx;
            pathTargetY = by;
            pathTargetZ = bz;
            pathing = true;
            repathCooldown = REPATH_COOLDOWN_TICKS;
        }
        return Status.CHASING;
    }

    private void stopPathing() {
        if (!pathing) return;
        pathing = false;
        repathCooldown = 0;
        pathIdleTicks = 0;
        if (BARITONE.isActive()) BARITONE.stop();
    }

    /**
     * Walks back to the stand position. Returns DONE once close enough.
     *
     * <p>Uses the pathfinder for the same reason the chase does: if the bot climbed a ladder to
     * reach a shard, it cannot hand-walk back down to where it started, and a walk that keeps
     * failing would leave it stranded a level above the geode.
     */
    public Status tickReturn(final AutoAmethystConfig.Collection cfg, final double anchorX,
                             final double anchorY, final double anchorZ, final int priority) {
        final double dy = anchorY - CACHE.getPlayerCache().getY();
        if (horizontalDistance(anchorX, anchorZ) <= cfg.returnTolerance
            && Math.abs(dy) <= cfg.pathfinderHeightThreshold) {
            reset();
            return Status.DONE;
        }
        final boolean progressing = trackProgress(Math.max(20, cfg.stuckTicks));
        final boolean needsPath = cfg.usePathfinder
            && (Math.abs(dy) > cfg.pathfinderHeightThreshold
                || stuckTicks >= Math.max(1, cfg.escalateToPathTicks));
        if (needsPath) {
            final int bx = MathHelper.floorI(anchorX);
            final int by = MathHelper.floorI(anchorY);
            final int bz = MathHelper.floorI(anchorZ);
            if (repathCooldown > 0) {
                repathCooldown--;
            } else if (!BARITONE.isActive() || bx != pathTargetX || by != pathTargetY || bz != pathTargetZ) {
                BARITONE.pathTo(new GoalNear(new BlockPos(bx, by, bz), 2));
                pathTargetX = bx;
                pathTargetY = by;
                pathTargetZ = bz;
                pathing = true;
                repathCooldown = REPATH_COOLDOWN_TICKS;
            }
            return Status.RETURNING;
        }
        if (!progressing) {
            failReason = "stopped moving while returning to the stand position";
            reset();
            return Status.FAILED;
        }
        if (pathing) stopPathing();
        // hasWorkNear also consults the unreachable set, so a returning bot cannot be re-triggered
        // by the same item it just gave up on
        walkToward(anchorX, anchorZ, cfg.sneakWhileCollecting, cfg.sprintWhileCollecting,
                   stuckTicks >= Math.max(1, cfg.jumpAfterStuckTicks), priority);
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
            // A new target must start with a clean convergence history, or it inherits the previous
            // target's distance and looks like it is already failing to make progress.
            noProgressTicks = 0;
            lastDistToTarget = -1;
            escalated = false;
            pathRefused = false;
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

    private void walkToward(final double x, final double z, final boolean sneak,
                            final boolean sprint, final boolean jump, final int priority) {
        final float yaw = RotationHelper.yawToXZ(x, z);
        INPUTS.submit(InputRequest.builder()
            .owner(owner)
            .input(Input.builder()
                .pressingForward(true)
                .sneaking(sneak)
                // Zenith cancels sprint whenever sneak is held, so these can never conflict.
                .sprinting(sprint && !sneak)
                .jumping(jump)
                .build())
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
