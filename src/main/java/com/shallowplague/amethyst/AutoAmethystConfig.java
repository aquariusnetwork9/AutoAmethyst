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
         * How often to rebuild the candidate target list. Only the part of the box within reach is
         * swept, so this is cheap regardless of how large the geode is.
         */
        public int rescanIntervalTicks = 20;

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
         * target raycast will happily hit a block through a wall; vanilla clients cannot. Leave on.
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
    }

    public final Movement movement = new Movement();

    public static final class Movement {
        /**
         * STATIONARY - never move. Harvest whatever is in reach of the parked position.
         * WAYPOINT   - path between waypoints with Zenith's pathfinder. Requires the rig's vertical
         *              runs to be ladders or vines; the path planner cannot route scaffolding.
         * SCAFFOLD   - hand driven walk/climb along a single scaffolding column. Uses only vanilla
         *              movement inputs (forward / jump / sneak) so Zenith's physics simulation
         *              produces ordinary position packets.
         */
        public String mode = "STATIONARY";

        /** Stand positions, "x y z", visited in order and cycled. Set with {@code waypoint add}. */
        public List<String> waypoints = new ArrayList<>();

        /** Minimum ticks to spend at a waypoint before moving on, even if nothing is mature. */
        public int dwellTicks = 40;

        /** Horizontal distance in blocks at which a waypoint counts as reached. */
        public double arriveRadius = 0.45;

        /** Give up on a movement leg after this many ticks and pause. */
        public int legTimeoutTicks = 600;

        /** Ticks of no measurable position change before a leg is considered stuck. */
        public int stuckTicks = 60;

        // --- SCAFFOLD mode only ---

        /**
         * XZ of the single scaffolding column used for all vertical travel. Vertical legs walk to
         * this column first, climb, then walk out to the target. Set with {@code column here}.
         */
        public boolean columnSet = false;
        public int columnX = 0;
        public int columnZ = 0;

        /** Vertical tolerance in blocks for a climb leg to count as finished. */
        public double climbTolerance = 0.2;

        /**
         * Sneak while walking legs. Strongly recommended on a scaffolding rig: it enables vanilla
         * ledge protection so the bot cannot walk off a platform, and the lower speed stops it
         * overshooting stand positions. Costs nothing that matters, since growth is the bottleneck.
         */
        public boolean sneakWhileWalking = true;
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
    }
}
