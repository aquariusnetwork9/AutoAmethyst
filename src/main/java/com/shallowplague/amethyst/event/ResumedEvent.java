package com.shallowplague.amethyst.event;

/**
 * Posted to {@code Globals.EVENT_BUS} when a pause is cleared.
 *
 * <p>See {@link HarvestBreakEvent} for this event's classloader scope: only code sharing
 * AutoAmethyst's classloader can subscribe to it.
 */
public record ResumedEvent() {
}
