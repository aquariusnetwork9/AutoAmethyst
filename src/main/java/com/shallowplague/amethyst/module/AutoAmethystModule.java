package com.shallowplague.amethyst.module;

import com.github.rfresh2.EventConsumer;
import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.player.Position;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.Proxy;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
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
import static com.zenith.Globals.INVENTORY;

/**
 * Harvests amethyst from a fixed geode rig, collects what it drops, and empties into a shulker.
 *
 * <h2>What can ever be broken or placed</h2>
 * Two independent fences, because they fail in different ways:
 * <ul>
 *   <li>{@link HarvestPolicy} is an allowlist of specific block constants, re-checked every tick
 *       immediately before any click. It governs blocks we deliberately aim at.</li>
 *   <li>{@link PathfinderGuard} clamps the pathfinder's {@code allowBreak} and {@code allowPlace}
 *       off. It governs blocks the pathfinder would decide to remove on its own to clear a route -
 *       which, left at its defaults, it absolutely will.</li>
 * </ul>
 * The net result is that the only blocks this plugin can break are the enabled harvest stages and
 * shulker boxes, and the only block it can place is a shulker box, on a spot proven not to sit on a
 * budding amethyst growth face.
 *
 * <h2>Why breaking goes through inputs</h2>
 * See {@link BreakDriver}. Short version: the vanilla interaction manager does destroy progress,
 * sequence numbers and swing packets correctly, and hand-rolled break packets get vetoed.
 */
public class AutoAmethystModule extends Module {

    /** Refuse to scan an absurd box; a geode interior is tens of blocks across, not hundreds. */
    private static final int MAX_BOX_VOLUME = 262_144; // 64^3

    private enum State { SCAN, ENGAGE, SETTLE, COLLECTING, RETURNING, DEPOSITING, MOVING }

    private final Timer scanTimer = Timers.tickTimer();
    private final Timer censusTimer = Timers.tickTimer();
    private final MovementDriver mover = new MovementDriver(this);
    private final BreakDriver breaker = new BreakDriver();
    private final DropCollector collector = new DropCollector(this);
    private final DepositCycle depositCycle = new DepositCycle(this);
    private final PathfinderGuard pathGuard = new PathfinderGuard(PLUGIN_CONFIG.internal);

    private State state = State.SCAN;
    private boolean paused = false;
    private String pauseReason = "";
    private volatile boolean reloadRequested = false;

    /** Candidate harvest positions currently within reach, packed via {@link BlockPos#asLong}. */
    private final LongArrayList reachable = new LongArrayList();
    /** pos -> tick at which the position becomes eligible again. */
    private final Map<Long, Long> skipUntil = new HashMap<>();
    /** pos -> tick at which we believed we broke it, for anticheat revert detection. */
    private final Map<Long, Long> recentlyBroken = new HashMap<>();

    private long tickCounter = 0;
    private int settleTicks = 0;
    private int toolWaitTicks = 0;
    /** Consecutive ticks with no usable pickaxe, so a momentary desync cannot latch a pause. */
    private int noToolTicks = 0;
    private int dwellTicks = 0;
    private int waypointIndex = 0;
    /** Consecutive unreachable waypoints, so one bad one skips instead of stopping the farm. */
    private int failedWaypoints = 0;
    private boolean visitedAnyWaypoint = false;
    private int consecutiveFailures = 0;

    /** Stand position the collector is leashed to, so the bot cannot drift over hours. */
    private double anchorX, anchorY, anchorZ;
    private boolean anchorSet = false;

    // instrumentation
    private long breaks = 0;
    private long skipped = 0;
    private long reverts = 0;
    private long toolSwaps = 0;
    private long deposits = 0;
    private int shulkersStored = 0;
    private int matureInBox = 0;
    private long sessionStartMs = 0;

    // --- diagnosis: why is nothing happening? ---
    /** Harvestable blocks found in the local scan cube, before any filter. */
    private int diagFoundInCube = 0;
    /** Rejected because the interaction box centre was beyond reach. */
    private int diagRejectedFar = 0;
    /** Rejected because the position is on the skip cooldown. */
    private int diagRejectedSkipped = 0;
    /** Rejected by canEngage: out of reach at that rotation, or no line of sight. */
    private int diagRejectedNoLos = 0;
    /** Distance from the eye to the nearest harvestable block anywhere in the box. */
    private double diagNearestDist = -1;
    /** Box-relative offset of that nearest block, so logs carry no real coordinates. */
    private int diagNearestDx, diagNearestDy, diagNearestDz;
    private long lastWaypointStartTick = 0;
    private int yieldAtStart = -1;
    private int yieldNow = -1;
    private int yieldBeforeDeposit = 0;
    private int depositedTotal = 0;

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
        // Do this first. Everything else assumes the pathfinder cannot mine or build.
        pathGuard.apply(PLUGIN_CONFIG.movement.sprint);
        resetRuntime();
        sessionStartMs = System.currentTimeMillis();
        yieldAtStart = -1;
        breaks = skipped = reverts = toolSwaps = deposits = 0;
        depositedTotal = 0;
        info("Enabled. Harvest mode: {}, movement: {}, pathfinder break/place clamped off",
            mode(), movementMode());
        if (!PLUGIN_CONFIG.harvest.protectBuds) {
            warn("Bud protection is DISABLED - immature buds can be broken. "
                + "Each one throws away up to ~2h17m of growth on that face.");
        }
    }

    @Override
    public void onDisable() {
        mover.reset();
        collector.reset();
        depositCycle.reset();
        breaker.reset();
        resetRuntime();
        pathGuard.restore();
    }

    private void onBotStarting(final ClientBotTick.Starting event) {
        resetRuntime();
    }

    private void onBotStopped(final ClientBotTick.Stopped event) {
        mover.reset();
        collector.reset();
        depositCycle.reset();
        breaker.reset();
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
        settleTicks = 0;
        toolWaitTicks = 0;
        noToolTicks = 0;
        dwellTicks = 0;
        visitedAnyWaypoint = false;
        consecutiveFailures = 0;
        failedWaypoints = 0;
        anchorSet = false;
        reachable.clear();
        skipUntil.clear();
        recentlyBroken.clear();
        breaker.reset();
        collector.reset();
        depositCycle.reset();
        scanTimer.skip();
    }

    private AutoAmethystConfig.Harvest harvest() { return PLUGIN_CONFIG.harvest; }

    private HarvestPolicy.Mode mode() {
        return HarvestPolicy.parseMode(PLUGIN_CONFIG.harvest.mode);
    }

    private MovementDriver.Mode movementMode() {
        return MovementDriver.parseMode(PLUGIN_CONFIG.movement.mode);
    }

    /** The allowlist used for every harvest break. Bound to the current mode and config. */
    private boolean isHarvestable(final Block block) {
        return HarvestPolicy.isHarvestTarget(block, mode(), harvest());
    }

    private boolean isOurYield(final ItemStack stack) {
        return HarvestPolicy.isHarvestYield(stack, mode());
    }

    // ------------------------------------------------------------------ tick

    private void onTick(final ClientBotTick event) {
        tickCounter++;
        if (reloadRequested) {
            reloadRequested = false;
            mover.reset();
            collector.reset();
            depositCycle.reset();
            breaker.reset();
            resetRuntime();
            info("Reloaded configuration");
        }
        if (paused) return;
        // The 2b2t queue ticks the bot like any other world, but the inventory and chunks are not
        // the real ones. Deciding anything here produces confident nonsense - most memorably
        // "no non-Silk pickaxe above 10% durability" one second after queueing, latched as a sticky
        // pause that survived into the actual world with a perfectly good Fortune III pick in hand.
        if (Proxy.getInstance().isInQueue()) {
            releaseBreak();
            return;
        }
        if (!CACHE.getPlayerCache().isAlive()) {
            releaseBreak();
            return;
        }
        if (!validateSetup()) return;

        // The clamp is the only thing standing between the pathfinder and the budding blocks. If
        // something else has written those settings while we were running, stop rather than mine.
        if (!pathGuard.stillClamped()) {
            pathGuard.apply(PLUGIN_CONFIG.movement.sprint);
            if (!pathGuard.stillClamped()) {
                pause("pathfinder allowBreak/allowPlace could not be clamped off");
                return;
            }
        }

        if (!anchorSet) setAnchorHere();

        if (yieldAtStart < 0) {
            yieldAtStart = countYield();
            yieldNow = yieldAtStart;
        }

        try {
            switch (state) {
                case SCAN -> tickScan();
                case ENGAGE -> tickEngage();
                case SETTLE -> tickSettle();
                case COLLECTING -> tickCollecting();
                case RETURNING -> tickReturning();
                case DEPOSITING -> tickDepositing();
                case MOVING -> tickMoving();
            }
        } catch (final Exception e) {
            error("Unhandled error in harvest tick, pausing", e);
            pause("internal error: " + e.getClass().getSimpleName());
        }

        maybeLogSummary();
        maybeLogDebug();
    }

    /** Periodic "what am I doing and why" dump, on when {@code stats.debug} is set. */
    private void maybeLogDebug() {
        if (!PLUGIN_CONFIG.stats.debug) return;
        final int interval = Math.max(20, PLUGIN_CONFIG.stats.debugIntervalTicks);
        if (tickCounter % interval != 0) return;
        for (final String line : diagnose()) {
            info("[debug] {}", line);
        }
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
        final MovementDriver.Mode m = movementMode();
        if (m != MovementDriver.Mode.STATIONARY && PLUGIN_CONFIG.movement.waypoints.isEmpty()) {
            pause("movement mode " + m + " needs at least one waypoint");
            return false;
        }
        if (PLUGIN_CONFIG.deposit.enabled && !PLUGIN_CONFIG.deposit.posSet) {
            pause("deposit is on but no position is set - stand at the shulker and run 'deposit here'");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ states

    private void tickScan() {
        if (!ensureTool()) return;

        if (shouldDeposit()) {
            yieldBeforeDeposit = countYield();
            // The deposit run does its own pathing, so make sure a waypoint path is not still
            // running underneath it and fighting for the same pathfinder.
            mover.reset();
            // Hand it the stand position so it can walk the bot back afterwards. Without that the
            // bot finishes standing at the chest and, in stationary mode, never returns to the geode.
            depositCycle.begin(
                MathHelper.floorI(anchorX), MathHelper.floorI(anchorY), MathHelper.floorI(anchorZ));
            state = State.DEPOSITING;
            return;
        }

        if (scanTimer.tick(Math.max(1, harvest().rescanIntervalTicks))) {
            rescan();
        }

        // Collect, but never at the expense of harvesting.
        //
        // This used to run before the harvest check, so any drop within the leash took priority over
        // breaking. In a real geode a fair number of shards land somewhere the bot cannot get to -
        // inside the rig, under the floor, on the far side of a wall - and each one costs a full
        // chase timeout. With 200+ mature clusters standing there, the bot spent all its time
        // failing to reach shards and almost none of it harvesting.
        //
        // So: only chase when there is nothing to break in reach, or when the drop is close enough
        // to grab without really interrupting anything.
        if (PLUGIN_CONFIG.collection.enabled) {
            final double radius = reachable.isEmpty()
                ? PLUGIN_CONFIG.collection.maxDistance
                : PLUGIN_CONFIG.collection.opportunisticRadius;
            if (radius > 0
                && collector.hasWorkNear(this::isOurYield, anchorX, anchorY, anchorZ, radius)) {
                state = State.COLLECTING;
                return;
            }
        }

        if (reachable.isEmpty()) {
            if (movementMode() == MovementDriver.Mode.STATIONARY) return;
            if (++dwellTicks >= Math.max(0, PLUGIN_CONFIG.movement.dwellTicks)) {
                beginNextWaypoint();
            }
            return;
        }
        dwellTicks = 0;
        final long pos = pickNearestTarget();
        if (pos == Long.MIN_VALUE) {
            // Every candidate was rejected. This used to clear the list silently, which made the
            // status read "in reach: 0" with "skipped: 0" and gave no hint that anything had been
            // considered at all - a line-of-sight problem was indistinguishable from an empty geode.
            if (diagRejectedNoLos > 0) {
                debug("Rejected all {} in-reach targets: no line of sight from here", diagRejectedNoLos);
            }
            reachable.clear();
            return;
        }
        breaker.begin(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        state = State.ENGAGE;
    }

    private void tickEngage() {
        final long pos = breaker.target();
        final BreakDriver.Status status = breaker.tick(
            this::isHarvestable, effectiveReach(), harvest().requireLineOfSight,
            harvest().maxBreakTicks, harvest().inputPriority);

        switch (status) {
            case BUSY -> { }
            case BROKEN -> onBreakSucceeded(pos);
            case BLOCKED -> {
                // A block that will not break in reasonable time gets parked so the loop cannot
                // spin on it; anything else is transient and just needs a rescan.
                final String reason = breaker.blockedReason();
                if (reason.contains("no progress")) {
                    skipTarget(pos, reason);
                } else {
                    abandonTarget(reason);
                }
            }
        }
    }

    private void tickSettle() {
        if (--settleTicks > 0) return;
        state = State.SCAN;
    }

    private void tickCollecting() {
        final DropCollector.Status status = collector.tick(
            this::isOurYield, PLUGIN_CONFIG.collection, anchorX, anchorY, anchorZ,
            harvest().inputPriority);
        switch (status) {
            case CHASING, RETURNING, IDLE -> { }
            case DONE -> {
                yieldNow = countYield();
                state = State.RETURNING;
            }
            case FAILED -> {
                warn("Drop collection gave up: {}", collector.failReason());
                state = State.RETURNING;
            }
        }
    }

    private void tickReturning() {
        final DropCollector.Status status = collector.tickReturn(
            PLUGIN_CONFIG.collection, anchorX, anchorY, anchorZ, harvest().inputPriority);
        switch (status) {
            case RETURNING, CHASING, IDLE -> { }
            case DONE -> {
                scanTimer.skip();
                state = State.SCAN;
            }
            case FAILED -> {
                // not fatal: we are still somewhere sane, just re-anchor and carry on
                warn("Could not walk back to the stand position: {}", collector.failReason());
                setAnchorHere();
                scanTimer.skip();
                state = State.SCAN;
            }
        }
    }

    private void tickDepositing() {
        final DepositCycle.Status status = depositCycle.tick(
            this::isOurYield, PLUGIN_CONFIG.deposit, effectiveReach(),
            harvest().requireLineOfSight, harvest().inputPriority);
        switch (status) {
            case BUSY -> { }
            case DONE -> {
                deposits++;
                final int after = countYield();
                // Carried yield drops when a deposit succeeds, so bank the difference or the
                // session total would read as a loss every time the shulker is filled.
                if (yieldBeforeDeposit > after) depositedTotal += yieldBeforeDeposit - after;
                yieldNow = after;
                yieldAtStart = after;
                shulkersStored = depositCycle.shulkersStored();
                // The run walks itself back to the stand position, so re-anchor on where we
                // actually ended up rather than assuming it succeeded.
                setAnchorHere();
                collector.clearUnreachable();
                scanTimer.skip();
                state = State.SCAN;
                // If the run freed nothing, running it again immediately would just loop. The usual
                // cause is an inventory full of filled shulkers with nowhere to put them.
                if (freeInventorySlots() <= Math.max(0, PLUGIN_CONFIG.deposit.triggerFreeSlots)) {
                    pause(PLUGIN_CONFIG.deposit.chestSet
                        ? "deposit finished but the inventory is still full - check the storage chest"
                        : "inventory is full of filled shulkers and no storage chest is set "
                          + "(stand by a chest and run 'deposit chest here')");
                }
            }
            case FAILED -> pause("deposit failed: " + depositCycle.failReason());
        }
    }

    private void tickMoving() {
        final MovementDriver.Status status = mover.tick(
            movementMode(), PLUGIN_CONFIG.movement, harvest().inputPriority);
        switch (status) {
            case BUSY -> { }
            case ARRIVED -> {
                dwellTicks = 0;
                failedWaypoints = 0;
                setAnchorHere();
                // drops that were unreachable from the last stand position may well be reachable
                // from this one
                collector.clearUnreachable();
                scanTimer.skip();
                state = State.SCAN;
            }
            case FAILED -> {
                // One unroutable waypoint must not stop the farm. Chunks unload, mobs stand in
                // doorways, and a stand position recorded on a ladder may not be standable at all -
                // none of which says anything about the other waypoints. Try the next one, and only
                // give up once a full cycle has failed.
                warn("Waypoint {} unreachable: {}", waypointIndex + 1, mover.failReason());
                if (++failedWaypoints >= Math.max(1, PLUGIN_CONFIG.movement.waypoints.size())) {
                    pause("no waypoint could be reached (last: " + mover.failReason() + ")");
                    return;
                }
                setAnchorHere();
                dwellTicks = Integer.MAX_VALUE / 2; // move on immediately rather than dwelling
                state = State.SCAN;
            }
        }
    }

    // ------------------------------------------------------------------ deposit trigger

    private boolean shouldDeposit() {
        final AutoAmethystConfig.Deposit cfg = PLUGIN_CONFIG.deposit;
        if (!cfg.enabled || !cfg.posSet) return false;
        return freeInventorySlots() <= Math.max(0, cfg.triggerFreeSlots);
    }

    private int freeInventorySlots() {
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int free = 0;
        for (int slot = 9; slot <= 44; slot++) {
            if (inv.get(slot) == Container.EMPTY_STACK) free++;
        }
        return free;
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
        yieldNow = countYield();

        final AutoAmethystConfig.Harvest h = harvest();
        final double reach = effectiveReach();
        final int r = (int) Math.ceil(reach) + 1;
        final int px = MathHelper.floorI(BOT.getX());
        final int py = MathHelper.floorI(BOT.getEyeY());
        final int pz = MathHelper.floorI(BOT.getZ());

        final int loX = Math.max(h.minX, px - r), hiX = Math.min(h.maxX, px + r);
        final int loY = Math.max(h.minY, py - r), hiY = Math.min(h.maxY, py + r);
        final int loZ = Math.max(h.minZ, pz - r), hiZ = Math.min(h.maxZ, pz + r);

        diagFoundInCube = 0;
        diagRejectedFar = 0;
        diagRejectedSkipped = 0;
        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = loY; y <= hiY; y++) {
                    if (!isHarvestable(World.getBlock(x, y, z))) continue;
                    diagFoundInCube++;
                    final long pos = BlockPos.asLong(x, y, z);
                    if (isSkipped(pos)) { diagRejectedSkipped++; continue; }
                    if (!withinReach(x, y, z, reach)) { diagRejectedFar++; continue; }
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
        double nearestSq = Double.MAX_VALUE;
        int nx = 0, ny = 0, nz = 0;
        final double ex = BOT.getX(), ey = BOT.getEyeY(), ez = BOT.getZ();
        for (int x = h.minX; x <= h.maxX; x++) {
            for (int z = h.minZ; z <= h.maxZ; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = h.minY; y <= h.maxY; y++) {
                    if (!isHarvestable(World.getBlock(x, y, z))) continue;
                    count++;
                    // Tracked so "nothing in reach" can say how far away the nearest one actually
                    // is, which is the difference between "wrong waypoints" and "empty geode".
                    final double d = MathHelper.distanceSq3d(x + 0.5, y + 0.5, z + 0.5, ex, ey, ez);
                    if (d < nearestSq) {
                        nearestSq = d;
                        nx = x; ny = y; nz = z;
                    }
                }
            }
        }
        matureInBox = count;
        if (count > 0) {
            diagNearestDist = Math.sqrt(nearestSq);
            diagNearestDx = nx - h.minX;
            diagNearestDy = ny - h.minY;
            diagNearestDz = nz - h.minZ;
        } else {
            diagNearestDist = -1;
        }
    }

    /**
     * A human readable answer to "why is the bot standing there doing nothing".
     *
     * <p>Deliberately reports box-relative offsets rather than world coordinates, so the output is
     * safe to paste anywhere.
     */
    public List<String> diagnose() {
        final List<String> out = new java.util.ArrayList<>();
        out.add("state=" + stateName() + " mode=" + mode() + " movement=" + movementMode());
        if (paused) out.add("PAUSED: " + pauseReason);
        out.add("mature in box=" + matureInBox
            + "  found in scan cube=" + diagFoundInCube
            + "  in reach=" + reachable.size());
        out.add("rejected: too far=" + diagRejectedFar
            + "  on cooldown=" + diagRejectedSkipped
            + "  no line of sight=" + diagRejectedNoLos);
        if (diagNearestDist >= 0) {
            out.add(String.format("nearest harvestable is %.2f blocks away at box+[%d, %d, %d] (reach is %.2f)",
                diagNearestDist, diagNearestDx, diagNearestDy, diagNearestDz, effectiveReach()));
            if (diagNearestDist > effectiveReach() && reachable.isEmpty()) {
                out.add("=> nothing is within reach of this stand position; the bot should be moving."
                    + " Check the waypoints actually put it next to the clusters.");
            }
        } else {
            out.add("no harvestable blocks anywhere in the box (is the box right? is the chunk loaded?)");
        }
        if (diagRejectedNoLos > 0 && reachable.isEmpty()) {
            out.add("=> everything in reach was rejected for line of sight."
                + " If the clusters are plainly visible, try 'autoamethyst los off'.");
        }
        out.add("movement: " + mover.describe()
            + "  waypoint=" + (waypointIndex + 1) + "/" + PLUGIN_CONFIG.movement.waypoints.size()
            + "  dwell=" + dwellTicks + "/" + PLUGIN_CONFIG.movement.dwellTicks
            + "  ticks since leg start=" + (tickCounter - lastWaypointStartTick));
        out.add("collection=" + (PLUGIN_CONFIG.collection.enabled ? "on" : "off")
            + "  deposit=" + (PLUGIN_CONFIG.deposit.enabled ? "on" : "off")
            + "  free slots=" + freeInventorySlots()
            + "  pathfinder clamped=" + pathfinderClamped());
        final ItemStack held = CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND);
        out.add("held tool ok=" + HarvestPolicy.isCorrectPickaxe(held, mode())
            + "  fortune=" + HarvestPolicy.enchantLevel(held, EnchantmentRegistry.FORTUNE.get())
            + "  silk=" + HarvestPolicy.hasEnchant(held, EnchantmentRegistry.SILK_TOUCH.get()));
        return out;
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
        final double reach = effectiveReach();
        diagRejectedNoLos = 0;
        final LongIterator it = reachable.iterator();
        while (it.hasNext()) {
            final long pos = it.nextLong();
            final int x = BlockPos.getX(pos);
            final int y = BlockPos.getY(pos);
            final int z = BlockPos.getZ(pos);
            if (!isHarvestable(World.getBlock(x, y, z))) continue;
            if (isSkipped(pos)) continue;
            final Position c = World.blockInteractionCenter(x, y, z);
            final Vector2f rot = RotationHelper.rotationTo(c.x(), c.y(), c.z());
            if (!BreakDriver.canEngage(x, y, z, rot, reach, harvest().requireLineOfSight)) {
                diagRejectedNoLos++;
                continue;
            }
            final double d = MathHelper.distanceSq3d(c.x(), c.y(), c.z(), BOT.getX(), BOT.getEyeY(), BOT.getZ());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
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

    private void onBreakSucceeded(final long pos) {
        breaks++;
        consecutiveFailures = 0;
        recentlyBroken.put(pos, tickCounter);
        reachable.rem(pos);
        settleTicks = Math.max(1, harvest().interBreakDelayTicks);
        state = State.SETTLE;
        logHarvest(pos);
    }

    /** Target is no longer valid but is not the target's fault; re-scan and pick another. */
    private void abandonTarget(final String reason) {
        debug("Abandoned target: {}", reason);
        releaseBreak();
        if (++consecutiveFailures >= Math.max(1, harvest().maxConsecutiveFailures)) {
            pause("gave up after " + consecutiveFailures + " consecutive failed targets (last: " + reason + ")");
            return;
        }
        scanTimer.skip();
        state = State.SCAN;
    }

    /** Target is bad; blacklist it for a while so the loop cannot spin on it. */
    private void skipTarget(final long pos, final String reason) {
        skipped++;
        skipUntil.put(pos, tickCounter + Math.max(20, harvest().skipCooldownTicks));
        reachable.rem(pos);
        warn("Skipping {}: {}", describe(pos), reason);
        releaseBreak();
        scanTimer.skip();
        state = State.SCAN;
    }

    private void releaseBreak() {
        breaker.release(harvest().inputPriority);
        breaker.reset();
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
            if (isHarvestable(World.getBlock(x, y, z))) {
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

    private void setAnchorHere() {
        anchorX = CACHE.getPlayerCache().getX();
        anchorY = CACHE.getPlayerCache().getY();
        anchorZ = CACHE.getPlayerCache().getZ();
        anchorSet = true;
    }

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
        lastWaypointStartTick = tickCounter;
        debug("Moving to waypoint {}/{}", waypointIndex + 1, waypoints.size());
        mover.begin(movementMode(), wp[0], wp[1], wp[2]);
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
     * Makes sure the right pickaxe for the mode is in hand. Returns false when the caller should
     * wait a tick, either because a swap is in flight or because harvesting is paused.
     *
     * <p>Never called during {@link State#ENGAGE}: changing the held item mid-break resets destroy
     * progress, because the interaction manager keys its "same target" test on the stack.
     */
    private boolean ensureTool() {
        final AutoAmethystConfig.Tool cfg = PLUGIN_CONFIG.tool;
        if (!cfg.enabled) return true;
        final HarvestPolicy.Mode m = mode();

        final ItemStack held = CACHE.getPlayerCache().getEquipment(EquipmentSlot.MAIN_HAND);
        final boolean heldOk = HarvestPolicy.isCorrectPickaxe(held, m) && !needsSwap(held);

        final int slot = findBestPickaxe(m);
        if (slot < 0) {
            if (heldOk) {
                toolWaitTicks = 0;
                noToolTicks = 0;
                return true;
            }
            // An inventory that reads as entirely empty has not been synced yet - on a reconnect or
            // a world change the cache is briefly blank. Never conclude anything from that.
            if (inventoryLooksUnsynced()) {
                noToolTicks = 0;
                return false;
            }
            // Only pause once the condition has held for a while. Pausing is sticky and needs a
            // manual resume, so a momentary desync must not be able to switch the farm off for
            // hours - which is exactly what happened when a queue tick reported an empty inventory.
            if (cfg.pauseWhenNoTool && ++noToolTicks >= Math.max(20, cfg.noToolGraceTicks)) {
                pause(m == HarvestPolicy.Mode.SILK
                    ? "no Silk Touch pickaxe above " + cfg.swapAtDurabilityPercent + "% durability"
                    : "no non-Silk pickaxe above " + cfg.swapAtDurabilityPercent + "% durability");
                return false;
            }
            toolWaitTicks = 0;
            if (HarvestPolicy.isCorrectPickaxe(held, m)) return true;
            if (tickCounter % 200 == 0) warn("No suitable pickaxe in inventory; waiting");
            return false;
        }
        noToolTicks = 0;

        if (heldOk) {
            // Fortune is worth a swap in SHARDS mode: Fortune III averages 8.8 shards per cluster
            // against 4 with none, so grinding away with the wrong pickaxe silently halves output.
            // In SILK mode Fortune does nothing, so leave a working pickaxe alone.
            if (m == HarvestPolicy.Mode.SILK) {
                toolWaitTicks = 0;
                return true;
            }
            final ItemStack best = CACHE.getPlayerCache().getPlayerInventory().get(slot);
            if (HarvestPolicy.enchantLevel(best, EnchantmentRegistry.FORTUNE.get())
                <= HarvestPolicy.enchantLevel(held, EnchantmentRegistry.FORTUNE.get())) {
                toolWaitTicks = 0;
                return true;
            }
        }

        if (slot >= 36 && slot <= 44 && CACHE.getPlayerCache().getHeldItemSlot() == slot - 36) {
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

    /**
     * True when the inventory cache looks like it has not been populated yet.
     *
     * <p>A real bot always has something in 36 slots' worth of inventory; entirely empty means the
     * cache is blank, which happens briefly on connect, on a world change and throughout the 2b2t
     * queue. Concluding "you have no pickaxe" from that is how a farm switches itself off.
     */
    private boolean inventoryLooksUnsynced() {
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) {
            if (inv.get(slot) != Container.EMPTY_STACK) return false;
        }
        return true;
    }

    private boolean needsSwap(final @Nullable ItemStack stack) {
        if (stack == null || stack == Container.EMPTY_STACK) return true;
        final int pct = durabilityPercent(stack);
        return pct >= 0 && pct <= PLUGIN_CONFIG.tool.swapAtDurabilityPercent;
    }

    /**
     * Best pickaxe for the mode: correct Silk Touch state, healthy, then most Fortune, then most
     * durability. Returns -1 when nothing qualifies.
     */
    private int findBestPickaxe(final HarvestPolicy.Mode m) {
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int bestSlot = -1;
        int bestFortune = -1;
        int bestRemaining = -1;
        for (int slot = 9; slot <= 44; slot++) {
            final ItemStack stack = inv.get(slot);
            if (!HarvestPolicy.isCorrectPickaxe(stack, m)) continue;
            if (needsSwap(stack)) continue;
            final int fortune = HarvestPolicy.enchantLevel(stack, EnchantmentRegistry.FORTUNE.get());
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

    // ------------------------------------------------------------------ instrumentation

    /**
     * Counts carried yield. Deliberately skipped while a container window is open: the player
     * inventory cache does not re-sync until the container closes, so a count taken then is stale.
     */
    private int countYield() {
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId() != 0) {
            return yieldNow;
        }
        final List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int total = 0;
        for (int slot = 9; slot <= 45; slot++) {
            final ItemStack stack = inv.get(slot);
            if (stack == Container.EMPTY_STACK) continue;
            if (isOurYield(stack)) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Yield collected this session.
     *
     * <p>Counts what is carried, so a deposit run that moves stacks into a shulker would read as a
     * loss. The running total is therefore accumulated across deposits rather than taken as a raw
     * difference.
     */
    public int yieldGained() {
        if (yieldAtStart < 0 || yieldNow < 0) return depositedTotal;
        return Math.max(0, yieldNow - yieldAtStart) + depositedTotal;
    }

    public double yieldPerHour() {
        final long elapsed = System.currentTimeMillis() - sessionStartMs;
        if (sessionStartMs == 0 || elapsed < TimeUnit.SECONDS.toMillis(30)) return 0;
        return yieldGained() * (double) TimeUnit.HOURS.toMillis(1) / elapsed;
    }

    private void maybeLogSummary() {
        final int interval = PLUGIN_CONFIG.stats.summaryIntervalTicks;
        if (interval <= 0 || tickCounter % interval != 0) return;
        info("breaks={} yield={} per-hour={} mature-in-box={} skipped={} reverts={} toolSwaps={} deposits={}",
            breaks, yieldGained(), String.format("%.1f", yieldPerHour()), matureInBox,
            skipped, reverts, toolSwaps, deposits);
    }

    /**
     * Appends one harvest record. Positions are written as offsets from the box minimum unless
     * absolute coordinates are explicitly enabled - a harvest log is exactly the kind of file that
     * ends up pasted into a chat or committed by accident, and these are real anarchy coordinates.
     */
    private void logHarvest(final long pos) {
        if (!PLUGIN_CONFIG.stats.logToFile) return;
        final String line = Instant.now() + " break " + describe(pos)
            + " breaks=" + breaks + " yield=" + yieldGained() + System.lineSeparator();
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
        releaseBreak();
        mover.reset();
        collector.reset();
        depositCycle.reset();
        warn("Paused: {}", reason);
        inGameAlertActivePlayer("<red>Paused:<reset> " + reason);
    }

    /** Clears a pause so the module retries. Called from the command. */
    public void resume() {
        paused = false;
        pauseReason = "";
        consecutiveFailures = 0;
        state = State.SCAN;
        anchorSet = false;
        scanTimer.skip();
    }

    public boolean isPaused() { return paused; }
    public String pauseReason() { return pauseReason; }
    public String stateName() {
        if (paused) return "PAUSED";
        if (state == State.DEPOSITING) return "DEPOSIT:" + depositCycle.phaseName();
        return state.name();
    }
    public long breaks() { return breaks; }
    public long skippedCount() { return skipped; }
    public long reverts() { return reverts; }
    public long toolSwaps() { return toolSwaps; }
    public long deposits() { return deposits; }
    public int shulkersStored() { return shulkersStored; }
    public int matureInBox() { return matureInBox; }
    public int reachableCount() { return reachable.size(); }
    public boolean pathfinderClamped() { return pathGuard.isApplied() && pathGuard.stillClamped(); }
}
