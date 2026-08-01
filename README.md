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
| Only breaks what is on the allowlist | Every break goes through one `BreakDriver`, which re-checks the block against `HarvestPolicy` by identity on **every tick** immediately before the click — not once when the target is chosen. The list is: the enabled harvest stages, plus shulker boxes for the deposit. Nothing else, in any mode |
| **Never breaks budding amethyst** | Vetoed unconditionally in three independent places: the target scanner, the breakable allowlist, and `BreakDriver` itself — the single line every break in the plugin passes through, which no caller, config combination or future refactor can route around. If the veto ever fires it logs an error, because reaching it means something else is wrong |
| Never breaks immature buds by default | Double-gated behind `protectBuds` (on by default) **and** silk mode **and** a per-stage toggle. Normal shard farming cannot reach it |
| Never places a block except a shulker | Shulkers are the only placeable, and only at the configured deposit spot |
| Never blocks a growth face | A placement is refused if the target is not clear air or if any of its six face neighbours is a budding amethyst. Buds only grow into air, so a block on a growth face silently stops it producing — re-checked at the moment of placing, not just when configured |
| **The pathfinder can never break or place** | `allowBreak` and `allowPlace` default to **true** in ZenithProxy, meaning pathing treats mining a wall as just another movement. The module clamps both off (plus `allowBreakAnyway`, which is the explicit override) and re-verifies every tick |
| Never mines through a wall | Zenith's block-target raycast deliberately ignores intervening blocks; the plugin adds its own first-hit line-of-sight check that a vanilla client would have to pass |
| Never exceeds vanilla reach | Reach comes from the server's own `BLOCK_INTERACTION_RANGE` attribute (4.5). The config value can only **lower** it |

The two block guards are deliberately independent because they fail differently. The allowlist
governs blocks the plugin deliberately aims at. The pathfinder clamp governs blocks the *pathfinder*
would decide to remove on its own initiative to clear a route — which, left at its stock defaults,
it absolutely will.

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

## Harvest modes

| Mode | Tool | Breaks | Yield |
|---|---|---|---|
| `shards` *(default)* | pickaxe **without** Silk Touch | fully grown clusters only | 4 shards base, **8.8 average with Fortune III** |
| `silk` | pickaxe **with** Silk Touch | clusters, and optionally each bud stage | the blocks themselves |

**Fortune does nothing on immature buds.** They drop *nothing at all* without Silk Touch, so there
is no drop for Fortune to multiply — only the terminal cluster stage has a non-silk drop. That is
why silk mode exists at all: it is the only way to get anything out of a bud.

It is also why bud harvesting is off by default and gated twice. Silk-harvesting a bud trades away
the growth already invested in that face — up to ~2h17m — for a bud block. Worth it if you want bud
blocks; strictly worse than waiting if you want shards. To enable:

```
autoamethyst harvest silk
autoamethyst buds allow          # turns off the master protection
autoamethyst stage large on      # and each stage individually
```

## Collecting and depositing

**Drop collection** (`collect on`, default on). Passive pickup is not enough: a broken block's drop
can fly a couple of blocks, well outside the ~1 block vanilla pickup radius, so a stationary bot
steadily leaks yield onto the floor to despawn after five minutes. The bot walks onto drops with
ordinary sneaking movement — never the pathfinder, which is entitled to decide the way to reach
something is to mine through it. It is leashed to `maxDistance` from its stand position and walks
back afterwards so it cannot drift over a long AFK run. A drop it genuinely cannot reach is written
off rather than chased forever.

**Deposit** (`deposit here`, then `deposit on`). When free inventory slots drop to
`triggerFreeSlots`, the bot runs a full deposit cycle:

1. walk to the shulker box at the deposit position, open it, move the harvest in
2. when that shulker fills: close it, break it (shulkers keep their contents), pick it up, place a
   fresh empty one from inventory, carry on
3. when there is nothing left to deposit: carry every filled shulker to the **storage chest** and
   leave them there
4. walk back to the stand position it was harvesting from

Set the chest by standing next to it and running `deposit chest here` — it finds the nearest
container within 4 blocks, so you never type a coordinate. Any container works. **The chest is never
broken**; it is not on the breakable allowlist, and if the chest position ever holds something
unexpected the run fails with a reason rather than clearing it.

Step 4 is not cosmetic. Without it the bot finishes standing at the chest, re-anchors there, and in
stationary mode never returns to the geode — the farm looks alive and produces nothing.

Keep the bot stocked with empty shulkers. If the inventory fills with *filled* shulkers and no chest
is set, the module pauses and tells you, rather than looping on a deposit that frees nothing.

> **Transfers use `ClickItem`, not shift-click.** Stock Zenith's `ShiftClick` sends an empty
> `changedSlots` map — its own source comments flag this as a likely anticheat problem. On a server
> that validates click packets it is rejected, and the failure is *silent*: the transfer simply never
> happens. So every move is a pick-up/put-down pair. Slower, but it actually works.

The deposit is paced, retries a window the server closes mid-transfer (which laggy anarchy servers
do), and pauses with a reason rather than grinding if anything is genuinely wrong — a full shulker
with no spare, something other than a shulker in the deposit spot, or transfers that stop taking
effect.

Put the deposit spot **outside the geode**, clear of any budding amethyst. In `scaffold` movement
mode the deposit walk uses the pathfinder, which cannot route scaffolding, so it needs a walkable
route.

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
5. Stand somewhere clear **outside** the geode and run `autoamethyst deposit here` — that is where
   the working shulker goes. Carry a few empty shulker boxes.
6. Stand next to your storage chest and run `autoamethyst deposit chest here`, then
   `autoamethyst deposit on`.
7. `autoamethyst on`

For a multi-level rig, add stand positions by standing on each and running `autoamethyst waypoint
add`, set the column with `autoamethyst column here`, then `autoamethyst mode scaffold`.

## Commands

`/autoamethyst` (alias `/amethyst`)

```
on/off                      toggle
status                      breaks, yield, yield/hr, reverts, skips, deposits
resume                      clear a pause
box corner1|corner2|show    define the geode box from the bot's position

harvest shards|silk         which stages to break and which pickaxe to require
buds protect|allow          master bud protection (on by default)
stage cluster|large|medium|small on/off    per-stage silk toggles

mode stationary|waypoint|scaffold
waypoint add|clear|list     stand positions, taken from the bot's position
column here                 set the scaffolding column

collect on/off              walk onto dropped items
deposit here                set the shulker position from the bot's position
deposit chest here          set the storage chest (nearest container within 4 blocks)
deposit haul on/off         carry filled shulkers to the chest
deposit on/off|status       shulker deposit cycle

reach <blocks>              lower the reach cap (0 = server default)
delay <ticks>               idle time after each break
los on/off                  line-of-sight requirement
tool on/off                 automatic pickaxe selection
swapat <percent>            durability threshold for swapping pickaxes
realcoords on/off           absolute coordinates in the harvest log
```

`status` reports **Pathfinder clamped**. If that ever reads `NO`, something re-enabled the
pathfinder's ability to break and place; the module pauses rather than run in that state.

## Coordinates and opsec

The geode box, waypoints and column are real coordinates on an anarchy server. They live in
`plugins/config/auto-amethyst.json` and **nowhere else**:

- Every position is set from where the bot is standing. You never type or paste a coordinate.
- `box show` and `waypoint list` report sizes and counts, never positions.
- `deposit here` and `deposit status` report whether a position is set, never what it is.
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
