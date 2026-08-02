package com.shallowplague.amethyst.module;

import com.zenith.feature.player.Position;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.util.math.MathHelper;
import org.cloudburstmc.math.vector.Vector2f;
import org.jspecify.annotations.Nullable;

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

                    final Vector2f rot = RotationHelper.rotationTo(ex, ey, ez, centre.x(), centre.y(), centre.z());
                    if (!BreakDriver.canEngage(tx, ty, tz, ex, ey, ez, rot, reach, requireLineOfSight)) continue;

                    bestDistSq = distSq;
                    best = new BlockPos(fx, fy, fz);
                }
            }
        }
        return best;
    }

    /** Whether a player could physically stand with their feet in this cell. */
    public static boolean standable(final int x, final int y, final int z) {
        final Block floor = World.getBlock(x, y - 1, z);
        if (!World.blocksMotion(floor)) return false;
        return !World.blocksMotion(World.getBlock(x, y, z))
            && !World.blocksMotion(World.getBlock(x, y + 1, z));
    }
}
