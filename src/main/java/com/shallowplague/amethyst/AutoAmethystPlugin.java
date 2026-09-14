package com.shallowplague.amethyst;

import com.shallowplague.amethyst.api.AutoAmethystApi;
import com.shallowplague.amethyst.command.AutoAmethystCommand;
import com.shallowplague.amethyst.module.AutoAmethystModule;
import com.shallowplague.amethyst.module.PathfinderGuard;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Harvests fully grown amethyst clusters from a fixed geode rig",
    url = "https://github.com/aquariusnetwork9/AutoAmethyst",
    authors = {"Shallowplague"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class AutoAmethystPlugin implements ZenithProxyPlugin {
    public static AutoAmethystConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;
    private static @Nullable AutoAmethystApi API;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AutoAmethystConfig.class);
        recoverPathfinderSettings();
        final AutoAmethystModule module = new AutoAmethystModule();
        API = module;
        pluginAPI.registerModule(module);
        pluginAPI.registerCommand(new AutoAmethystCommand());
        LOG.info("AutoAmethyst {} loaded", BuildConstants.VERSION);
    }

    /**
     * The live status/control surface described in {@link AutoAmethystApi}, for code that shares
     * this plugin's classloader - typically this package embedded directly into a larger bundle
     * plugin. Null until {@link #onLoad} has run.
     *
     * <p>A separate plugin jar cannot see the {@link AutoAmethystApi} type at all (see its
     * Javadoc for why) and should instead go through
     * {@code PluginManager.getPlugin("auto-amethyst")} plus reflection.
     */
    public static @Nullable AutoAmethystApi api() {
        return API;
    }

    /**
     * Puts the pathfinder back if a previous session was killed while the module had it clamped.
     *
     * <p>Without this, a crash leaves {@code allowBreak}/{@code allowPlace} forced off in the user's
     * ZenithProxy config forever, silently degrading every other module that relies on the
     * pathfinder being able to break or place. Only runs when the module will not be enabling
     * itself - if it is about to enable, it re-applies the clamp anyway and keeps the snapshot.
     */
    private void recoverPathfinderSettings() {
        if (!PLUGIN_CONFIG.internal.pathfinderClampActive) return;
        if (PLUGIN_CONFIG.harvest.enabled) return;
        new PathfinderGuard(PLUGIN_CONFIG.internal).restore();
        LOG.info("Restored pathfinder allowBreak/allowPlace after an unclean shutdown");
    }
}
