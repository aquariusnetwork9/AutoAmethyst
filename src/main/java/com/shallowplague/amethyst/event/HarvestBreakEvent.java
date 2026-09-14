package com.shallowplague.amethyst.event;

/**
 * Posted to {@code Globals.EVENT_BUS} each time a cluster is successfully broken.
 *
 * <p><b>Classloader scope:</b> ZenithProxy's event bus dispatches by exact {@code Class} identity
 * (an {@code IdentityHashMap}), and each plugin jar loads in its own classloader. A separate
 * plugin jar cannot obtain the same {@code Class} object for this event and so cannot subscribe
 * to it, however it is compiled - this is a hard platform limit, not an oversight. Subscribing to
 * this event only works for code that shares AutoAmethyst's classloader, i.e. is compiled or
 * embedded directly alongside it. A separate plugin jar should instead poll
 * {@link com.shallowplague.amethyst.api.AutoAmethystApi#stats()} through
 * {@code PluginManager.getPlugin("auto-amethyst")} plus reflection.
 */
public record HarvestBreakEvent(long totalBreaks, int yieldGained) {
}
