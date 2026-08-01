package com.shallowplague.amethyst.module;

import com.shallowplague.amethyst.AutoAmethystConfig;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.BlockTags;
import com.zenith.feature.player.World;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolTag;
import com.zenith.mc.item.ToolType;
import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.BOT;

/**
 * The single source of truth for what this plugin is allowed to break, place and pick up.
 *
 * <p>Everything is an <b>allowlist</b> of specific {@link BlockRegistry} constants compared by
 * identity. There is deliberately no "block we avoid" list anywhere: a denylist silently permits
 * anything nobody thought of, and in a geode rig the cost of one wrong break is permanent.
 *
 * <p>{@link BlockRegistry#BUDDING_AMETHYST} is never in any set, in any mode, ever. It drops
 * nothing with any tool including Silk Touch and cannot be obtained in survival, so breaking one
 * permanently deletes a growth site that produces a cluster every ~2h17m on each of six faces.
 */
public final class HarvestPolicy {

    private HarvestPolicy() {}

    public enum Mode {
        /**
         * Break only fully grown clusters, with a non-Silk pickaxe. Fortune III averages 8.8 shards
         * per cluster.
         */
        SHARDS,
        /**
         * Break whichever growth stages are enabled, with a Silk Touch pickaxe, collecting the
         * blocks themselves rather than shards.
         *
         * <p>Worth understanding before enabling: Fortune is worthless on immature buds because
         * they drop <b>nothing at all</b> without Silk Touch - there is no drop for it to multiply.
         * Only the terminal cluster stage has a non-silk drop. So silk-harvesting a bud trades away
         * the growth already invested in that face (up to ~2h17m) for a bud block. That is a fine
         * trade if you want the blocks; it is strictly worse if you want shards.
         */
        SILK
    }

    public static Mode parseMode(final @Nullable String raw) {
        if (raw == null) return Mode.SHARDS;
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            return Mode.SHARDS;
        }
    }

    /**
     * Whether this block is something we intend to harvest right now.
     *
     * <p>In SHARDS mode this is exactly one block. In SILK mode it is whichever stages the user
     * enabled. Nothing else is ever true.
     */
    public static boolean isHarvestTarget(final Block block, final Mode mode,
                                          final AutoAmethystConfig.Harvest cfg) {
        // Never, in any mode, under any configuration. Repeated here as well as in isBreakable and
        // BreakDriver because this is the method the scanner uses to choose targets, and the
        // cheapest place to be certain is all three.
        if (block == BlockRegistry.BUDDING_AMETHYST) return false;
        if (block == BlockRegistry.AMETHYST_CLUSTER) {
            return mode == Mode.SHARDS || cfg.silkHarvestCluster;
        }
        if (mode != Mode.SILK) return false;
        // Buds are double gated. protectBuds is a master switch that makes breaking an immature bud
        // impossible no matter what else is configured, and it defaults on. Breaking a bud is only
        // ever possible if the user has deliberately turned that off AND selected silk mode AND
        // enabled that specific stage - it can never happen by accident or by misconfiguration.
        if (cfg.protectBuds) return false;
        if (block == BlockRegistry.LARGE_AMETHYST_BUD) return cfg.silkHarvestLargeBud;
        if (block == BlockRegistry.MEDIUM_AMETHYST_BUD) return cfg.silkHarvestMediumBud;
        if (block == BlockRegistry.SMALL_AMETHYST_BUD) return cfg.silkHarvestSmallBud;
        return false;
    }

    /** Any amethyst growth block, at any stage, plus the budding block that produces them. */
    public static boolean isAmethystGrowth(final @Nullable Block block) {
        return block == BlockRegistry.BUDDING_AMETHYST
            || block == BlockRegistry.AMETHYST_CLUSTER
            || block == BlockRegistry.LARGE_AMETHYST_BUD
            || block == BlockRegistry.MEDIUM_AMETHYST_BUD
            || block == BlockRegistry.SMALL_AMETHYST_BUD;
    }

    public static boolean isBud(final @Nullable Block block) {
        return block == BlockRegistry.LARGE_AMETHYST_BUD
            || block == BlockRegistry.MEDIUM_AMETHYST_BUD
            || block == BlockRegistry.SMALL_AMETHYST_BUD;
    }

    /**
     * Whether a block may be placed at this position without harming the farm.
     *
     * <p>The dangerous case is subtle and permanent. Buds only grow into <b>air or still water</b>,
     * so a block sitting in the air space on a face of a {@code budding_amethyst} occupies that
     * growth face and it produces nothing for as long as the block is there. Six faces per budding
     * block, each worth a cluster every ~2h17m, so one carelessly placed shulker silently removes a
     * sixth of a growth site's output and nothing about it looks broken.
     *
     * <p>So a placement is refused if the target is not air, if any of its six face neighbours is a
     * budding amethyst (it is a growth face), or if any neighbour is an existing bud or cluster.
     */
    public static boolean isSafeToPlaceAt(final int x, final int y, final int z) {
        if (!World.isChunkLoadedBlockPos(x, z)) return false;
        if (!World.getBlock(x, y, z).isAir()) return false;
        for (final int[] d : FACE_OFFSETS) {
            final Block neighbour = World.getBlock(x + d[0], y + d[1], z + d[2]);
            if (isAmethystGrowth(neighbour)) return false;
        }
        return true;
    }

    private static final int[][] FACE_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Every block this plugin may ever break: the current harvest targets, plus shulker boxes for
     * the deposit cycle. Nothing else, in any mode, under any configuration.
     */
    public static boolean isBreakable(final Block block, final Mode mode,
                                      final AutoAmethystConfig.Harvest cfg) {
        // Stated explicitly rather than left to fall out of the allowlist below. Budding amethyst
        // is never breakable, and this rule should survive someone later adding a stage toggle or
        // widening the harvest set without thinking about it. BreakDriver enforces the same veto
        // again at the point of the click.
        if (block == BlockRegistry.BUDDING_AMETHYST) return false;
        return isHarvestTarget(block, mode, cfg) || isShulkerBlock(block);
    }

    /** Shulker boxes are the only block this plugin may ever place. */
    public static boolean isPlaceable(final Block block) {
        return isShulkerBlock(block);
    }

    public static boolean isShulkerBlock(final @Nullable Block block) {
        return block != null && block.blockTags().contains(BlockTags.SHULKER_BOXES);
    }

    /**
     * A block worth right-clicking to open. Checked before any container interaction so a stale or
     * mistyped chest position makes the bot fail with a reason rather than right-click whatever
     * happens to be standing there.
     */
    public static boolean isContainerBlock(final @Nullable Block block) {
        if (block == null) return false;
        if (isShulkerBlock(block)) return true;
        final String name = block.name();
        return name.endsWith("chest") || name.equals("barrel");
    }

    public static boolean isShulkerItem(final @Nullable ItemStack stack) {
        final ItemData data = itemData(stack);
        return data != null && data.name().endsWith("shulker_box");
    }

    /** The item a harvested block yields, which is what the collector and the deposit chase. */
    public static boolean isHarvestYield(final @Nullable ItemStack stack, final Mode mode) {
        final ItemData data = itemData(stack);
        if (data == null) return false;
        if (mode == Mode.SHARDS) {
            return data.id() == ItemRegistry.AMETHYST_SHARD.id();
        }
        // silk mode yields the blocks themselves, and clusters still yield shards if a non-silk
        // pickaxe ever ends up in hand
        final String name = data.name();
        return data.id() == ItemRegistry.AMETHYST_SHARD.id()
            || name.endsWith("amethyst_cluster")
            || name.endsWith("amethyst_bud");
    }

    // ------------------------------------------------------------------ tools

    /**
     * Whether this pickaxe is the right tool for the mode.
     *
     * <p>The Silk Touch rule inverts between modes, and getting it wrong is silent and expensive in
     * both directions: a Silk pickaxe in SHARDS mode drops cluster blocks and zero shards, and a
     * non-Silk pickaxe in SILK mode destroys buds for nothing at all.
     */
    public static boolean isCorrectPickaxe(final @Nullable ItemStack stack, final Mode mode) {
        if (!isPickaxe(stack)) return false;
        final boolean silk = hasEnchant(stack, EnchantmentRegistry.SILK_TOUCH.get());
        return mode == Mode.SILK ? silk : !silk;
    }

    public static boolean isPickaxe(final @Nullable ItemStack stack) {
        final ItemData data = itemData(stack);
        if (data == null) return false;
        final ToolTag tag = data.toolTag();
        return tag != null && tag.type() == ToolType.PICKAXE;
    }

    public static int enchantLevel(final @Nullable ItemStack stack, final @Nullable EnchantmentData enchantment) {
        if (stack == null || stack == Container.EMPTY_STACK || enchantment == null) return 0;
        return BOT.getInteractions().getEnchantmentLevel(stack, enchantment);
    }

    public static boolean hasEnchant(final @Nullable ItemStack stack, final @Nullable EnchantmentData enchantment) {
        return enchantLevel(stack, enchantment) > 0;
    }

    private static @Nullable ItemData itemData(final @Nullable ItemStack stack) {
        if (stack == null || stack == Container.EMPTY_STACK) return null;
        return ItemRegistry.REGISTRY.get(stack.getId());
    }
}
