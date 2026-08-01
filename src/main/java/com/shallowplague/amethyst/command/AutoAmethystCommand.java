package com.shallowplague.amethyst.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shallowplague.amethyst.module.AutoAmethystModule;
import com.shallowplague.amethyst.module.HarvestPolicy;
import com.shallowplague.amethyst.module.MovementDriver;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import org.jspecify.annotations.Nullable;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.util.math.MathHelper;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.shallowplague.amethyst.AutoAmethystPlugin.PLUGIN_CONFIG;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

/**
 * Live control for the amethyst harvester.
 *
 * <p>Every position is set from where the bot is standing rather than typed, so real coordinates
 * never have to be pasted into a terminal, a Discord channel or a transcript.
 */
public class AutoAmethystCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoamethyst")
            .category(CommandCategory.MODULE)
            .aliases("amethyst")
            .description("""
                Harvests fully grown amethyst clusters from a fixed geode rig.

                Set the geode box by standing at two opposite corners of the interior and running
                'box corner1' then 'box corner2'. Only amethyst_cluster blocks inside that box are
                ever broken.

                Movement modes:
                  stationary - never moves, harvests whatever is in reach
                  waypoint   - paths between waypoints (needs ladders, not scaffolding)
                  scaffold   - walks and climbs a single scaffolding column by hand
                """)
            .usageLines(
                "on/off",
                "status",
                "resume",
                "box corner1|corner2|show",
                "harvest shards|silk",
                "buds protect|allow",
                "stage cluster|large|medium|small on/off",
                "mode stationary|waypoint|scaffold",
                "waypoint add|clear|list",
                "column here",
                "collect on/off",
                "deposit here|on/off|status",
                "reach <blocks>",
                "delay <ticks>",
                "los on/off",
                "tool on/off",
                "swapat <percent>",
                "realcoords on/off"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoamethyst")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.harvest.enabled = getToggle(c, "toggle");
                module().syncEnabledFromConfig();
                c.getSource().getEmbed().title("AutoAmethyst " + toggleStrCaps(PLUGIN_CONFIG.harvest.enabled));
            }))
            .then(literal("status").executes(c -> {
                final AutoAmethystModule m = module();
                c.getSource().getEmbed()
                    .title("AutoAmethyst Status")
                    .addField("State", m.stateName())
                    .addField("Breaks", m.breaks())
                    .addField("Yield", m.yieldGained())
                    .addField("Yield/hr", String.format("%.1f", m.yieldPerHour()))
                    .addField("Mature in box", m.matureInBox())
                    .addField("In reach", m.reachableCount())
                    .addField("Skipped", m.skippedCount())
                    .addField("Reverted", m.reverts())
                    .addField("Tool swaps", m.toolSwaps())
                    .addField("Deposits", m.deposits())
                    .addField("Pathfinder clamped", m.pathfinderClamped() ? "yes" : "NO")
                    .primaryColor();
                if (m.isPaused()) {
                    c.getSource().getEmbed().description("Paused: " + m.pauseReason()).errorColor();
                }
            }))
            .then(literal("resume").executes(c -> {
                module().resume();
                c.getSource().getEmbed().title("Resumed").primaryColor();
            }))
            .then(literal("box")
                .then(literal("corner1").executes(c -> { return setCorner(c, true); }))
                .then(literal("corner2").executes(c -> { return setCorner(c, false); }))
                .then(literal("show").executes(c -> {
                    final var h = PLUGIN_CONFIG.harvest;
                    if (!h.boxSet) {
                        c.getSource().getEmbed().title("No box set").errorColor();
                        return ERROR;
                    }
                    c.getSource().getEmbed()
                        .title("Geode Box")
                        .addField("Size", (h.maxX - h.minX + 1) + " x " + (h.maxY - h.minY + 1) + " x " + (h.maxZ - h.minZ + 1))
                        .description("Coordinates are intentionally not printed. They are in plugins/config/auto-amethyst.json")
                        .primaryColor();
                    return OK;
                })))
            .then(literal("harvest").then(argument("mode", word()).executes(c -> {
                final String raw = getString(c, "mode");
                final HarvestPolicy.Mode parsed = HarvestPolicy.parseMode(raw);
                if (!parsed.name().equalsIgnoreCase(raw.trim())) {
                    c.getSource().getEmbed()
                        .title("Unknown harvest mode")
                        .description("Expected shards or silk")
                        .errorColor();
                    return ERROR;
                }
                PLUGIN_CONFIG.harvest.mode = parsed.name();
                module().requestReload();
                c.getSource().getEmbed()
                    .title("Harvest mode: " + parsed)
                    .description(parsed == HarvestPolicy.Mode.SILK
                        ? "Requires a Silk Touch pickaxe. Buds still need 'buds allow' before they can be broken."
                        : "Requires a non-Silk pickaxe. Fortune III averages 8.8 shards per cluster.")
                    .primaryColor();
                return OK;
            })))
            .then(literal("buds")
                .then(literal("protect").executes(c -> {
                    PLUGIN_CONFIG.harvest.protectBuds = true;
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Bud protection ON")
                        .description("Immature buds can no longer be broken under any configuration.")
                        .primaryColor();
                }))
                .then(literal("allow").executes(c -> {
                    PLUGIN_CONFIG.harvest.protectBuds = false;
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Bud protection OFF")
                        .description("Buds can now be broken in SILK mode if the stage is enabled. "
                            + "Each bud broken throws away up to ~2h17m of growth on that face.")
                        .errorColor();
                })))
            .then(literal("stage")
                .then(literal("cluster").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.harvest.silkHarvestCluster = getToggle(c, "toggle");
                    module().requestReload();
                    c.getSource().getEmbed().title("Silk cluster harvest "
                        + toggleStrCaps(PLUGIN_CONFIG.harvest.silkHarvestCluster));
                })))
                .then(literal("large").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.harvest.silkHarvestLargeBud = getToggle(c, "toggle");
                    module().requestReload();
                    c.getSource().getEmbed().title("Silk large bud harvest "
                        + toggleStrCaps(PLUGIN_CONFIG.harvest.silkHarvestLargeBud));
                })))
                .then(literal("medium").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.harvest.silkHarvestMediumBud = getToggle(c, "toggle");
                    module().requestReload();
                    c.getSource().getEmbed().title("Silk medium bud harvest "
                        + toggleStrCaps(PLUGIN_CONFIG.harvest.silkHarvestMediumBud));
                })))
                .then(literal("small").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.harvest.silkHarvestSmallBud = getToggle(c, "toggle");
                    module().requestReload();
                    c.getSource().getEmbed().title("Silk small bud harvest "
                        + toggleStrCaps(PLUGIN_CONFIG.harvest.silkHarvestSmallBud));
                }))))
            .then(literal("collect").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.collection.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Drop collection " + toggleStrCaps(PLUGIN_CONFIG.collection.enabled));
            })))
            .then(literal("deposit")
                .then(literal("here").executes(c -> {
                    if (!connected()) {
                        c.getSource().getEmbed().title("Not connected").errorColor();
                        return ERROR;
                    }
                    final int x = MathHelper.floorI(CACHE.getPlayerCache().getX());
                    final int y = MathHelper.floorI(CACHE.getPlayerCache().getY());
                    final int z = MathHelper.floorI(CACHE.getPlayerCache().getZ());
                    if (!HarvestPolicy.isSafeToPlaceAt(x, y, z)
                        && !HarvestPolicy.isShulkerBlock(World.getBlock(x, y, z))) {
                        c.getSource().getEmbed()
                            .title("Unsafe deposit position")
                            .description("That spot is either not clear, or it sits on a growth face of a "
                                + "budding amethyst. Placing there would permanently stop that face growing. "
                                + "Stand somewhere clear of the geode and try again.")
                            .errorColor();
                        return ERROR;
                    }
                    PLUGIN_CONFIG.deposit.x = x;
                    PLUGIN_CONFIG.deposit.y = y;
                    PLUGIN_CONFIG.deposit.z = z;
                    PLUGIN_CONFIG.deposit.posSet = true;
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Deposit position set")
                        .description("Put an empty shulker box there, or leave it clear and the bot will "
                            + "place one from its inventory.")
                        .primaryColor();
                    return OK;
                }))
                .then(literal("chest").then(literal("here").executes(c -> {
                    if (!connected()) {
                        c.getSource().getEmbed().title("Not connected").errorColor();
                        return ERROR;
                    }
                    // Stand next to the chest and look at it, or stand on it - we take the block the
                    // bot is looking at if it is a container, otherwise the block it is standing on.
                    final int[] target = findContainerNearby();
                    if (target == null) {
                        c.getSource().getEmbed()
                            .title("No container found")
                            .description("Stand next to the storage chest (within 4 blocks) and run this again.")
                            .errorColor();
                        return ERROR;
                    }
                    PLUGIN_CONFIG.deposit.chestX = target[0];
                    PLUGIN_CONFIG.deposit.chestY = target[1];
                    PLUGIN_CONFIG.deposit.chestZ = target[2];
                    PLUGIN_CONFIG.deposit.chestSet = true;
                    PLUGIN_CONFIG.deposit.haulToChest = true;
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Storage chest set")
                        .description("Full shulkers will be carried here and left in it. "
                            + "The chest is never broken.")
                        .primaryColor();
                    return OK;
                })))
                .then(literal("haul").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.deposit.haulToChest = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Haul shulkers to chest " + toggleStrCaps(PLUGIN_CONFIG.deposit.haulToChest));
                })))
                .then(literal("status").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Deposit")
                        .addField("Enabled", toggleStr(PLUGIN_CONFIG.deposit.enabled))
                        .addField("Shulker position", PLUGIN_CONFIG.deposit.posSet ? "set" : "not set")
                        .addField("Storage chest", PLUGIN_CONFIG.deposit.chestSet ? "set" : "not set")
                        .addField("Haul to chest", toggleStr(PLUGIN_CONFIG.deposit.haulToChest))
                        .addField("Trigger", "<= " + PLUGIN_CONFIG.deposit.triggerFreeSlots + " free slots")
                        .addField("Replace when full", toggleStr(PLUGIN_CONFIG.deposit.replaceWhenFull))
                        .addField("Shulkers stored", module().shulkersStored())
                        .description("Positions are intentionally not printed.")
                        .primaryColor();
                }))
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.deposit.enabled = getToggle(c, "toggle");
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Deposit " + toggleStrCaps(PLUGIN_CONFIG.deposit.enabled));
                })))
            .then(literal("mode").then(argument("mode", word()).executes(c -> {
                final String raw = getString(c, "mode");
                final MovementDriver.Mode parsed = MovementDriver.parseMode(raw);
                if (!parsed.name().equalsIgnoreCase(raw.trim())) {
                    c.getSource().getEmbed()
                        .title("Unknown mode")
                        .description("Expected stationary, waypoint or scaffold")
                        .errorColor();
                    return ERROR;
                }
                PLUGIN_CONFIG.movement.mode = parsed.name();
                module().requestReload();
                c.getSource().getEmbed().title("Movement mode: " + parsed).primaryColor();
                return OK;
            })))
            .then(literal("waypoint")
                .then(literal("add").executes(c -> {
                    if (!connected()) {
                        c.getSource().getEmbed().title("Not connected").errorColor();
                        return ERROR;
                    }
                    PLUGIN_CONFIG.movement.waypoints.add(currentBlockPosString());
                    module().requestReload();
                    c.getSource().getEmbed()
                        .title("Waypoint added")
                        .addField("Total", PLUGIN_CONFIG.movement.waypoints.size())
                        .primaryColor();
                    return OK;
                }))
                .then(literal("clear").executes(c -> {
                    PLUGIN_CONFIG.movement.waypoints.clear();
                    module().requestReload();
                    c.getSource().getEmbed().title("Waypoints cleared").primaryColor();
                }))
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Waypoints")
                        .addField("Count", PLUGIN_CONFIG.movement.waypoints.size())
                        .description("Positions are intentionally not printed. They are in plugins/config/auto-amethyst.json")
                        .primaryColor();
                })))
            .then(literal("column").then(literal("here").executes(c -> {
                if (!connected()) {
                    c.getSource().getEmbed().title("Not connected").errorColor();
                    return ERROR;
                }
                PLUGIN_CONFIG.movement.columnX = MathHelper.floorI(CACHE.getPlayerCache().getX());
                PLUGIN_CONFIG.movement.columnZ = MathHelper.floorI(CACHE.getPlayerCache().getZ());
                PLUGIN_CONFIG.movement.columnSet = true;
                module().requestReload();
                c.getSource().getEmbed().title("Scaffolding column set to current position").primaryColor();
                return OK;
            })))
            .then(literal("reach").then(argument("blocks", integer(0, 6)).executes(c -> {
                PLUGIN_CONFIG.harvest.maxReach = getInteger(c, "blocks");
                c.getSource().getEmbed()
                    .title("Reach cap set")
                    .description("0 means use the server's own interaction range. Values above vanilla are ignored.")
                    .primaryColor();
            })))
            .then(literal("delay").then(argument("ticks", integer(0, 200)).executes(c -> {
                PLUGIN_CONFIG.harvest.interBreakDelayTicks = getInteger(c, "ticks");
                c.getSource().getEmbed().title("Inter-break delay set").primaryColor();
            })))
            .then(literal("los").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.harvest.requireLineOfSight = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Line of sight requirement " + toggleStrCaps(PLUGIN_CONFIG.harvest.requireLineOfSight));
            })))
            .then(literal("tool").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.tool.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Tool management " + toggleStrCaps(PLUGIN_CONFIG.tool.enabled));
            })))
            .then(literal("swapat").then(argument("percent", integer(0, 90)).executes(c -> {
                PLUGIN_CONFIG.tool.swapAtDurabilityPercent = getInteger(c, "percent");
                c.getSource().getEmbed().title("Pickaxe swap threshold set").primaryColor();
            })))
            .then(literal("realcoords").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.stats.logRealCoords = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Real coordinates in logs " + toggleStrCaps(PLUGIN_CONFIG.stats.logRealCoords))
                    .description(PLUGIN_CONFIG.stats.logRealCoords
                        ? "Harvest logs will now contain absolute coordinates. Do not share or commit them."
                        : "Harvest logs record positions relative to the box corner.");
            })));
    }

    private int setCorner(final com.mojang.brigadier.context.CommandContext<CommandContext> c, final boolean first) {
        if (!connected()) {
            c.getSource().getEmbed().title("Not connected").errorColor();
            return ERROR;
        }
        final var h = PLUGIN_CONFIG.harvest;
        final int x = MathHelper.floorI(CACHE.getPlayerCache().getX());
        final int y = MathHelper.floorI(CACHE.getPlayerCache().getY());
        final int z = MathHelper.floorI(CACHE.getPlayerCache().getZ());
        if (first) {
            h.minX = x; h.minY = y; h.minZ = z;
            h.maxX = x; h.maxY = y; h.maxZ = z;
            h.boxSet = false;
            c.getSource().getEmbed()
                .title("Corner 1 set")
                .description("Now stand at the opposite corner and run 'box corner2'")
                .primaryColor();
        } else {
            final int minX = Math.min(h.minX, x);
            final int minY = Math.min(h.minY, y);
            final int minZ = Math.min(h.minZ, z);
            h.maxX = Math.max(h.minX, x);
            h.maxY = Math.max(h.minY, y);
            h.maxZ = Math.max(h.minZ, z);
            h.minX = minX; h.minY = minY; h.minZ = minZ;
            h.boxSet = true;
            c.getSource().getEmbed()
                .title("Geode box set")
                .addField("Size", (h.maxX - h.minX + 1) + " x " + (h.maxY - h.minY + 1) + " x " + (h.maxZ - h.minZ + 1))
                .primaryColor();
        }
        module().requestReload();
        return OK;
    }

    private static boolean connected() {
        return Proxy.getInstance().isConnected();
    }

    /**
     * Finds the nearest container block to the bot, so the user can set the storage chest by
     * standing next to it rather than typing coordinates.
     *
     * <p>Returns {@code null} if nothing suitable is within reach.
     */
    private static int @Nullable [] findContainerNearby() {
        final int px = MathHelper.floorI(CACHE.getPlayerCache().getX());
        final int py = MathHelper.floorI(CACHE.getPlayerCache().getY());
        final int pz = MathHelper.floorI(CACHE.getPlayerCache().getZ());
        final int radius = 4;
        int[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final int x = px + dx, y = py + dy, z = pz + dz;
                    if (!World.isChunkLoadedBlockPos(x, z)) continue;
                    if (!isContainerBlock(World.getBlock(x, y, z))) continue;
                    final double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    private static boolean isContainerBlock(final Block block) {
        if (HarvestPolicy.isShulkerBlock(block)) return true;
        final String name = block.name();
        return name.endsWith("chest") || name.equals("barrel");
    }

    private static String currentBlockPosString() {
        return MathHelper.floorI(CACHE.getPlayerCache().getX())
            + " " + MathHelper.floorI(CACHE.getPlayerCache().getY())
            + " " + MathHelper.floorI(CACHE.getPlayerCache().getZ());
    }

    private static AutoAmethystModule module() {
        return MODULE.get(AutoAmethystModule.class);
    }

    @Override
    public void defaultEmbed(final Embed embed) {
        final AutoAmethystModule m = module();
        embed
            .addField("AutoAmethyst", toggleStr(PLUGIN_CONFIG.harvest.enabled))
            .addField("State", m.stateName())
            .addField("Box", PLUGIN_CONFIG.harvest.boxSet ? "set" : "not set")
            .addField("Harvest mode", PLUGIN_CONFIG.harvest.mode)
            .addField("Bud protection", toggleStr(PLUGIN_CONFIG.harvest.protectBuds))
            .addField("Movement", PLUGIN_CONFIG.movement.mode)
            .addField("Waypoints", PLUGIN_CONFIG.movement.waypoints.size())
            .addField("Collect drops", toggleStr(PLUGIN_CONFIG.collection.enabled))
            .addField("Deposit", toggleStr(PLUGIN_CONFIG.deposit.enabled))
            .addField("Tool mgmt", toggleStr(PLUGIN_CONFIG.tool.enabled))
            .addField("Breaks", m.breaks())
            .addField("Yield", m.yieldGained())
            .primaryColor();
    }
}
