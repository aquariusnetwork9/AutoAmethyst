package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.Position;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/**
 * Empties the bot's harvest into a shulker box, swaps the shulker when it fills, hauls the full ones
 * to a storage chest, and walks back to the geode.
 *
 * <p>The full run:
 * <ol>
 *   <li>walk to the deposit shulker, open it, move the harvest in</li>
 *   <li>when it will not take any more: close, break it (a shulker keeps its contents), pick it up,
 *       place a fresh empty one, carry on</li>
 *   <li>when the inventory has nothing left to deposit: haul every full shulker to the storage
 *       chest and leave them there</li>
 *   <li>walk back to the stand position the harvest loop was using</li>
 * </ol>
 *
 * <p>That last step is not cosmetic. Without it the bot finishes a deposit standing at the chest,
 * re-anchors there, and in stationary mode simply never returns to the geode - the farm looks alive
 * and produces nothing.
 *
 * <h2>Transfers use ClickItem, not ShiftClick</h2>
 * Stock Zenith's {@code ShiftClick} sends an <b>empty</b> {@code changedSlots} map - its own source
 * comment flags this as a likely anticheat problem. On a server that validates click packets it is
 * rejected, and the failure is silent: the transfer simply never happens. So every move here is a
 * pair of {@link ClickItem} clicks, pick the stack up then put it down.
 *
 * <h2>What it will not do</h2>
 * The storage chest is never broken. The only blocks this plugin can break are harvest targets and
 * shulker boxes, and the chest is neither - if the chest position holds something unexpected the run
 * fails with a reason rather than clearing it. Placement is validated through
 * {@link HarvestPolicy#isSafeToPlaceAt} every time, so a shulker can never end up on a budding
 * amethyst growth face.
 */
public final class DepositCycle {

    public enum Status { BUSY, DONE, FAILED }

    /** Ticks between path requests, so async path calculation is not re-triggered every tick. */
    private static final int REPATH_COOLDOWN_TICKS = 40;

    /** How many times a window that closes mid-transfer will be re-opened before giving up. */
    private static final int MAX_REOPENS = 5;

    private enum Phase {
        IDLE,
        APPROACH,
        ENSURE_CONTAINER,
        PLACE_SHULKER,
        OPEN,
        TRANSFER,
        CLOSE_FULL,
        BREAK_SHULKER,
        COLLECT_SHULKER,
        CLOSE_DONE,
        CHEST_APPROACH,
        CHEST_OPEN,
        CHEST_TRANSFER,
        CHEST_CLOSE,
        RETURN
    }

    private final Object owner;
    private final BreakDriver breaker = new BreakDriver();
    private final DropCollector shulkerCollector;
    /** Reused rather than reallocated each tick; only two fields are ever set on it. */
    private final AutoAmethystConfig.Collection collectCfg = new AutoAmethystConfig.Collection();

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int actionCooldown = 0;
    private int stallCount = 0;
    private int lastTransferCount = -1;
    private int placeAttempts = 0;
    private int repathCooldown = 0;
    private int reopens = 0;
    private int shulkersStored = 0;
    private int fullShulkersBeforeChest = 0;
    private String failReason = "";

    /** Where the harvest loop was standing, so the run can put the bot back afterwards. */
    private int returnX, returnY, returnZ;

    public DepositCycle(final Object owner) {
        this.owner = owner;
        this.shulkerCollector = new DropCollector(owner);
    }

    public String failReason() { return failReason; }
    public boolean isRunning() { return phase != Phase.IDLE; }
    public String phaseName() { return phase.name(); }
    public int shulkersStored() { return shulkersStored; }

    /** @param returnTo the stand position to walk back to when the run finishes */
    public void begin(final int returnToX, final int returnToY, final int returnToZ) {
        reset();
        this.returnX = returnToX;
        this.returnY = returnToY;
        this.returnZ = returnToZ;
        phase = Phase.APPROACH;
    }

    public void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        actionCooldown = 0;
        stallCount = 0;
        lastTransferCount = -1;
        placeAttempts = 0;
        repathCooldown = 0;
        reopens = 0;
        failReason = "";
        breaker.reset();
        shulkerCollector.reset();
    }

    public Status tick(final Predicate<ItemStack> yield, final AutoAmethystConfig.Deposit cfg,
                       final double reach, final boolean requireLineOfSight, final int priority) {
        if (phase == Phase.IDLE) return Status.DONE;
        if (actionCooldown > 0) {
            actionCooldown--;
            return Status.BUSY;
        }
        if (++phaseTicks > Math.max(40, cfg.phaseTimeoutTicks)) {
            return fail("deposit phase " + phase + " timed out");
        }

        return switch (phase) {
            case APPROACH -> approach(cfg.x, cfg.y, cfg.z, cfg, reach, Phase.ENSURE_CONTAINER);
            case ENSURE_CONTAINER -> tickEnsureContainer(cfg);
            case PLACE_SHULKER -> tickPlaceShulker(cfg);
            case OPEN -> open(cfg.x, cfg.y, cfg.z, cfg, reach, requireLineOfSight, priority, Phase.TRANSFER);
            case TRANSFER -> transfer(yield, cfg, Phase.CLOSE_DONE, true);
            case CLOSE_FULL -> close(Phase.BREAK_SHULKER);
            case BREAK_SHULKER -> tickBreakShulker(cfg, reach, requireLineOfSight, priority);
            case COLLECT_SHULKER -> tickCollectShulker(cfg, priority);
            case CLOSE_DONE -> close(needsChestTrip(cfg) ? Phase.CHEST_APPROACH : Phase.RETURN);
            case CHEST_APPROACH -> approach(cfg.chestX, cfg.chestY, cfg.chestZ, cfg, reach, Phase.CHEST_OPEN);
            case CHEST_OPEN -> open(cfg.chestX, cfg.chestY, cfg.chestZ, cfg, reach, requireLineOfSight,
                                    priority, Phase.CHEST_TRANSFER);
            case CHEST_TRANSFER -> transfer(DepositCycle::isFullShulkerItem, cfg, Phase.CHEST_CLOSE, false);
            case CHEST_CLOSE -> close(Phase.RETURN);
            case RETURN -> tickReturn(cfg, reach);
            case IDLE -> Status.DONE;
        };
    }

    // ------------------------------------------------------------------ shared movement / window

    /**
     * Walks to within reach of a block, then moves to {@code next}.
     *
     * <p>Safe to use the pathfinder here: {@link PathfinderGuard} has already clamped
     * {@code allowBreak} and {@code allowPlace} off, so it can only walk, never mine or bridge a
     * route. The repath cooldown matters because path calculation is asynchronous - re-issuing
     * every tick while {@code isActive()} still reads false produces a storm of instantly-satisfied
     * requests and the bot never actually goes anywhere.
     */
    private Status approach(final int x, final int y, final int z, final AutoAmethystConfig.Deposit cfg,
                            final double reach, final Phase next) {
        final Position centre = World.blockInteractionCenter(x, y, z);
        final double dist = distanceToEye(centre.x(), centre.y(), centre.z());
        if (dist <= reach - 0.5) {
            if (BARITONE.isActive()) BARITONE.stop();
            advance(next);
            return Status.BUSY;
        }
        if (dist > cfg.maxTravelDistance) {
            return fail("target is " + (int) dist + " blocks away, over the "
                + (int) cfg.maxTravelDistance + " limit");
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (!BARITONE.isActive()) {
            BARITONE.pathTo(x, y + 1, z);
            repathCooldown = REPATH_COOLDOWN_TICKS;
        }
        return Status.BUSY;
    }

    private Status open(final int x, final int y, final int z, final AutoAmethystConfig.Deposit cfg,
                        final double reach, final boolean requireLineOfSight, final int priority,
                        final Phase next) {
        if (openContainerId() != 0) {
            lastTransferCount = -1;
            stallCount = 0;
            advance(next);
            return Status.BUSY;
        }
        final Position centre = World.blockInteractionCenter(x, y, z);
        final Vector2f rot = RotationHelper.rotationTo(centre.x(), centre.y(), centre.z());
        if (!BreakDriver.canEngage(x, y, z, rot, reach, requireLineOfSight)) {
            return fail("cannot see the container from here");
        }
        INPUTS.submit(InputRequest.builder()
            .owner(owner)
            .input(Input.builder()
                .hand(Hand.MAIN_HAND)
                .clickRequiresRotation(true)
                .clickTarget(new ClickTarget.BlockPosition(x, y, z))
                .rightClick(true)
                .build())
            .yaw(rot.getX())
            .pitch(rot.getY())
            .priority(priority)
            .build());
        actionCooldown = Math.max(1, cfg.stepSettleTicks);
        return Status.BUSY;
    }

    /**
     * Moves one stack per step from the player half into the container half: left click to pick up,
     * left click a destination to put down. Paced by {@code stepSettleTicks} - an unthrottled stream
     * of container clicks is itself something servers close windows over.
     *
     * @param allowReplace whether a full container should trigger the break-and-replace path. False
     *                     for the storage chest, which we neither break nor replace.
     */
    private Status transfer(final Predicate<ItemStack> what, final AutoAmethystConfig.Deposit cfg,
                            final Phase onDone, final boolean allowReplace) {
        final Container container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (container.getContainerId() == 0) {
            // A laggy server will close the window out from under a deposit mid-transfer. Treating
            // that as fatal strands a half-emptied inventory and pauses the farm for something that
            // just needs the window opening again, so re-open a bounded number of times.
            if (++reopens > MAX_REOPENS) {
                return fail("the container window kept closing mid-transfer (" + (reopens - 1) + " re-opens)");
            }
            advance(allowReplace ? Phase.OPEN : Phase.CHEST_OPEN);
            return Status.BUSY;
        }

        // If the mouse is holding something, put it down before doing anything else.
        final ItemStack mouse = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        if (mouse != Container.EMPTY_STACK) {
            final int dest = findDestinationSlot(container, mouse);
            if (dest < 0) {
                final int back = findFreePlayerSlot(container);
                if (back < 0) return fail("inventory and container are both full with an item in hand");
                click(container.getContainerId(), back, cfg);
                return Status.BUSY;
            }
            click(container.getContainerId(), dest, cfg);
            return Status.BUSY;
        }

        final int playerStart = container.getSize() - 36;
        final int source = findPlayerSlotMatching(container, playerStart, what);
        if (source < 0) {
            advance(onDone);
            return Status.BUSY;
        }
        final int dest = findDestinationSlot(container, container.getItemStack(source));
        if (dest < 0) {
            if (!allowReplace) {
                return fail("the storage chest is full - empty it and resume");
            }
            if (!cfg.replaceWhenFull) {
                return fail("deposit shulker is full and replaceWhenFull is off");
            }
            advance(Phase.CLOSE_FULL);
            return Status.BUSY;
        }

        // Stall detection: if the count of movable items in the player half stops falling, the
        // server is rejecting our clicks and retrying forever would just spam it.
        final int remaining = countPlayerMatching(container, playerStart, what);
        if (lastTransferCount >= 0 && remaining >= lastTransferCount) {
            if (++stallCount > Math.max(3, cfg.maxStalledSteps)) {
                return fail("container transfers are not taking effect (server rejecting clicks?)");
            }
        } else {
            stallCount = 0;
        }
        lastTransferCount = remaining;

        click(container.getContainerId(), source, cfg);
        return Status.BUSY;
    }

    private Status close(final Phase next) {
        if (openContainerId() != 0) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(owner)
                .actions(new CloseContainer())
                .priority(0)
                .build());
            return Status.BUSY;
        }
        advance(next);
        return Status.BUSY;
    }

    // ------------------------------------------------------------------ shulker phases

    private Status tickEnsureContainer(final AutoAmethystConfig.Deposit cfg) {
        final Block at = World.getBlock(cfg.x, cfg.y, cfg.z);
        if (HarvestPolicy.isShulkerBlock(at)) {
            placeAttempts = 0; // a shulker is standing; the budget is per-placement, not per-run
            advance(Phase.OPEN);
            return Status.BUSY;
        }
        if (at.isAir()) {
            advance(Phase.PLACE_SHULKER);
            return Status.BUSY;
        }
        // Something else is sitting in the deposit spot. Never break it - the only blocks this
        // plugin may break are harvest targets and shulkers, and guessing here is how a farm loses
        // a budding block.
        return fail("deposit position holds " + at.name() + ", which this plugin will not break");
    }

    private Status tickPlaceShulker(final AutoAmethystConfig.Deposit cfg) {
        // Bounded, because ENSURE_CONTAINER sends us back here whenever the spot is still air. A
        // placement that silently does nothing would otherwise loop between the two phases forever,
        // and advance() resets the phase timer each time so the timeout would never fire.
        if (++placeAttempts > Math.max(1, cfg.maxPlaceAttempts)) {
            return fail("could not place a shulker at the deposit position after "
                + (placeAttempts - 1) + " attempts");
        }
        // Re-checked at the moment of placing, not just when the position was configured. The rig
        // grows: a bud can appear next to the deposit spot between one cycle and the next, and
        // placing on a budding amethyst face stops that face producing for as long as it is there.
        if (!HarvestPolicy.isSafeToPlaceAt(cfg.x, cfg.y, cfg.z)) {
            return fail("refusing to place at the deposit position: it is not clear air, or it now "
                + "sits on a growth face of a budding amethyst");
        }
        final int slot = findEmptyShulkerSlot();
        if (slot < 0) {
            return fail("no empty shulker box in inventory to place");
        }
        final ItemStack stack = CACHE.getPlayerCache().getPlayerInventory().get(slot);
        final ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data == null) return fail("could not resolve the shulker item");
        BARITONE.placeBlock(cfg.x, cfg.y, cfg.z, data);
        actionCooldown = Math.max(1, cfg.stepSettleTicks);
        advance(Phase.ENSURE_CONTAINER);
        return Status.BUSY;
    }

    private Status tickBreakShulker(final AutoAmethystConfig.Deposit cfg, final double reach,
                                    final boolean requireLineOfSight, final int priority) {
        if (!breaker.isActive()) {
            final Block at = World.getBlock(cfg.x, cfg.y, cfg.z);
            if (at.isAir()) {
                advance(Phase.COLLECT_SHULKER);
                return Status.BUSY;
            }
            if (!HarvestPolicy.isShulkerBlock(at)) {
                return fail("deposit position is " + at.name() + ", refusing to break it");
            }
            // A broken shulker keeps its contents, so if there is nowhere to put it the drop sits
            // on the floor with a whole shulker of harvest inside and despawns in five minutes.
            if (freePlayerSlots() < 1) {
                return fail("no free inventory slot to hold the full shulker once broken");
            }
            breaker.begin(cfg.x, cfg.y, cfg.z);
        }
        // Allowlist for this break is exactly one thing: a shulker box.
        final BreakDriver.Status status = breaker.tick(
            HarvestPolicy::isShulkerBlock, reach, requireLineOfSight, cfg.breakTimeoutTicks, priority);
        return switch (status) {
            case BUSY -> Status.BUSY;
            case BROKEN -> {
                advance(Phase.COLLECT_SHULKER);
                yield Status.BUSY;
            }
            case BLOCKED -> fail("could not break the full shulker: " + breaker.blockedReason());
        };
    }

    private Status tickCollectShulker(final AutoAmethystConfig.Deposit cfg, final int priority) {
        collectCfg.maxDistance = Math.max(2.0, cfg.collectRadius);
        collectCfg.chaseTimeoutTicks = cfg.collectTimeoutTicks;

        final DropCollector.Status status = shulkerCollector.tick(
            HarvestPolicy::isShulkerItem, collectCfg, cfg.x + 0.5, cfg.y + 0.5, cfg.z + 0.5, priority);
        return switch (status) {
            case CHASING, RETURNING -> Status.BUSY;
            case DONE -> {
                // nothing left to chase; give the server a moment to actually hand the shulker
                // over before we look for a spare to put down
                shulkerCollector.reset();
                actionCooldown = Math.max(1, cfg.stepSettleTicks);
                advance(Phase.PLACE_SHULKER);
                yield Status.BUSY;
            }
            case FAILED -> fail("could not collect the broken shulker: " + shulkerCollector.failReason());
            case IDLE -> Status.BUSY;
        };
    }

    // ------------------------------------------------------------------ chest trip / return

    /** Whether there are full shulkers to haul and somewhere configured to put them. */
    private boolean needsChestTrip(final AutoAmethystConfig.Deposit cfg) {
        if (!cfg.haulToChest || !cfg.chestSet) return false;
        return countFullShulkers() > 0;
    }

    private Status tickReturn(final AutoAmethystConfig.Deposit cfg, final double reach) {
        // Nothing configured to return to, or already close enough.
        final double dist = distanceToEye(returnX + 0.5, returnY + 0.5, returnZ + 0.5);
        if (dist <= Math.max(1.5, cfg.returnTolerance)) {
            if (BARITONE.isActive()) BARITONE.stop();
            phase = Phase.IDLE;
            return Status.DONE;
        }
        if (dist > cfg.maxTravelDistance) {
            // Do not strand the bot walking somewhere absurd; the harvest loop re-anchors instead.
            phase = Phase.IDLE;
            return Status.DONE;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (!BARITONE.isActive()) {
            BARITONE.pathTo(returnX, returnY, returnZ);
            repathCooldown = REPATH_COOLDOWN_TICKS;
        }
        return Status.BUSY;
    }

    // ------------------------------------------------------------------ helpers

    private void click(final int containerId, final int slot, final AutoAmethystConfig.Deposit cfg) {
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(owner)
            .actions(new ClickItem(containerId, slot, ClickItemAction.LEFT_CLICK))
            .priority(0)
            .build());
        actionCooldown = Math.max(1, cfg.stepSettleTicks);
    }

    private static int freePlayerSlots() {
        final var inv = CACHE.getPlayerCache().getPlayerInventory();
        int free = 0;
        for (int slot = 9; slot <= 44; slot++) {
            if (inv.get(slot) == Container.EMPTY_STACK) free++;
        }
        return free;
    }

    private static int openContainerId() {
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId();
    }

    /**
     * A slot in the container half that can take the given stack.
     *
     * <p>Prefers a partially filled stack of the same item over an empty slot. Without that the
     * shulker is declared full while it holds 27 slots of one shard each, and the cycle starts
     * breaking and replacing shulkers that are barely used.
     */
    private static int findDestinationSlot(final Container container, final @Nullable ItemStack moving) {
        final int playerStart = container.getSize() - 36;
        if (moving != null && moving != Container.EMPTY_STACK) {
            final ItemData data = ItemRegistry.REGISTRY.get(moving.getId());
            final int maxStack = data == null ? 64 : data.stackSize();
            // Shulker boxes never stack, so only ever look for an empty slot for one of those.
            if (maxStack > 1) {
                for (int i = 0; i < playerStart; i++) {
                    final ItemStack slot = container.getItemStack(i);
                    if (slot == Container.EMPTY_STACK) continue;
                    if (slot.getId() == moving.getId() && slot.getAmount() < maxStack) return i;
                }
            }
        }
        for (int i = 0; i < playerStart; i++) {
            if (container.getItemStack(i) == Container.EMPTY_STACK) return i;
        }
        return -1;
    }

    private static int findFreePlayerSlot(final Container container) {
        final int playerStart = container.getSize() - 36;
        for (int i = playerStart; i < container.getSize(); i++) {
            if (container.getItemStack(i) == Container.EMPTY_STACK) return i;
        }
        return -1;
    }

    private static int findPlayerSlotMatching(final Container container, final int playerStart,
                                              final Predicate<ItemStack> what) {
        for (int i = playerStart; i < container.getSize(); i++) {
            final ItemStack stack = container.getItemStack(i);
            if (stack == Container.EMPTY_STACK) continue;
            if (what.test(stack)) return i;
        }
        return -1;
    }

    private static int countPlayerMatching(final Container container, final int playerStart,
                                           final Predicate<ItemStack> what) {
        int total = 0;
        for (int i = playerStart; i < container.getSize(); i++) {
            final ItemStack stack = container.getItemStack(i);
            if (stack == Container.EMPTY_STACK) continue;
            if (what.test(stack)) total += stack.getAmount();
        }
        return total;
    }

    /** An empty shulker, so we never place a full one back down and lose the contents. */
    private static int findEmptyShulkerSlot() {
        final var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) {
            final ItemStack stack = inv.get(slot);
            if (!HarvestPolicy.isShulkerItem(stack)) continue;
            if (shulkerIsEmpty(stack)) return slot;
        }
        return -1;
    }

    private static int countFullShulkers() {
        final var inv = CACHE.getPlayerCache().getPlayerInventory();
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            if (isFullShulkerItem(inv.get(slot))) count++;
        }
        return count;
    }

    /**
     * A shulker with something in it. Emptiness is read from the CONTAINER data component, which
     * 2b2t does transmit for shulker items.
     */
    public static boolean isFullShulkerItem(final @Nullable ItemStack stack) {
        if (!HarvestPolicy.isShulkerItem(stack)) return false;
        return !shulkerIsEmpty(stack);
    }

    private static boolean shulkerIsEmpty(final ItemStack stack) {
        final var contents = stack.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
        return contents == null || contents.isEmpty();
    }

    private double distanceToEye(final double x, final double y, final double z) {
        final double dx = x - CACHE.getPlayerCache().getX();
        final double dy = y - CACHE.getPlayerCache().getEyeY();
        final double dz = z - CACHE.getPlayerCache().getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void advance(final Phase next) {
        // Count what actually left the inventory, not what we hoped to move.
        if (next == Phase.CHEST_TRANSFER) {
            fullShulkersBeforeChest = countFullShulkers();
        } else if (phase == Phase.CHEST_TRANSFER) {
            shulkersStored += Math.max(0, fullShulkersBeforeChest - countFullShulkers());
        }
        phase = next;
        phaseTicks = 0;
    }

    private Status fail(final String reason) {
        failReason = reason;
        // leave the window closed rather than stranded open
        if (openContainerId() != 0) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(owner)
                .actions(new CloseContainer())
                .priority(0)
                .build());
        }
        reset();
        return Status.FAILED;
    }
}
