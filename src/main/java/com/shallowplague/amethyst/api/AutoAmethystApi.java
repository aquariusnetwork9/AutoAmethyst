package com.shallowplague.amethyst.api;

import java.util.List;
import java.util.Map;

/**
 * Read/control surface for other code to drive AutoAmethyst without depending on its internals.
 *
 * <h2>Why every type here is a JDK type</h2>
 * ZenithProxy loads each plugin jar in its own {@code URLClassLoader}, with only the core
 * application classes as a shared parent - sibling plugins cannot see each other's classes.
 * A type defined inside this plugin's own jar (including this interface) is therefore a
 * different {@code Class} object to any copy a separate plugin jar might compile or shade for
 * itself, and casting across that boundary fails with {@link ClassCastException}. Every method
 * on this interface is deliberately typed in {@code String}/{@code boolean}/{@code long}/
 * {@code List<String>}/{@code Map<String, Object>} - the JDK classes are loaded once, by a
 * classloader every plugin shares, so these are the only signatures that are safe to invoke
 * across that boundary.
 *
 * <h2>Same-classloader callers</h2>
 * Code compiled together with AutoAmethyst - for example, this package embedded directly into a
 * larger bundle plugin - can call {@link com.shallowplague.amethyst.AutoAmethystPlugin#api()} and
 * use this interface directly, with full type safety.
 *
 * <h2>Separate plugin jar callers</h2>
 * A plugin that stays a separate jar cannot reference this interface at compile time at all. It
 * can still reach every method here at runtime, with zero compile-time dependency on this plugin:
 * <pre>{@code
 * Object autoAmethyst = pluginManager.getPlugin("auto-amethyst"); // ZenithProxyPlugin, a core type
 * if (autoAmethyst != null) {
 *     Map<?, ?> stats = (Map<?, ?>) autoAmethyst.getClass().getMethod("stats").invoke(autoAmethyst);
 *     boolean paused = (boolean) autoAmethyst.getClass().getMethod("isPaused").invoke(autoAmethyst);
 * }
 * }</pre>
 * This works because {@code getPlugin} returns the core {@code ZenithProxyPlugin} interface (a
 * type every plugin shares) and reflection resolves methods and JDK-typed values dynamically,
 * without either side needing the other's classes on its compile classpath.
 *
 * <p>There is no built-in dependency-ordering between plugins, so a caller must be prepared for
 * {@code getPlugin} to return {@code null} if AutoAmethyst has not loaded yet or is not present.
 */
public interface AutoAmethystApi {

    /** Whether the harvest module is currently enabled. */
    boolean isEnabled();

    /** Enables or disables the harvest module and persists the choice to its config. */
    void setEnabled(boolean enabled);

    /** Short machine-oriented state name, e.g. {@code "IDLE"}, {@code "ENGAGE"}, {@code "PAUSED"}. */
    String stateName();

    /** True when the module is paused and needs {@link #resume()} to continue. */
    boolean isPaused();

    /** Human-readable reason for the current pause, or an empty string when not paused. */
    String pauseReason();

    /** Clears a pause and lets the module retry from idle. */
    void resume();

    /** Runs one deposit cycle at the next opportunity, regardless of how full the inventory is. */
    void requestDeposit();

    /** Re-reads config and resets in-flight state; call after changing config values externally. */
    void requestReload();

    /**
     * A human-readable diagnosis of what the module is doing and why, the same text logged for
     * the {@code autoamethyst why} command. Every line avoids real coordinates.
     */
    List<String> diagnose();

    /**
     * A point-in-time snapshot of counters and status, keyed by name. Every value is a JDK type
     * ({@link String}, {@link Boolean}, {@link Long}, {@link Integer}, or {@link Double}) so it
     * survives the reflective cross-plugin path described above without either side needing a
     * shared class for the value itself.
     *
     * <p>Keys: {@code state}, {@code paused}, {@code pauseReason}, {@code breaks}, {@code skipped},
     * {@code reverts}, {@code toolSwaps}, {@code deposits}, {@code shulkersStored},
     * {@code matureInBox}, {@code reachableCount}, {@code shardsOnGround}, {@code shardStacksOnGround},
     * {@code shardsStranded}, {@code yieldGained}, {@code yieldPerHour}, {@code pathfinderClamped}.
     */
    Map<String, Object> stats();
}
