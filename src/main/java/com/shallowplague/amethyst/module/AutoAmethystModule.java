package com.shallowplague.amethyst.module;

import com.github.rfresh2.EventConsumer;
import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
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
import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolTag;
import com.zenith.mc.item.ToolType;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;
import static com.shallowplague.amethyst.AutoAmethystPlugin.PLUGIN_CONFIG;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/**
 * Harvests mature {@code minecraft:amethyst_cluster} blocks inside a configured box.
 *
 * <h2>What it will and will not break</h2>
 * The target block is re-checked against {@link BlockRegistry#AMETHYST_CLUSTER} by identity on
 * <i>every single tick</i> immediately before the click input is submitted, not once when the
 * target is chosen. Nothing else can ever be swung at. That matters more than usual here: the
 * immature buds drop nothing at all, and {@code budding_amethyst} is unobtainable, so one stray
 * break permanently deletes a growth site.
 *
 * <h2>How the break is driven</h2>
 * Through {@code PlayerInteractionManager} via a normal left click input, exactly as Zenith's own
 * interact process does. That buys real destroy progress against the held item, correct sequence
 * numbers, swing packets, the 5 tick inter block destroy delay and the rotation gate, all of which
 * a hand rolled {@code ServerboundPlayerActionPacket} would get wrong.
 *
 * <p>Note that {@code Bot#interactionTick} runs before the requested rotation is committed, so the
 * first tick of a new target only turns the head and the click lands on the second tick. The module
 * therefore holds one target until it breaks rather than re-picking every tick.
 */
public class AutoAmethystModule extends Module {

    /** Refuse to scan an absurd box; a geode interior is tens of blocks across, not hundreds. */
    private static final int MAX_BOX_VOLUME = 262_144; // 64^3

    private enum State { SCAN, ENGAGE, SETTLE, MOVING }

    private final Timer scanTimer = Timers.tickTimer();
    private final Timer censusTimer = Timers.tickTimer();
    private final MovementDriver mover = new MovementDriver(this);

    private State state = State.SCAN;
    private boolean paused = false;
    private String pauseReason = "";
    private volatile boolean reloadRequested = false;

    /** Candidate cluster positions currently within reach, packed via {@link BlockPos#asLong}. */
    private final LongArrayList reachable = new LongArrayList();
    /** pos -> tick at which the position becomes eligible again. */
    private final Map<Long, Long> skipUntil = new HashMap<>();
    /** pos -> tick at which we believed we broke it, for anticheat revert detection. */
    private final Map<Long, Long> recentlyBroken = new HashMap<>();

    private long tickCounter = 0;
    private boolean hasTarget = false;
    private long targetPos = 0;
    private int targetTicks = 0;
    private int settleTicks = 0;
    private int toolWaitTicks = 0;
    private int dwellTicks = 0;
    private int waypointIndex = 0;
    private boolean visitedAnyWaypoint = false;
    private int consecutiveFailures = 0;

    // instrumentation
    private long breaks = 0;
    private long skipped = 0;
    private long reverts = 0;
    private long toolSwaps = 0;
    private int matureInBox = 0;
    private long sessionStartMs = 0;
    private int shardsAtStart = -1;
    private int shardsNow = -1;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.harvest.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onBotStarting),
            of(ClientBotTick.Stopped.class, this::onBotStopped)
        );
    }

    @Override
    public void onEnable() {
        resetRuntime();
        sessionStartMs = System.currentTimeMillis();
        shardsAtStart = -1;
        breaks = skipped = reverts = toolSwaps = 0;
        info("Enabled. Movement mode: {}", mode());
    }

    @Override
    public void onDisable() {
        mover.reset();
        resetRuntime();
    }

    private void onBotStarting(final ClientBotTick.Starting event) {
        // fresh connection: every cached position and in-flight break is meaningless
        resetRuntime();
    }

    private void onBotStopped(final ClientBotTick.Stopped event) {
        mover.reset();
        resetRuntime();
    }

    /** Called from the command thread when config changed in a way that invalidates runtime state. */
    public void requestReload() {
        reloadRequested = true;
    }

    private void resetRuntime() {
        state = State.SCAN;
        paused = false;
        pauseReason = "";
        hasTarget = false;
        targetPos = 0;
        targetTicks = 0;
        settleTicks = 0;
        toolWaitTicks = 0;
        dwellTicks = 0;
        visitedAnyWaypoint = false;
        consecutiveFailures = 0;
        reachable.clear();
        skipUntil.clear();
        recentlyBroken.clear();
        scanTimer.skip();
    }

    private AutoAmethystConfig.Harvest harvest() { return PLUGIN_CONFIG.harvest; }

    private MovementDriver.Mode mode() {
        return MovementDriver.parseMode(PLUGIN_CONFIG.movement.mode);
    }

    // ------------------------------------------------------------------ tick

    private void onTick(final ClientBotTick event) {
        tickCounter++;
        if (reloadRequested) {
            reloadRequested = false;
            mover.reset();
            resetRuntime();
            info("Reloaded configuration");
        }
        if (paused) return;
        if (!CACHE.getPlayerCache().isAlive()) {
            // dead: let AutoRespawn deal with it, and drop any half-finished break
            if (hasTarget) abandonTarget("player died");
            return;
        }
        if (!validateSetup()) return;

        if (shardsAtStart < 0) {
            shardsAtStart = countShards();
            shardsNow = shardsAtStart;
        }

        try {
            switch (state) {
                case SCAN -> tickScan();
                case ENGAGE -> tickEngage();
                case SETTLE -> tickSettle();
                case MOVING -> tickMoving();
            }
        } catch (final Exception e) {
            error("Unhandled error in harvest tick, pausing", e);
            pause("internal error: " + e.getClass().getSimpleName());
        }

        maybeLogSummary();
    }

    /** One time sanity checks that would otherwise produce silent nonsense. */
    private boolean validateSetup() {
        final AutoAmethystConfig.Harvest h = harvest();
        if (!h.boxSet) {
            pause("no geode box configured - set one with 'autoamethyst box corner1' / 'box corner2'");
            return false;
        }
        if (h.minX > h.maxX || h.minY > h.maxY || h.minZ > h.maxZ) {
            pause("geode box corners are inverted");
            return false;
        }
        final long volume = (long) (h.maxX - h.minX + 1) * (h.maxY - h.minY + 1) * (h.maxZ - h.minZ + 1);
        if (volume > MAX_BOX_VOLUME) {
            pause("geode box is " + volume + " blocks, over the " + MAX_BOX_VOLUME + " limit");
            return false;
        }
        final MovementDriver.Mode m = mode();
        if (m != MovementDriver.Mode.STATIONARY && PLUGIN_CONFIG.movement.waypoints.isEmpty()) {
            pause("movement mode " + m + " needs at least one waypoint");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ states

    private void tickScan() {
        if (!ensureTool()) return;

        if (scanTimer.tick(Math.max(1, harvest().rescanIntervalTicks))) {
            rescan();
        }
        if (reachable.isEmpty()) {
            // nothing in reach; either wait here or move on to the next stand position
            if (mode() == MovementDriver.Mode.STATIONARY) return;
            if (++dwellTicks >= Math.max(0, PLUGIN_CONFIG.movement.dwellTicks)) {
                beginNextWaypoint();
            }
            return;
        }
        dwellTicks = 0;
        final long pos = pickNearestTarget();
        if (pos == Long.MIN_VALUE) {
            reachable.clear();
            return;
        }
        targetPos = pos;
        hasTarget = true;
        targetTicks = 0;
        state = State.ENGAGE;
    }

    private void tickEngage() {
        final int x = BlockPos.getX(targetPos);
        final int y = BlockPos.getY(targetPos);
        final int z = BlockPos.getZ(targetPos);

        if (!World.isChunkLoadedBlockPos(x, z)) {
            abandonTarget("chunk unloaded");
            return;
        }

        final Block block = World.getBlock(x, y, z);
        if (block.isAir()) {
            // Only credit a break we actually swung at. If it went to air before our first click
            // landed, something else removed it and counting it would inflate the stats.
            if (targetTicks > 0) {
                onBreakSucceeded(x, y, z);
            } else {
                abandonTarget("target vanished before the first swing");
            }
            return;
        }
        // HARD GUARD. Anything that is not a mature cluster is never swung at, full stop.
        if (block != BlockRegistry.AMETHYST_CLUSTER) {
            abandonTarget("block became " + block.name());
            return;
        }
        if (++targetTicks > Math.max(20, harvest().maxBreakTicks)) {
            skipTarget(x, y, z, "no progress after " + targetTicks + " ticks (ghost block?)");
            return;
        }
        if (!isUsableHeldPickaxe()) {
            abandonTarget("held item is no longer a usable pickaxe");
            return;
        }

        final Position center = World.blockInteractionCenter(x, y, z);
        final Vector2f rot = RotationHelper.rotationTo(center.x(), center.y(), center.z());
        if (!canEngage(x, y, z, rot)) {
            abandonTarget("lost reach or line of sight");
            return;
        }

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
            .priority(harvest().inputPriority)
            .build());
    }

    private void tickSettle() {
        if (--settleTicks > 0) return;
        state = State.SCAN;
    }

    private void tickMoving() {
        final MovementDriver.Status status = mover.tick(mode(), PLUGIN_CONFIG.movement, harvest().inputPriority);
        switch (status) {
            case BUSY -> { }
            case ARRIVED -> {
                dwellTicks = 0;
                scanTimer.skip();
                state = State.SCAN;
            }
            case FAILED -> {
                pause("movement failed: " + mover.failReason());
            }
        }
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Rebuilds {@link #reachable}.
     *
     * <p>Only the part of the box that could possibly be in reach is swept. Reach is at most the
     * vanilla 4.5, so this is a ~10 block cube regardless of how big the geode is - sweeping the
     * whole box every second would be a few hundred thousand block lookups on the tick thread for
     * no benefit. The whole-box census that feeds the "mature in box" stat runs on its own much
     * slower timer.
     */
    private void rescan() {
        reachable.clear();
        expireSkips();
        checkForReverts();
        shardsNow = countShards();

        final AutoAmethystConfig.Harvest h = harvest();
        final double reach = effectiveReach();
        final int r = (int) Math.ceil(reach) + 1;
        final int px = MathHelper.floorI(BOT.getX());
        final int py = MathHelper.floorI(BOT.getEyeY());
        final int pz = MathHelper.floorI(BOT.getZ());

        final int loX = Math.max(h.minX, px - r), hiX = Math.min(h.maxX, px + r);
        final int loY = Math.max(h.minY, py - r), hiY = Math.min(h.maxY, py + r);
        final int loZ = Math.max(h.minZ, pz - r), hiZ = Math.min(h.maxZ, pz + r);

        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = loY; y <= hiY; y++) {
                    if (World.getBlock(x, y, z) != BlockRegistry.AMETHYST_CLUSTER) continue;
                    final long pos = BlockPos.asLong(x, y, z);
                    if (isSkipped(pos)) continue;
                    if (!withinReach(x, y, z, reach)) continue;
                    reachable.add(pos);
                }
            }
        }

        if (censusTimer.tick(Math.max(20, harvest().censusIntervalTicks))) {
            recountBox();
        }
    }

    /** Whole-box count of mature clusters. Statistics only; never used to pick a target. */
    private void recountBox() {
        final AutoAmethystConfig.Harvest h = harvest();
        int count = 0;
        for (int x = h.minX; x <= h.maxX; x++) {
            for (int z = h.minZ; z <= h.maxZ; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = h.minY; y <= h.maxY; y++) {
                    if (World.getBlock(x, y, z) == BlockRegistry.AMETHYST_CLUSTER) count++;
                }
            }
        }
        matureInBox = count;
    }

    /**
     * Cheap distance pre-filter so the expensive raycasts only run on the one target we pick.
     * Uses the interaction box centre, which for a cluster is nowhere near the block centre.
     */
    private boolean withinReach(final int x, final int y, final int z, final double reach) {
        final Position c = World.blockInteractionCenter(x, y, z);
        final double d = MathHelper.distanceSq3d(c.x(), c.y(), c.z(), BOT.getX(), BOT.getEyeY(), BOT.getZ());
        return d <= reach * reach;
    }

    /** Returns the nearest reachable target that still passes every guard, or {@link Long#MIN_VALUE}. */
    private long pickNearestTarget() {
        long best = Long.MIN_VALUE;
        double bestDist = Double.MAX_VALUE;
        final LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            final long pos = it.nextLong();
            final int x = BlockPos.getX(pos);
            final int y = BlockPos.getY(pos);
            final int z = BlockPos.getZ(pos);
            if (World.getBlock(x, y, z) != BlockRegistry.AMETHYST_CLUSTER) continue;
            if (isSkipped(pos)) continue;
            final Position c = World.blockInteractionCenter(x, y, z);
            final Vector2f rot = RotationHelper.rotationTo(c.x(), c.y(), c.z());
            if (!canEngage(x, y, z, rot)) continue;
            final double d = MathHelper.distanceSq3d(c.x(), c.y(), c.z(), BOT.getX(), BOT.getEyeY(), BOT.getZ());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /**
     * True when the block can actually be hit from here with the given rotation.
     *
     * <p>Two separate questions. First, does the aimed ray intersect the target's own interaction
     * box within reach - that is what the engine will re-run when the click executes, so if it
     * fails the click is a no-op. Second, is the target the <i>first</i> thing that ray hits.
     * Zenith's block-target raycast deliberately ignores intervening blocks, so without this second
     * check the bot would happily mine clusters through a wall, which is not something a vanilla
     * client can do.
     */
    private boolean canEngage(final int x, final int y, final int z, final Vector2f rot) {
        final double reach = effectiveReach();
        final BlockRaycastResult through =
            RaycastHelper.playerEyeRaycastThroughToBlockTarget(x, y, z, rot.getX(), rot.getY(), reach);
        if (!through.hit() || through.x() != x || through.y() != y || through.z() != z) return false;

        if (!harvest().requireLineOfSight) return true;

        final BlockRaycastResult first = RaycastHelper.blockRaycastFromPos(
            BOT.getX(), BOT.getEyeY(), BOT.getZ(), rot.getX(), rot.getY(), reach, false);
        return first.hit() && first.x() == x && first.y() == y && first.z() == z;
    }

    /**
     * Reach in blocks. Defaults to the server's own block interaction range attribute, which is the
     * vanilla 4.5. The config value can only lower it - raising reach past vanilla is precisely what
     * anticheat looks for.
     */
    private double effectiveReach() {
        final double engine = BOT.getBlockReachDistance();
        final double configured = harvest().maxReach;
        if (configured > 0 && configured < engine) return configured;
        return engine;
    }

    // ------------------------------------------------------------------ target lifecycle

    private void onBreakSucceeded(final int x, final int y, final int z) {
        breaks++;
        consecutiveFailures = 0;
        recentlyBroken.put(BlockPos.asLong(x, y, z), tickCounter);
        reachable.rem(BlockPos.asLong(x, y, z));
        clearTarget();
        settleTicks = Math.max(1, harvest().interBreakDelayTicks);
        state = State.SETTLE;
        logHarvest(x, y, z);
    }

    /** Target is no longer valid but is not the target's fault; re-scan and pick another. */
    private void abandonTarget(final String reason) {
        if (hasTarget) {
            debug("Abandoned target {}: {}", describe(targetPos), reason);
        }
        releaseInputs();
        clearTarget();
        if (++consecutiveFailures >= Math.max(1, harvest().maxConsecutiveFailures)) {
            pause("gave up after " + consecutiveFailures + " consecutive failed targets (last: " + reason + ")");
            return;
        }
        scanTimer.skip();
        state = State.SCAN;
    }

    /** Target is bad; blacklist it for a while so the loop cannot spin on it. */
    private void skipTarget(final int x, final int y, final int z, final String reason) {
        skipped++;
        final long pos = BlockPos.asLong(x, y, z);
        skipUntil.put(pos, tickCounter + Math.max(20, harvest().skipCooldownTicks));
        reachable.rem(pos);
        warn("Skipping {}: {}", describe(pos), reason);
        releaseInputs();
        clearTarget();
        scanTimer.skip();
        state = State.SCAN;
    }

    private void clearTarget() {
        hasTarget = false;
        targetPos = 0;
        targetTicks = 0;
    }

    /**
     * Stops an in-flight break cleanly. Submitting an empty input at our own priority makes the
     * interaction manager see "not left clicking" next tick, which sends the ABORT_DESTROY_BLOCK a
     * vanilla client would send instead of just going silent mid-dig.
     */
    private void releaseInputs() {
        if (!hasTarget) return;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().build())
            .priority(harvest().inputPriority)
            .build());
    }

    /**
     * Detects blocks we thought we broke that came back.
     *
     * <p>A cluster takes roughly 34 minutes per growth stage, so a cluster reappearing within
     * seconds is never regrowth - it is the server rejecting the break and reverting our optimistic
     * client-side prediction. That is the signature of an anticheat veto, and it is worth surfacing
     * loudly because it means the farm is producing nothing at all.
     */
    private void checkForReverts() {
        if (recentlyBroken.isEmpty()) return;
        final long window = Math.max(1, harvest().revertWindowTicks);
        final Iterator<Map.Entry<Long, Long>> it = recentlyBroken.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Long> entry = it.next();
            final long pos = entry.getKey();
            final long stamp = entry.getValue();
            if (tickCounter - stamp > window) {
                it.remove();
                continue;
            }
            final int x = BlockPos.getX(pos);
            final int y = BlockPos.getY(pos);
            final int z = BlockPos.getZ(pos);
            if (!World.isChunkLoadedBlockPos(x, z)) continue;
            if (World.getBlock(x, y, z) == BlockRegistry.AMETHYST_CLUSTER) {
                it.remove();
                if (breaks > 0) breaks--;
                reverts++;
                warn("Break at {} was reverted by the server - the break is being rejected, not mistimed", describe(pos));
            }
        }
    }

    private void expireSkips() {
        if (skipUntil.isEmpty()) return;
        skipUntil.values().removeIf(until -> tickCounter >= until);
    }

    private boolean isSkipped(final long pos) {
        final Long until = skipUntil.get(pos);
        return until != null && tickCounter < until;
    }

    // ------------------------------------------------------------------ movement

    private void beginNextWaypoint() {
        // Snapshot: the command thread can add to or clear this list between our size check and our
        // indexed read, and a config edit must not be able to throw out of the tick loop.
        final List<String> waypoints = List.copyOf(PLUGIN_CONFIG.movement.waypoints);
        if (waypoints.isEmpty()) return;
        if (visitedAnyWaypoint) {
            waypointIndex = (waypointIndex + 1) % waypoints.size();
        } else {
            waypointIndex = 0;
            visitedAnyWaypoint = true;
        }
        if (waypointIndex >= waypoints.size()) waypointIndex = 0;
        // Deliberately does not echo the waypoint text: these are real world coordinates and this
        // message goes to the log, the in-game alert and potentially Discord.
        final int[] wp = parseWaypoint(waypoints.get(waypointIndex));
        if (wp == null) {
            pause("waypoint #" + (waypointIndex + 1) + " is malformed (expected \"x y z\")");
            return;
        }
        dwellTicks = 0;
        mover.begin(mode(), wp[0], wp[1], wp[2]);
        state = State.MOVING;
    }

    /** Parses "x y z" into a triple, or null if it is not three integers. */
    public static int @Nullable [] parseWaypoint(final String raw) {
        if (raw == null) return null;
        final String[] parts = raw.trim().split("\\s+");
        if (parts.length != 3) return null;
        try {
            return new int[]{
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])
            };
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tool handling

    /**
     * Makes sure a usable pickaxe is in hand. Returns false when the caller should wait a tick,
     * either because a hotbar swap is in flight or because harvesting is paused.
     *
     * <p>Only ever called outside {@link State#ENGAGE}: changing the held item mid-break resets
     * destroy progress, because the interaction manager keys its "same target" test on the stack.
     */
    private boolean ensureTool() {
        final AutoAmethystConfig.Tool cfg = PLUGIN_CONFIG.tool;
        if (!cfg.enabled) return true;

        final ItemStack held = CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND);
        final boolean heldOk = isUsablePickaxe(held) && !needsSwap(held);

        final int slot = findBestPickaxe();
        if (slot < 0) {
            if (heldOk) {
                // holding the only healthy pickaxe there is
                toolWaitTicks = 0;
                return true;
            }
            if (cfg.pauseWhenNoTool) {
                pause("no usable pickaxe above " + cfg.swapAtDurabilityPercent + "% durability");
                return false;
            }
            toolWaitTicks = 0;
            if (isUsablePickaxe(held)) return true;
            if (tickCounter % 200 == 0) {
                warn("No usable pickaxe in inventory and nothing worth swapping to; idling");
            }
            return false;
        }

        if (heldOk) {
            // Fortune is worth a swap: a Fortune III pickaxe averages 8.8 shards per cluster
            // against 4 with no Fortune, so quietly grinding away with the wrong pickaxe would
            // silently halve the farm's output.
            final ItemStack best = CACHE.getPlayerCache().getPlayerInventory().get(slot);
            if (enchantLevel(best, EnchantmentRegistry.FORTUNE.get())
                <= enchantLevel(held, EnchantmentRegistry.FORTUNE.get())) {
                toolWaitTicks = 0;
                return true;
            }
        }

        if (slot >= 36 && slot <= 44 && CACHE.getPlayerCache().getHeldItemSlot() == slot - 36) {
            // already holding the best available pickaxe
            toolWaitTicks = 0;
            return true;
        }

        if (++toolWaitTicks > 60) {
            pause("pickaxe swap did not take effect after 60 ticks");
            return false;
        }
        if (toolWaitTicks > 1) return false; // a swap is already in flight, let it land

        if (slot >= 36 && slot <= 44) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(harvest().inputPriority)
                .actions(new SetHeldItem(slot - 36))
                .build());
        } else {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(harvest().inputPriority)
                .actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.from(0)), new SetHeldItem(0))
                .build());
        }
        toolSwaps++;
        info("Swapping pickaxe (slot {})", slot);
        return false;
    }

    private boolean isUsableHeldPickaxe() {
        if (!PLUGIN_CONFIG.tool.enabled) return true;
        return isUsablePickaxe(CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND));
    }

    /**
     * A pickaxe we are willing to swing at a cluster.
     *
     * <p>Silk Touch is disqualifying rather than merely suboptimal: it drops the cluster block and
     * zero shards, so a silk pickaxe turns the whole farm into a no-op that still burns durability.
     */
    private boolean isUsablePickaxe(final @Nullable ItemStack stack) {
        if (stack == null || stack == Container.EMPTY_STACK) return false;
        final ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data == null) return false;
        final ToolTag tag = data.toolTag();
        if (tag == null || tag.type() != ToolType.PICKAXE) return false;
        return !(PLUGIN_CONFIG.tool.refuseSilkTouch && hasEnchant(stack, EnchantmentRegistry.SILK_TOUCH.get()));
    }

    private boolean needsSwap(final ItemStack stack) {
        final int pct = durabilityPercent(stack);
        return pct >= 0 && pct <= PLUGIN_CONFIG.tool.swapAtDurabilityPercent;
    }

    /**
     * Best pickaxe in the inventory: healthy first, then most Fortune, then most durability left.
     * Returns -1 when nothing qualifies.
     */
    private int findBestPickaxe() {
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int bestSlot = -1;
        int bestFortune = -1;
        int bestRemaining = -1;
        for (int slot = 9; slot <= 44; slot++) {
            final ItemStack stack = inv.get(slot);
            if (!isUsablePickaxe(stack)) continue;
            if (needsSwap(stack)) continue;
            final int fortune = enchantLevel(stack, EnchantmentRegistry.FORTUNE.get());
            final int remaining = remainingDurability(stack);
            if (fortune > bestFortune || (fortune == bestFortune && remaining > bestRemaining)) {
                bestFortune = fortune;
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** Remaining durability, or {@link Integer#MAX_VALUE} for an undamageable item. */
    private static int remainingDurability(final ItemStack stack) {
        final ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data == null) return Integer.MAX_VALUE;
        final Integer max = stack.withAddedComponents(data.components())
            .getDataComponents().get(DataComponentTypes.MAX_DAMAGE);
        if (max == null || max <= 0) return Integer.MAX_VALUE;
        final Integer damage = stack.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        return Math.max(0, max - (damage == null ? 0 : damage));
    }

    /** Percent of durability left, or -1 when the item cannot be damaged. */
    private static int durabilityPercent(final ItemStack stack) {
        final ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data == null) return -1;
        final Integer max = stack.withAddedComponents(data.components())
            .getDataComponents().get(DataComponentTypes.MAX_DAMAGE);
        if (max == null || max <= 0) return -1;
        final Integer damage = stack.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        final int remaining = Math.max(0, max - (damage == null ? 0 : damage));
        return (int) ((remaining * 100L) / max);
    }

    private static int enchantLevel(final ItemStack stack, final @Nullable EnchantmentData enchantment) {
        if (enchantment == null) return 0;
        return BOT.getInteractions().getEnchantmentLevel(stack, enchantment);
    }

    private static boolean hasEnchant(final ItemStack stack, final @Nullable EnchantmentData enchantment) {
        return enchantLevel(stack, enchantment) > 0;
    }

    // ------------------------------------------------------------------ instrumentation

    /**
     * Counts shards carried. Deliberately skipped while a container window is open: the player
     * inventory cache does not re-sync until the container closes, so a count taken then is stale.
     */
    private int countShards() {
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId() != 0) {
            return shardsNow;
        }
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        final int shardId = ItemRegistry.AMETHYST_SHARD.id();
        int total = 0;
        for (int slot = 9; slot <= 45; slot++) {
            final ItemStack stack = inv.get(slot);
            if (stack == Container.EMPTY_STACK) continue;
            if (stack.getId() == shardId) total += stack.getAmount();
        }
        return total;
    }

    public int shardsGained() {
        if (shardsAtStart < 0 || shardsNow < 0) return 0;
        return Math.max(0, shardsNow - shardsAtStart);
    }

    public double shardsPerHour() {
        final long elapsed = System.currentTimeMillis() - sessionStartMs;
        if (sessionStartMs == 0 || elapsed < TimeUnit.SECONDS.toMillis(30)) return 0;
        return shardsGained() * (double) TimeUnit.HOURS.toMillis(1) / elapsed;
    }

    private void maybeLogSummary() {
        final int interval = PLUGIN_CONFIG.stats.summaryIntervalTicks;
        if (interval <= 0 || tickCounter % interval != 0) return;
        info("breaks={} shards={} shards/h={} mature-in-box={} skipped={} reverts={} toolSwaps={}",
            breaks, shardsGained(), String.format("%.1f", shardsPerHour()), matureInBox, skipped, reverts, toolSwaps);
    }

    /**
     * Appends one harvest record. Positions are written as offsets from the box minimum unless
     * absolute coordinates are explicitly enabled - a harvest log is exactly the kind of file that
     * ends up pasted into a chat or committed by accident, and these are real anarchy coordinates.
     */
    private void logHarvest(final int x, final int y, final int z) {
        if (!PLUGIN_CONFIG.stats.logToFile) return;
        final String line = Instant.now() + " break " + describe(BlockPos.asLong(x, y, z))
            + " breaks=" + breaks + " shards=" + shardsGained() + System.lineSeparator();
        final String file = PLUGIN_CONFIG.stats.logFile;
        // Off the tick thread. A blocking append per break is small, but the bot tick loop is the
        // one place where a stalled disk turns into missed movement packets.
        EXECUTOR.execute(() -> {
            try {
                final Path path = Path.of(file);
                final Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException | RuntimeException e) {
                warn("Could not write harvest log, disabling file logging: {}", e.getMessage());
                PLUGIN_CONFIG.stats.logToFile = false;
            }
        });
    }

    /** Renders a position for logs, box-relative by default so real coordinates stay out of files. */
    private String describe(final long pos) {
        final int x = BlockPos.getX(pos);
        final int y = BlockPos.getY(pos);
        final int z = BlockPos.getZ(pos);
        if (PLUGIN_CONFIG.stats.logRealCoords) {
            return "[" + x + ", " + y + ", " + z + "]";
        }
        final AutoAmethystConfig.Harvest h = harvest();
        return "box+[" + (x - h.minX) + ", " + (y - h.minY) + ", " + (z - h.minZ) + "]";
    }

    // ------------------------------------------------------------------ pause / status

    private void pause(final String reason) {
        if (paused && reason.equals(pauseReason)) return;
        paused = true;
        pauseReason = reason;
        releaseInputs();
        clearTarget();
        mover.reset();
        warn("Paused: {}", reason);
        inGameAlertActivePlayer("<red>Paused:<reset> " + reason);
    }

    /** Clears a pause so the module retries. Called from the command. */
    public void resume() {
        paused = false;
        pauseReason = "";
        consecutiveFailures = 0;
        state = State.SCAN;
        scanTimer.skip();
    }

    public boolean isPaused() { return paused; }
    public String pauseReason() { return pauseReason; }
    public String stateName() { return paused ? "PAUSED" : state.name(); }
    public long breaks() { return breaks; }
    public long skippedCount() { return skipped; }
    public long reverts() { return reverts; }
    public long toolSwaps() { return toolSwaps; }
    public int matureInBox() { return matureInBox; }
    public int reachableCount() { return reachable.size(); }
    public int waypointIndex() { return waypointIndex; }
}
