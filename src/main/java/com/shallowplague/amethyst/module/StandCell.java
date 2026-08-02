package com.shallowplague.amethyst.module;

import com.zenith.feature.player.Position;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.LocalizedCollisionBox;
import com.zenith.util.math.MathHelper;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.BOT;

/**
 * Works out <i>where the bot would have to stand</i> to break a given block.
 *
 * <p>Before this existed the plugin could only ever answer "can I break this from here?", and its
 * only remedy for "no" was to ask the pathfinder to get closer and hope. Inside a geode that
 * frequently cannot be satisfied at all, so every occluded cluster cost a full travel leg and a
 * blacklist, forever, while sitting a couple of blocks away.
 *
 * <h2>Standability is tested physically, not by asking the pathfinder</h2>
 * The obvious test is {@code MovementHelper.canWalkOn}, and it is wrong here.
 * {@code MovementHelper.isBlockNormalCube} returns false for every block whose name contains
 * "amethyst" except plain {@code amethyst_block}, so the planner considers budding amethyst
 * unstandable - and budding amethyst is most of the floor of a working geode rig. Using the
 * planner's opinion would make this solver reject nearly every real stand position and inherit the
 * very bug it exists to route around.
 *
 * <p>So standability is decided the way the game decides it: the block underfoot stops motion, and
 * the two cells the body occupies do not. A vanilla client walks across budding amethyst without
 * complaint, which is the whole point.
 *
 * <p>The pathfinder may still refuse to route to a cell this returns. That is expected, and it is
 * why {@link Travel} falls back to walking at the cell directly.
 */
public final class StandCell {

    /** Vanilla standing eye height. */
    public static final double EYE_HEIGHT = 1.62;

    private StandCell() { }

    /**
     * Nearest position the bot could stand and actually break {@code target} from, or null.
     *
     * @param radius   horizontal cells to search either side
     * @param below    how many levels below the target to consider standing
     * @param above    how many levels above the target to consider standing
     */
    public static @Nullable BlockPos find(final int tx, final int ty, final int tz,
                                          final double reach, final boolean requireLineOfSight,
                                          final int radius, final int below, final int above) {
        final Position centre = World.blockInteractionCenter(tx, ty, tz);
        final double reachSq = reach * reach;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -below; dy <= above; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final int fx = tx + dx, fy = ty + dy, fz = tz + dz;
                    if (!World.isChunkLoadedBlockPos(fx, fz)) continue;

                    final double ex = fx + 0.5, ey = fy + EYE_HEIGHT, ez = fz + 0.5;
                    final double distSq = MathHelper.distanceSq3d(centre.x(), centre.y(), centre.z(), ex, ey, ez);
                    // Cheap rejections first - the raycasts are the expensive part.
                    if (distSq > reachSq || distSq >= bestDistSq) continue;
                    if (!standable(fx, fy, fz)) continue;

                    // TARGET first, then source. RotationHelper.rotationTo(a,b,c,d,e,f) computes
                    // dx = a - d, so passing the eye first aims the ray 180 degrees away from the
                    // block and every candidate cell then reports "no clean shot".
                    final Vector2f rot = RotationHelper.rotationTo(
                        centre.x(), centre.y(), centre.z(), ex, ey, ez);
                    if (!BreakDriver.canEngage(tx, ty, tz, ex, ey, ez, rot, reach, requireLineOfSight)) continue;

                    bestDistSq = distSq;
                    best = new BlockPos(fx, fy, fz);
                }
            }
        }
        return best;
    }

    /**
     * {@code LocalizedCollisionBox.intersects} is INCLUSIVE on every axis, and the block scan is
     * padded by a cell in each direction, so the floor at {@code y-1} is always tested and always
     * "touches" a box whose {@code minY} is exactly {@code y}. Without this shrink every cell that
     * has a floor reads as blocked, the solver returns nothing, and the bot stands still for good.
     * Zenith never makes the call raw either - {@code Bot.canPlayerFitWithinBlocksAndEntitiesWhen}
     * inflates by this same negative epsilon first.
     */
    private static final double EPSILON = 1.0E-7;

    /** Depth of the slab probed under the feet, as {@code Bot.updateSupportingBlockPos} uses. */
    private static final double SUPPORT_PROBE = 1.0E-6;

    /**
     * Whether the bot could stand with its feet exactly in this cell.
     *
     * <p>Order matters: {@link #occupiable} must be tested first. The support probe's {@code maxY}
     * equals the body's {@code minY} and {@code intersects} is inclusive, so an upward-facing
     * cluster sitting in the destination cell itself would otherwise register as "supported" -
     * {@code occupiable} is the thing that rejects it.
     *
     * <p>The chunk guard is load-bearing rather than defensive: {@code World.getBlockStateId}
     * returns 0 (air) for an unloaded section, so in unloaded chunks the collision tests answer a
     * confident "clear" for solid rock.
     */
    public static boolean standable(final int x, final int y, final int z) {
        if (!World.isChunkLoadedBlockPos(x, z)) return false;
        final double cx = x + 0.5, cz = z + 0.5;
        return occupiable(cx, y, cz) && supported(cx, y, cz);
    }

    /**
     * Nothing solid overlaps the player's body here.
     *
     * <p>Shape-exact, and it replaces {@code blocksMotion} on the body cells in <b>both</b>
     * directions. {@code World.blocksMotion} is {@code Block.solidBlock()} bar cobweb and bamboo,
     * and {@code solidBlock()} is <b>true</b> for {@code amethyst_cluster} and all three buds - so
     * the old test rejected every cell containing any crystal, including ones the 0.6-wide player
     * fits in perfectly well (a small bud occupies 0.8125..1.0 on its axis; the centred player box
     * is 0.2..0.8, which does not overlap at all).
     */
    public static boolean occupiable(final double x, final double y, final double z) {
        return World.isSpaceEmpty(playerBoxAt(x, y, z).inflate(-EPSILON, -EPSILON, -EPSILON));
    }

    /** Cell-centre convenience. */
    public static boolean fits(final int x, final int y, final int z) {
        return occupiable(x + 0.5, y, z + 0.5);
    }

    /**
     * Something presents a real collision surface exactly at the feet plane.
     *
     * <p>This is the fix for the actual wedging bug. The old test only asked whether the block
     * underfoot "stops motion", which is true of a crystal as much as of stone - but a floor-facing
     * cluster tops out {@code 0.5625} below the cell and a bud lower still. The bot's step height is
     * {@code 0.6}, above every one of those, so it would step onto the crystal and settle BELOW the
     * cell the solver had chosen. From that moment {@code floorI(y)} names the crystal's own cell as
     * the bot's feet cell, and every cell-indexed test in the plugin and in Baritone agrees the bot
     * is standing inside a cluster - which is unroutable. That is the wedge.
     */
    public static boolean supported(final double x, final double y, final double z) {
        final LocalizedCollisionBox body = playerBoxAt(x, y, z);
        return World.findSupportingBlockPos(new LocalizedCollisionBox(
            body.minX(), body.maxX(),
            body.minY() - SUPPORT_PROBE, body.minY(),
            body.minZ(), body.maxZ(),
            x, y, z)).isPresent();
    }

    /**
     * The player's own standing collision box, anchored anywhere.
     *
     * <p>Always {@code STANDING}, never {@code BOT.getPose()}: the engine silently auto-crouches to
     * a 1.5 tall box when standing does not fit, so a pose-dependent test would call a cell clear
     * that the bot cannot actually stand up in.
     */
    public static LocalizedCollisionBox playerBoxAt(final double x, final double y, final double z) {
        return new LocalizedCollisionBox(BOT.getCollisionBox(Pose.STANDING), x, y, z);
    }
}
