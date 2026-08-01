package com.shallowplague.amethyst.module;

import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.mc.block.BlockPos;

/**
 * "Get close enough that the server hands us this item", rather than "stand exactly on it".
 *
 * <h2>Why this exists</h2>
 * The obvious goal for a dropped shard is {@link GoalBlock} on the item's own block, which is what
 * the built-in {@code BARITONE.pickup()} uses. Inside a geode that goal is frequently impossible:
 * {@code MovementHelper.isBlockNormalCube} returns false for every amethyst-named block except
 * {@code amethyst_block}, so the pathfinder will not stand on budding amethyst. A shard that lands
 * on the geode floor therefore has no reachable {@code GoalBlock}, the path request fails
 * immediately, and the drop looks unreachable when in reality the bot only has to stand beside it.
 *
 * <h2>The geometry</h2>
 * Vanilla picks an item up when the player's bounding box, inflated by 1.0 horizontally and 0.5
 * vertically, intersects the item's. A 0.6 x 1.8 player against a 0.25 item works out as:
 * <ul>
 *   <li>horizontally, centres within ~1.425 blocks - so a block-adjacent stand position, including
 *       a diagonal one, is comfortably inside;</li>
 *   <li>vertically, the item may sit up to 2.3 above the feet but only 0.75 <i>below</i> them,
 *       because the inflation is small next to the player's own height.</li>
 * </ul>
 * That asymmetry is the important part: standing on a ledge <i>above</i> a shard never picks it up,
 * which is exactly the "walked right up to it and did nothing" case. So the goal accepts the item's
 * own block and the ring around it at the same level or one below, and never a position above it.
 *
 * <p>Only satisfaction is loosened. {@link #heuristic} still points at the item's own block, so the
 * pathfinder stands directly on the shard whenever it can and settles for standing beside it only
 * when it cannot.
 */
public final class GoalPickup implements Goal {

    private final int x;
    private final int y;
    private final int z;
    /** Delegate for the cost function, so the pull toward the item uses Baritone's own scale. */
    private final GoalBlock exact;

    public GoalPickup(final int x, final int y, final int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.exact = new GoalBlock(new BlockPos(x, y, z));
    }

    @Override
    public boolean isInGoal(final int px, final int py, final int pz) {
        final int dy = py - y;
        // Above the item is not a pickup position however close it looks, and more than a block
        // below means the item is out of the top of the inflated box.
        if (dy > 0 || dy < -1) return false;
        return Math.abs(px - x) <= 1 && Math.abs(pz - z) <= 1;
    }

    @Override
    public double heuristic(final int px, final int py, final int pz) {
        return exact.heuristic(px, py, pz);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof final GoalPickup other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return (x * 31 + y) * 31 + z;
    }

    @Override
    public String toString() {
        return "GoalPickup{" + x + ", " + y + ", " + z + "}";
    }
}
