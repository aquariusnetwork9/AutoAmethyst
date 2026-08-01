package com.shallowplague.amethyst;

import com.shallowplague.amethyst.command.AutoAmethystCommand;
import com.shallowplague.amethyst.module.AutoAmethystModule;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

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

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AutoAmethystConfig.class);
        pluginAPI.registerModule(new AutoAmethystModule());
        pluginAPI.registerCommand(new AutoAmethystCommand());
        LOG.info("AutoAmethyst {} loaded", BuildConstants.VERSION);
    }
}
