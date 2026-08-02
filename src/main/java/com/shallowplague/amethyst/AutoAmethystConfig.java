package com.shallowplague.amethyst;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved to {@code plugins/config/auto-amethyst.json}.
 *
 * <p>All fields are public and mutable. Nested static classes become nested JSON objects.
 *
 * <p><b>Coordinates live here and only here.</b> The geode box, waypoints and column position are
 * real world coordinates on an anarchy server. They are written to the local config file and are
 * deliberately never echoed into logs, chat or Discord embeds unless {@link Stats#logRealCoords} is
 * explicitly turned on. Do not commit a populated copy of this file anywhere public.
 */
public class AutoAmethystConfig {

    public final Harvest harvest = new Harvest();

    public static final class Harvest {
        /** Master toggle. */
        public boolean enabled = false;

        /**
         * SHARDS - break only fully grown clusters with a non-Silk pickaxe. Fortune III averages
         * 8.8 shards per cluster.
         *
         * SILK - break the enabled growth stages with a Silk Touch pickaxe, collecting the blocks
         * themselves. Note Fortune is worthless on immature buds: they drop <b>nothing at all</b>
         * without Silk Touch, so there is no drop for Fortune to multiply. Only the terminal cluster
         * stage has a non-silk drop.
         */
        public String mode = "SHARDS";

        /**
         * <b>Master bud protection. Leave this on.</b>
         *
         * <p>While true it is impossible for the plugin to break an immature bud, whatever else is
         * configured - the per-stage toggles below are ignored entirely. Breaking a bud throws away
         * the growth already invested in that face, up to ~2h17m, for a block that is only worth
         * having if you specifically want bud blocks.
         *
         * <p>Turning this off is a deliberate two-step act: you must also select SILK mode and
         * enable the individual stage. Nothing about normal shard farming can trip it.
         */
        public boolean protectBuds = true;

        /** SILK mode: harvest fully grown clusters as blocks. Safe - they are terminal anyway. */
        public boolean silkHarvestCluster = true;
        /** SILK mode: harvest large buds. Requires protectBuds = false. */
        public boolean silkHarvestLargeBud = false;
        /** SILK mode: harvest medium buds. Requires protectBuds = false. */
        public boolean silkHarvestMediumBud = false;
        /** SILK mode: harvest small buds. Requires protectBuds = false. */
        public boolean silkHarvestSmallBud = false;

        /**
         * Inclusive bounding box of the geode interior. Only {@code amethyst_cluster} blocks inside
         * this box are ever considered targets. Set with {@code autoamethyst box corner1|corner2}.
         */
        public boolean boxSet = false;
        public int minX = 0;
        public int minY = 0;
        public int minZ = 0;
        public int maxX = 0;
        public int maxY = 0;
        public int maxZ = 0;

        /**
         * How often to sweep the whole box for ripe clusters.
         *
         * <p>The whole box every time, not just what is within reach. A geode is a couple of chunks
         * across, so this is a few tens of thousands of array reads on a timer - nothing - and it
         * means the bot always knows where every ripe cluster is instead of having to wander past
         * them to notice.
         */
        public int scanIntervalTicks = 100;

        /**
         * How many clusters must be ripe before the bot sets off.
         *
         * <p>Defaults to 1: go as soon as anything is ready. The cycle is one cluster at a time
         * anyway - break it, collect its shards, rescan, repeat - so there is nothing to be gained
         * by batching, and every shard left on the floor is on a five minute despawn timer. Raise it
         * only if you would rather the bot stood still more of the time.
         */
        public int minMatureToHarvest = 1;

        /**
         * How often to sweep the whole box to count mature clusters. Statistics only, and the whole
         * point of keeping it separate from the target scan, so it can afford to be slow.
         */
        public int censusIntervalTicks = 200;

        /**
         * Idle ticks after a successful break before the next target is engaged. Gives the drop
         * time to settle and keeps breaks from being back to back. The vanilla interaction manager
         * already enforces its own 5 tick destroy delay on top of this.
         */
        public int interBreakDelayTicks = 6;

        /**
         * Reach cap in blocks. 0 means "use the server's block interaction range attribute", which
         * is the vanilla 4.5 and the only value that is safe under Grim. Only lower it.
         */
        public double maxReach = 0.0;

        /**
         * Require an unobstructed line of sight to the target before breaking. Zenith's block
         * target raycast will happily hit a block through a wall; vanilla clients cannot.
         *
         * <p><b>Leave this on.</b> Besides being the honest thing to send a server, it is what stops
         * the bot breaking clusters on the level above it through the ceiling - the shards then land
         * up there, where there is no route to them, and the farm quietly loses everything it just
         * mined. Turning it off does not make the bot harvest more; it makes it drop more on the
         * floor. If clusters are being refused for want of a clean shot, the fix is that the bot
         * needs to walk onto their level, which {@link com.shallowplague.amethyst.module.Travel}
         * escalates to on its own.
         */
        public boolean requireLineOfSight = true;

        /**
         * Ticks to keep swinging at one target before treating it as a ghost block and skipping it.
         * A cluster with a Fortune III netherite pickaxe breaks in a handful of ticks, so anything
         * near this cap means something is wrong.
         */
        public int maxBreakTicks = 200;

        /** Ticks a skipped/ghosted target stays blacklisted before it is eligible again. */
        public int skipCooldownTicks = 600;

        /**
         * Ticks a target the bot physically could not get to stays blacklisted. Far longer than
         * {@link #skipCooldownTicks}, which is meant for transient problems.
         *
         * <p>Some clusters in a real rig simply cannot be reached or seen from anywhere the bot can
         * stand - walled in, or on a face the pathfinder has no route to. Retrying those on the
         * ordinary 30 second cooldown means the bot spends its whole life walking to clusters it is
         * about to give up on again, which is what "it won't even follow its own path" looks like
         * from outside. Nothing is lost by waiting: a cluster face takes about 2h17m to regrow, so
         * checking an awkward one every ten minutes still catches it long before it matters.
         */
        public int unreachableCooldownTicks = 2400;

        /**
         * Ticks after an apparent break during which a reappearing cluster at the same position is
         * counted as an anticheat revert rather than new growth. Real regrowth takes ~34 minutes
         * per stage so anything inside a few seconds is a revert.
         */
        public int revertWindowTicks = 60;

        /**
         * Input priority. Baritone dispatches at 7000 and its interact process at 7001, so this
         * must be above 7001 for our breaks to win arbitration.
         */
        public int inputPriority = 7100;

        /** Pause and alert instead of continuing when a guard trips repeatedly. */
        public int maxConsecutiveFailures = 10;
    }

    public final Tool tool = new Tool();

    public static final class Tool {
        /** Select and hold a suitable pickaxe automatically. */
        public boolean enabled = true;

        /**
         * Never break with a Silk Touch pickaxe. Silk Touch drops the cluster block itself and zero
         * shards, so a silk pick silently destroys the entire point of the farm.
         */
        public boolean refuseSilkTouch = true;

        /** Swap to a spare pickaxe at or below this percent of remaining durability. */
        public int swapAtDurabilityPercent = 10;

        /** Pause harvesting when no usable pickaxe is left rather than breaking barehanded. */
        public boolean pauseWhenNoTool = true;

        /**
         * How long the "no usable pickaxe" condition must hold before it pauses the farm.
         *
         * <p>A pause is sticky and needs a manual resume, so it must not be reachable from a
         * momentary desync. The inventory cache reads empty on connect, on a world change and for
         * the whole 2b2t queue, and pausing on the first such tick switched the farm off for hours
         * while the bot was carrying a perfectly good Fortune III pickaxe.
         */
        public int noToolGraceTicks = 200;
    }

    /**
     * Where the bot parks between harvests, and how it travels.
     *
     * <p>There is deliberately no route, patrol or waypoint list. A geode is a couple of chunks
     * across, so the bot scans the whole thing from wherever it stands and walks straight to
     * whatever has ripened. Routes were a mistake: they made the bot wander past clusters it could
     * not see from the route, and stall on stand positions that were never quite right.
     */
    public final Movement movement = new Movement();

    public static final class Movement {
        /**
         * Where the bot parks when there is nothing to do. Set with {@code autoamethyst home}.
         *
         * <p>Optional. With no home set the bot simply stays wherever its last harvest left it,
         * which is fine - the point of a home is a spot you have chosen for random-tick coverage.
         */
        public boolean homeSet = false;
        public int homeX = 0;
        public int homeY = 0;
        public int homeZ = 0;

        /** Walk back to the home spot once there is nothing left to harvest, collect or deposit. */
        public boolean returnHome = true;

        /** How close to home counts as home. Never tighter than the pathfinder's own goal precision. */
        public double homeTolerance = 3.0;

        /** Give up on any single travel leg after this long. */
        public int legTimeoutTicks = 600;

        /**
         * Refuse to walk further than this for one cluster. A geode is a couple of chunks across,
         * so anything beyond this is either a bad box or a cluster in a neighbouring formation.
         */
        public double maxTravelDistance = 96.0;

        /**
         * Run. Zenith's pathfinder has {@code allowSprint} FALSE by default, which is why travel
         * crawls unless this turns it on.
         */
        public boolean sprint = true;
    }

    public final Collection collection = new Collection();

    public static final class Collection {
        /**
         * Sweep the geode for dropped shards and walk onto them.
         *
         * <p>Passive pickup is not enough on its own: a break throws its drop a block or two, and
         * shards then fall to whatever level is below, well outside the radius the server hands
         * items over on. Without this the farm quietly leaks yield onto the floor to despawn after
         * five minutes.
         */
        public boolean enabled = true;

        /**
         * How far outside the geode box a dropped shard still counts, in blocks.
         *
         * <p>This replaces the old "leash from the bot's stand position", which was a
         * three-dimensional distance and therefore excluded anything that had fallen a couple of
         * levels - the bot ignored bottom-level shards entirely. The farm is the box; a shard in
         * the box gets collected however far away it is.
         */
        public int boxMargin = 6;

        /** Most drops handed to the pathfinder in one goal. */
        public int maxGoalsPerSweep = 32;

        /**
         * Walk directly at a drop this close rather than asking the pathfinder, in blocks.
         *
         * <p>This is the only thing that collects a shard resting on budding amethyst. The
         * pathfinder will not route onto one - {@code MovementHelper.isBlockNormalCube} excludes
         * every amethyst-named block except {@code amethyst_block} - even though a vanilla client
         * walks over it perfectly happily. It is also what gets the bot standing on top of a drop
         * rather than beside it, which 2b2t sometimes insists on before handing the item over.
         */
        public double nudgeRadius = 3.5;

        /**
         * Height difference, up or down, within which a drop is close enough to walk straight at.
         *
         * <p>Generous on purpose. Shards fall to whatever level is below, so "one floor down" is
         * the common case rather than the exception, and the vanilla pickup box reaches 2.3 blocks
         * above the feet anyway. A tight value here just means the bot stands looking at a shard
         * one level away and hands it to a pathfinder that cannot stand on the geode floor either.
         */
        public double nudgeHeight = 2.5;

        /** Give up walking directly at a drop after this long and hand it to the pathfinder. */
        public int nudgeTimeoutTicks = 50;

        /** Ticks of no movement during a direct walk before treating it as blocked. */
        public int nudgeStuckTicks = 12;

        /** How long before a drop that a direct walk could not reach is walked at again. */
        public int nudgeRetryTicks = 200;

        /**
         * Ticks to wait for the pathfinder to produce a route before dropping to a looser goal, and
         * then before writing the batch off.
         *
         * <p>Short on purpose. "No path found" comes back almost immediately when every goal is
         * unstandable, and the bot used to stand there for the whole chase timeout doing nothing.
         */
        public int pathGiveUpTicks = 40;

        /** Ticks spent walking with nothing picked up before parking that drop and moving on. */
        public int chaseTimeoutTicks = 400;

        /**
         * How long a drop that could not be reached is left alone before being tried again.
         *
         * <p>Never permanent. An earlier version remembered unreachable drops in a set cleared only
         * by a successful deposit, so a single transient path failure meant that shard was ignored
         * for the rest of the run - which looked exactly like the bot not caring about drops at
         * all.
         */
        public int retryCooldownTicks = 1200;
    }

    public final Deposit deposit = new Deposit();

    public static final class Deposit {
        /**
         * Empty the harvest into a shulker box at a fixed position, replacing it when it fills.
         * Full shulkers accumulate in the bot's inventory for you to collect.
         */
        public boolean enabled = false;

        /** Position of the deposit shulker. Set with {@code autoamethyst deposit here}. */
        public boolean posSet = false;
        public int x = 0;
        public int y = 0;
        public int z = 0;

        /**
         * Haul filled shulkers to a storage chest instead of carrying them around. Without this the
         * bot accumulates full shulkers until it runs out of inventory and stops.
         */
        public boolean haulToChest = true;

        /**
         * Position of the storage chest. Set with {@code autoamethyst deposit chest here}. Any
         * container works. It is opened and filled, never broken - the plugin cannot break a chest.
         */
        public boolean chestSet = false;
        public int chestX = 0;
        public int chestY = 0;
        public int chestZ = 0;

        /**
         * Chest holding <b>empty</b> shulker boxes to restock from. Set with
         * {@code autoamethyst deposit supply here}. Without this the bot can only ever place the
         * empties it happens to be carrying, so the farm stops the moment it runs out.
         *
         * <p>May be the same block as the storage chest: filled shulkers go in, empty ones come
         * out, and the two are told apart by their contents rather than by which chest they are in.
         */
        public boolean supplySet = false;
        public int supplyX = 0;
        public int supplyY = 0;
        public int supplyZ = 0;

        /**
         * Restock trips allowed per deposit run before giving up on the supply chest.
         *
         * <p>The bot deliberately takes <b>exactly one</b> empty shulker per trip and places it
         * immediately, rather than stocking up - so a run that fills more than one shulker makes
         * more than one trip. This budget only exists to stop an empty supply chest looping.
         */
        public int maxSupplyTrips = 3;

        /**
         * How close counts as "back at the stand position" when the run walks the bot home. The
         * return leg exists because a bot that finishes a deposit standing at the chest would
         * re-anchor there and, in stationary mode, never harvest again.
         */
        public double returnTolerance = 2.0;

        /** Start a deposit run when this many inventory slots or fewer are free. */
        public int triggerFreeSlots = 4;

        /**
         * When the shulker fills: break it (shulkers keep their contents), pick it up, and place a
         * fresh empty one. Turning this off makes a full shulker pause the module instead.
         */
        public boolean replaceWhenFull = true;

        /** Ticks between container clicks. Unthrottled click streams get windows closed on you. */
        public int stepSettleTicks = 6;

        /**
         * Abort a stuck deposit phase after this long rather than grinding. A full transfer is two
         * clicks per stack at {@link #stepSettleTicks} apiece, so this has to comfortably exceed
         * 36 x 2 x stepSettleTicks or a legitimately long transfer will look like a hang.
         */
        public int phaseTimeoutTicks = 1200;

        /** Placement attempts before giving up on putting a shulker at the deposit position. */
        public int maxPlaceAttempts = 5;

        /** Give up breaking the full shulker after this long. */
        public int breakTimeoutTicks = 200;

        /** How far the bot may wander to pick up the broken shulker. */
        public double collectRadius = 5.0;

        public int collectTimeoutTicks = 200;

        /** Refuse to walk further than this to reach the deposit position. */
        public double maxTravelDistance = 64.0;

        /** Consecutive transfer steps with no effect before giving up. */
        public int maxStalledSteps = 6;
    }

    public final Internal internal = new Internal();

    /**
     * Bookkeeping, not settings. Do not edit by hand.
     *
     * <p>The module clamps Zenith's {@code pathfinder.allowBreak} / {@code allowPlace} off while it
     * runs. Zenith persists its own config on shutdown and on every command, so those clamped
     * values reach {@code config.json} - meaning if the proxy is killed while the module is enabled,
     * the user's original settings would be gone and the next startup would snapshot the clamped
     * values as if they were the originals. Keeping the snapshot here, in a file that survives the
     * crash, lets the module put things back on the next run.
     */
    public static final class Internal {
        public boolean pathfinderClampActive = false;
        public boolean savedAllowBreak = true;
        public boolean savedAllowPlace = true;
        public boolean savedAllowParkourPlace = false;
        public boolean savedAllowSprint = false;
        public List<String> savedAllowBreakAnyway = new ArrayList<>();
    }

    public final Stats stats = new Stats();

    public static final class Stats {
        /** Append a line per harvest event to {@link #logFile}. */
        public boolean logToFile = true;

        public String logFile = "plugins/auto-amethyst/harvest.log";

        /**
         * Log absolute world coordinates. Off by default: positions are logged as offsets from the
         * configured box minimum instead, which is enough to debug the rig without putting real
         * anarchy coordinates into a file that may get shared or committed.
         */
        public boolean logRealCoords = false;

        /** Emit a rolling summary to the module log this often. 0 disables. */
        public int summaryIntervalTicks = 12000;

        /**
         * Log a full diagnosis every {@link #debugIntervalTicks} explaining what the module is doing
         * and, more usefully, why it is <i>not</i> doing anything. Turn on with
         * {@code autoamethyst debug on}; the same report is available on demand via
         * {@code autoamethyst why}.
         */
        public boolean debug = false;

        public int debugIntervalTicks = 100;
    }
}
