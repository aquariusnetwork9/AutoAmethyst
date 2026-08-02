package com.shallowplague.amethyst.module;

import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.Position;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.BlockRaycastResult;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.util.math.MathHelper;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;

import java.util.function.Predicate;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.MODULE_LOG;

/**
 * Breaks one block at a time through the vanilla interaction path, refusing anything the caller has
 * not explicitly allowed.
 *
 * <p>Shared by the harvest loop and the deposit cycle so there is exactly one place in the plugin
 * that can break a block, and exactly one allowlist check guarding it. The {@code allowed}
 * predicate is re-evaluated on <b>every tick</b> immediately before the click is submitted, not
 * once when the target is chosen - a block can change under us between ticks, and the whole point
 * is that we never swing at whatever it changed into.
 *
 * <p>Breaking is driven by submitting a left click input with a {@link ClickTarget.BlockPosition}
 * and a real rotation, which is what Zenith's own interact process does. That routes through
 * {@code PlayerInteractionManager}, so destroy progress against the held item, sequence numbers,
 * swing packets and the inter-block destroy delay are all handled by the engine. No block break
 * packet is constructed here.
 */
public final class BreakDriver {

    public enum Status {
        /** Still swinging. */
        BUSY,
        /** The block is gone. */
        BROKEN,
        /** Not allowed, not reachable, or took too long. See {@link #blockedReason()}. */
        BLOCKED
    }

    /**
     * Ticks a target may be out of reach or out of sight before the break is given up on. Covers
     * the bot still decelerating after the pathfinder has parked it, and the rotation gate - the
     * engine's interaction tick runs before the requested yaw is committed, so the first tick of a
     * new target only turns.
     */
    private static final int ENGAGE_SETTLE_TICKS = 20;

    private boolean active = false;
    private long target = 0;
    private int ticks = 0;
    /** Consecutive ticks the target has been unreachable within this one break attempt. */
    private int engageFailTicks = 0;
    private String blockedReason = "";

    public boolean isActive() { return active; }
    public String blockedReason() { return blockedReason; }
    public int ticksSpent() { return ticks; }
    public long target() { return target; }

    public void begin(final int x, final int y, final int z) {
        target = BlockPos.asLong(x, y, z);
        ticks = 0;
        engageFailTicks = 0;
        active = true;
        blockedReason = "";
    }

    public void reset() {
        active = false;
        target = 0;
        ticks = 0;
        engageFailTicks = 0;
        blockedReason = "";
    }

    /**
     * Sends a "not clicking" input so the engine emits the ABORT a vanilla client would send when it
     * lets go mid-dig, rather than simply going quiet.
     */
    public void release(final int priority) {
        if (!active) return;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().build())
            .priority(priority)
            .build());
    }

    public Status tick(final Predicate<Block> allowed, final double reach, final boolean requireLineOfSight,
                       final int maxTicks, final int priority) {
        if (!active) return Status.BROKEN;
        final int x = BlockPos.getX(target);
        final int y = BlockPos.getY(target);
        final int z = BlockPos.getZ(target);

        if (!World.isChunkLoadedBlockPos(x, z)) return blocked("chunk unloaded");

        final Block block = World.getBlock(x, y, z);
        if (block.isAir()) {
            // Only a real break if we actually swung. Otherwise something else removed it.
            if (ticks > 0) {
                active = false;
                return Status.BROKEN;
            }
            return blocked("target vanished before the first swing");
        }
        // ABSOLUTE VETO. Budding amethyst is never breakable, by anyone, under any configuration,
        // in any mode. It is already absent from every allowlist, but this is the single line every
        // break in the plugin passes through, so the rule is enforced here where no caller mistake,
        // future refactor or config combination can route around it.
        //
        // It is worth being this heavy-handed about: budding amethyst drops nothing with any tool
        // including Silk Touch, cannot be obtained in survival, and produces a cluster on each of
        // six faces every ~2h17m. Breaking one is silent, instant and permanent.
        if (block == BlockRegistry.BUDDING_AMETHYST) {
            return vetoed("REFUSED to break budding amethyst - this should be unreachable, "
                + "please report it");
        }
        // The configured allowlist. Everything else this plugin may break passes through here.
        if (!allowed.test(block)) return blocked("block is " + block.name() + ", which is not on the allowlist");

        if (++ticks > Math.max(20, maxTicks)) return blocked("no progress after " + ticks + " ticks (ghost block?)");

        final Position center = World.blockInteractionCenter(x, y, z);
        final Vector2f rot = RotationHelper.rotationTo(center.x(), center.y(), center.z());
        if (!canEngage(x, y, z, rot, reach, requireLineOfSight)) {
            // Not immediately fatal. The caller only decides to engage once, a tick earlier, and
            // the bot is often still gliding to a stop when the break starts - so a target right at
            // the edge of reach fails on the first tick and would be given up on before it had
            // stood still. Tolerate a short settling window; a target that never comes into view
            // still ends up blocked, just with an accurate reason.
            if (++engageFailTicks <= ENGAGE_SETTLE_TICKS) return Status.BUSY;
            return blocked("out of reach or no line of sight");
        }
        engageFailTicks = 0;

        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .hand(Hand.MAIN_HAND)
                .clickRequiresRotation(true)
                .clickTarget(new ClickTarget.BlockPosition(x, y, z))
                .leftClick(true)
                .build())
            .yaw(rot.getX())
            .pitch(rot.getY())
            .priority(priority)
            .build());
        return Status.BUSY;
    }

    /**
     * Whether the block can actually be hit from here with this rotation.
     *
     * <p>Two questions. Does the aimed ray meet the target's own interaction box inside reach - the
     * engine re-runs exactly this when the click executes, so failing it makes the click a no-op.
     * And is the target the <i>first</i> thing that ray meets: Zenith's block-target raycast
     * deliberately ignores intervening blocks, so without this the bot would mine through walls,
     * which no vanilla client can do.
     */
    public static boolean canEngage(final int x, final int y, final int z, final Vector2f rot,
                                    final double reach, final boolean requireLineOfSight) {
        return canEngage(x, y, z, BOT.getX(), BOT.getEyeY(), BOT.getZ(), rot, reach, requireLineOfSight);
    }

    /**
     * The same test from an arbitrary eye position, so a stand position can be evaluated
     * <i>before</i> walking to it.
     *
     * <p>This is what lets the module answer "where would I have to stand to break this?" instead
     * of only ever "can I break it from here?". Without it the sole remedy for an occluded cluster
     * was to ask the pathfinder to get closer and hope, which inside a geode routinely cannot be
     * satisfied at all.
     */
    public static boolean canEngage(final int x, final int y, final int z,
                                    final double eyeX, final double eyeY, final double eyeZ,
                                    final Vector2f rot, final double reach,
                                    final boolean requireLineOfSight) {
        final BlockRaycastResult through = RaycastHelper.blockRaycastThroughToBlockTarget(
            x, y, z, eyeX, eyeY, eyeZ, rot.getX(), rot.getY(), reach);
        if (!through.hit() || through.x() != x || through.y() != y || through.z() != z) return false;
        if (!requireLineOfSight) return true;
        final BlockRaycastResult first = RaycastHelper.blockRaycastFromPos(
            eyeX, eyeY, eyeZ, rot.getX(), rot.getY(), reach, false);
        if (!first.hit()) return true; // nothing at all in the way
        if (first.x() == x && first.y() == y && first.z() == z) return true;

        // The first block the ray meets is not the target - but that does NOT mean the target is
        // occluded. An amethyst cluster's interaction box is a small shape attached to one face of
        // its voxel, so a ray aimed at it routinely clips the corner of an adjacent voxel (the wall
        // the cluster is growing out of) before entering the cluster's own box. Comparing block
        // identity therefore rejects almost every legitimately visible cluster, which is exactly
        // what "requireLineOfSight on = nothing gets harvested" looked like.
        //
        // The real question is whether anything sits STRICTLY IN FRONT of the target surface, so
        // compare how far along the ray each intersection is.
        return distanceSqFromEye(through) <= distanceSqFromEye(first) + 1.0E-4;
    }

    private static double distanceSqFromEye(final BlockRaycastResult result) {
        final var hit = result.intersection();
        if (hit == null) return Double.MAX_VALUE;
        return MathHelper.distanceSq3d(BOT.getX(), BOT.getEyeY(), BOT.getZ(), hit.x(), hit.y(), hit.z());
    }

    private Status blocked(final String reason) {
        blockedReason = reason;
        active = false;
        return Status.BLOCKED;
    }

    /**
     * A blocked break that also indicates a bug. Logged at error level rather than passed quietly
     * back to the caller, because reaching it means an allowlist somewhere is wrong and the only
     * reason nothing was destroyed is this backstop.
     */
    private Status vetoed(final String reason) {
        MODULE_LOG.error("[BreakDriver] {}", reason);
        return blocked(reason);
    }
}
