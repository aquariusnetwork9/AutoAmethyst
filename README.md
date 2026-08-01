# AutoAmethyst

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that AFK-farms fully grown amethyst
clusters from a fixed geode rig.

Built against **stock ZenithProxy 1.21.4**. No fork, no core changes, no Baritone patches — it
loads into an unmodified `java`-channel instance from `plugins/`.

> **Status: 0.1.0, compile-verified only.** It has never been run against a live server. Treat the
> first run as a test.

---

## What it does

Breaks `minecraft:amethyst_cluster` blocks inside a box you define, continuously, with the best
Fortune pickaxe it can find, and never touches anything else.

**Hard guarantees, enforced in code rather than by convention:**

| Guarantee | How |
|---|---|
| Only ever breaks mature clusters | The target block is compared by identity against `BlockRegistry.AMETHYST_CLUSTER` on **every tick**, immediately before the click is submitted — not once when the target is chosen |
| Never breaks buds or budding amethyst | Falls out of the above. Buds drop nothing, and `budding_amethyst` is unobtainable — one stray break permanently deletes a growth site |
| Never places a block | There is no place code path in the plugin |
| Never uses Silk Touch | A silk pickaxe drops the cluster block and **zero shards**. Silk is disqualifying, not merely suboptimal |
| Never mines through a wall | Zenith's block-target raycast deliberately ignores intervening blocks; the plugin adds its own first-hit line-of-sight check that a vanilla client would have to pass |
| Never exceeds vanilla reach | Reach comes from the server's own `BLOCK_INTERACTION_RANGE` attribute (4.5). The config value can only **lower** it |

## Anticheat posture

Breaking is driven through Zenith's `PlayerInteractionManager` via an ordinary left-click input —
the same path Zenith's own interact process uses. That gets real destroy progress computed against
the held item, correct sequence numbers, swing packets, the 5-tick inter-block destroy delay, and
the rotation gate for free.

**No block-break packet is ever constructed by hand.** That is deliberate: hand-rolled
`ServerboundPlayerActionPacket` mining with no rotation is exactly what gets vetoed outright.

The plugin also **detects that veto**. If a block we believed we broke reappears within a couple of
seconds, that is never regrowth (a stage takes ~34 minutes) — it is the server rejecting the break
and reverting the optimistic client-side prediction. It is logged loudly and counted separately,
because it means the farm is producing nothing at all while looking like it works.

## Movement

Three modes. Pick with `autoamethyst mode <mode>`.

| Mode | What it does | Requirements |
|---|---|---|
| `stationary` *(default)* | Never moves. Harvests everything in reach of where it is parked | Zero anticheat surface |
| `waypoint` | Paths between waypoints with Zenith's pathfinder | **Ladders or vines.** See below |
| `scaffold` | Walks and climbs a single scaffolding column by hand | A rig with one shared vertical column |

### ⚠ The pathfinder cannot route scaffolding

Zenith's path *planner* has no scaffolding movement — the branch is commented out in
`MovementHelper.java` and scaffolding is explicitly excluded from its walkable checks. Only
`CLIMBABLE`-tagged blocks (ladder, vine) are planned through. **`waypoint` mode will fail on a
scaffolding rig.** Either rebuild the vertical runs as ladders, or use `scaffold` mode.

`scaffold` mode works because the *physics executor* is a different thing from the planner: Zenith
faithfully models scaffolding collision, climbing and sneak-descent. So the plugin just holds the
ordinary vanilla movement keys — forward, jump, sneak — and Zenith's own physics simulation emits
completely normal position packets. Nothing is forged.

A `scaffold` leg is three phases: walk to the shared column, climb to the target Y, walk out to the
stand position. It **sneaks while walking** by default, which engages vanilla ledge protection so
the bot cannot walk off a platform, and stops it overshooting stand positions. Growth takes hours
per cluster, so the lost speed costs nothing.

Any leg that stalls fails the leg and pauses the module with a reason, rather than grinding forever.

## Setup

1. Drop the jar in `plugins/` and restart the proxy (plugins load at startup; there is no hot reload).
2. Walk the bot to one interior corner of the geode: `autoamethyst box corner1`
3. Walk to the opposite corner: `autoamethyst box corner2`
4. Put Fortune III pickaxes (**no Silk Touch**) in the inventory.
5. `autoamethyst on`

For a multi-level rig, add stand positions by standing on each and running `autoamethyst waypoint
add`, set the column with `autoamethyst column here`, then `autoamethyst mode scaffold`.

## Commands

`/autoamethyst` (alias `/amethyst`)

```
on/off                      toggle
status                      breaks, shards, shards/hr, reverts, skips
resume                      clear a pause
box corner1|corner2|show    define the geode box from the bot's position
mode stationary|waypoint|scaffold
waypoint add|clear|list     stand positions, taken from the bot's position
column here                 set the scaffolding column
reach <blocks>              lower the reach cap (0 = server default)
delay <ticks>               idle time after each break
los on/off                  line-of-sight requirement
tool on/off                 automatic pickaxe selection
swapat <percent>            durability threshold for swapping pickaxes
realcoords on/off           absolute coordinates in the harvest log
```

## Coordinates and opsec

The geode box, waypoints and column are real coordinates on an anarchy server. They live in
`plugins/config/auto-amethyst.json` and **nowhere else**:

- Every position is set from where the bot is standing. You never type or paste a coordinate.
- `box show` and `waypoint list` report sizes and counts, never positions.
- The harvest log records positions as offsets from the box corner, not absolute coordinates.
  `realcoords on` overrides this — only turn it on if you understand where that file ends up.
- Pause and error messages never echo a configured position, because they also go to the in-game
  alert and to Discord.

**Do not commit a populated `auto-amethyst.json` anywhere.**

## Yield expectations

With a Fortune III pickaxe, verified against the 1.21.4 loot table and the `ore_drops` bonus formula:

- Base drop: **4 shards** with any pickaxe, 2 with anything else.
- Fortune III multiplier is uniform over **{1, 1, 2, 3, 4}**, so E = 2.2.
- **Expected 8.8 shards per cluster.**

Growth is the bottleneck, not harvesting: each of a budding block's 6 faces advances one stage every
~34 minutes on average, so a face produces a cluster roughly every **2 h 17 min**, Erlang-4
distributed. Theoretical ceiling is `faces × 8.8 / 2.276 h`.

Harvest on sight — do not batch. Because a mature cluster blocks its own face until it is removed,
idle time between maturity and harvest is pure loss; a fixed sweep period caps out at about 59% of
continuous harvesting no matter how you tune it.

If measured output is far under theory, check **simulation distance first**: random ticks only run in
chunks near a player, so parts of the geode may simply not be ticking from where the bot parks.

## Mending is dead weight

Amethyst clusters drop no experience, so Mending never repairs anything here. Use Unbreaking III and
let the plugin swap pickaxes at a durability threshold instead (`swapat`, default 10%).

## Building

```
./gradlew build
```

Produces `build/libs/AutoAmethyst-<version>.jar`. Requires JDK 25 to build; targets Java 21.

## Licence

CC0-1.0, inherited from the [ZenithProxy example plugin
template](https://github.com/rfresh2/ZenithProxyExamplePlugin) this is built on.
