# DungeonProgressHud

DungeonProgressHud is a client-side Fabric mod for Hypixel SkyBlock. It combines
Catacombs progression, run statistics, dungeon chest profit, and M7 drop tracking
in one configurable HUD.

**Current release:** 1.0.12 for Minecraft 26.1.2

**Development build:** 1.0.14

[Download 1.0.12](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.12)

## What it tracks

### Catacombs progress

- Current Catacombs level and total XP
- Progress toward the next level and a configurable target level
- Remaining XP and estimated runs to the target
- Observed XP per run on the detected floor, or manually configured XP per run
- Last run XP, scoped run count, and XP per hour
- Automatically detected normal and Master Mode floor
- Dungeon-aware session time with a five-minute grace period between runs

Run completions are read from chat and dated archive logs. Profile refreshes store
XP intervals; unattributed XP contributes to session XP/hour without becoming an
invented run or affecting observed XP/run.

### Dungeon chest profit

- Wood through Bedrock reward chests
- Croesus chest rewards
- Bazaar and auction pricing through bundled SkyBlockAPI 4.2.19
- Instant Buy or Instant Sell Bazaar valuation
- Lowest BIN, Median, or Mean auction valuation
- Optional essence, Dungeon Chest Key, and Kismet Feather accounting
- Incomplete-price detection instead of silently treating unavailable prices as real zeroes
- Duplicate claim and reroll suppression
- Session, total, and custom rolling-window statistics

The calculation used for new records is:

```text
gross reward value
- chest coin cost
- optional Dungeon Chest Key value
- optional Kismet Feather value
= net profit
```

DPH calculates every reward individually, including books, armor, essence and
shards. Confirmed chests with missing prices are saved for retry, including across
restarts. The HUD shows their count as Pending Prices until their value is known.
Devonian pricing can be used as a temporary fallback while market data loads.

### M7 drop tracking

- Scoped M7 item-drop counts
- Session, total, and rolling-window views
- Resettable tracked-drop history
- Shared scope with run and profit statistics

## Requirements

- Minecraft 26.1.2
- Java 25
- Fabric Loader 0.19.3 or newer
- Fabric API
- Fabric Language Kotlin
- Devonian 1.31.9
- SkyBlock Profile Viewer 1.8.4+ or SkyBlocker for player-profile data

SkyBlockAPI and its compatible 26.1 support libraries are bundled inside the
DungeonProgressHud JAR. Do not install a second SkyBlockAPI copy alongside it.

## Installation

1. Install Fabric Loader for Minecraft 26.1.2.
2. Add Fabric API, Fabric Language Kotlin, and Devonian to the instance's `mods` folder.
3. Add SkyBlock Profile Viewer or SkyBlocker if you want live Catacombs profile data.
4. Download `DungeonProgressHud-1.0.12.jar` from the [1.0.12 release](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.12).
5. Place the JAR in the same `mods` folder and launch the game.

Open Devonian with `/devonian` to configure the HUD, pricing modes, visible
lines, target level, and cost-accounting options.

## Commands

| Command | Description |
| --- | --- |
| `/dph` | Show profile and pricing status. |
| `/dph refresh` | Refresh player data without recording a run. |
| `/dph reset` | Clear observed XP samples and reset the profile XP baseline. |
| `/dph prices` | Show pricing modes, cache health, fallback state, and missing IDs from the last chest. |
| `/dph session` | Use the current dungeon session for HUD statistics. |
| `/dph daily` | Use the last 24 hours for HUD statistics. |
| `/dph weekly` | Use the last seven days for HUD statistics. |
| `/dph total` | Use all retained history for HUD statistics. |
| `/dph <scope>` | Set a custom rolling window such as `2`, `7`, `1w`, `2w`, or `1m`. |
| `/dph summary [session\|daily\|weekly]` | Print a run, XP, time, and profit summary. |
| `/dph importlogs` | Import recent dungeon completion messages from client logs. |
| `/dph fake` | Record the selected or open reward chest without clicking its claim button. |
| `/dph profit` | Show the current profit tracker state. |
| `/dph profit toggle` | Toggle the shared tracker between session and total scope. |
| `/dph profit session\|total\|<window>` | Change the shared tracker scope. |
| `/dph items` | Show the current item tracker state. |
| `/dph items toggle` | Toggle the shared tracker between session and total scope. |
| `/dph items session\|total\|<window>` | Change the shared tracker scope. |
| `/dph items reset` | Clear tracked M7 item drops. |
| `/dph order reset` | Restore the default HUD line order. |

The default fake-open key is `H`. It can be changed in Minecraft's keybind settings.

## Pricing behavior

The default pricing modes are:

- Bazaar: **Instant Buy**
- Auction: **Median**, to reduce the influence of extreme listings
- Missing prices: **Mark Chest Incomplete**
- Devonian loading fallback: **Enabled**
- Essence value: **Included**
- Dungeon Chest Key cost: **Excluded**
- Kismet Feather cost: **Included**

DPH refreshes the public Bazaar and auction feeds asynchronously about ten seconds
after joining and every five minutes thereafter. Failed refreshes retain existing
prices and retry after 30 seconds. Bazaar prices use Hypixel's volume-weighted
averages with the correct Instant Buy/Instant Sell side; see the
[Hypixel Bazaar API documentation](https://api.hypixel.net/#tag/SkyBlock/paths/~1v2~1skyblock~1bazaar/get).
`/dph prices` reports refresh age, failures, pending claims, and loaded cache sizes.
The first launch of 1.0.13 selects Median for auction prices; this remains configurable.

## Player data

While the HUD is visible, profile data refreshes approximately every five
minutes. Manual `/dph refresh` calls update the display and XP baseline without
creating a run or changing Last Run XP.

## Saved data

Persistent data is stored at:

```text
<Fabric config directory>/DungeonProgressHud/runs.json
```

Records include runs, observed XP, chest profit, scoped Kismet uses, and tracked
M7 drops. New chest records also store gross value, individual costs, quote
sources, priced items, and missing item IDs.

Old records retain their saved `profit` value during migration. They are never
repriced using today's market data.

Total also includes the saved lifetime profit and chest-count remainder from
versions that kept only the last 250 chests. That remainder has unknown dates and
ownership, so it is treated as legacy local history and excluded from session and
rolling-window views. Individual deleted rewards cannot be reconstructed from a
lifetime counter.

## Building from source

The project targets Kotlin 2.3.20 and Java 25. Set `JAVA_HOME` to a Java 25 JDK.
The committed wrapper supplies Gradle 9.5.1; Loom is pinned to 1.16.3.

```powershell
./gradlew build
```

The production artifact is written to:

```text
build/libs/DungeonProgressHud-1.0.14.jar
```

Run only the unit tests with:

```powershell
./gradlew test
```

## Changes in 1.0.14

- Recognize middle-clicks generated by inventory mods such as NoFrills when the
  player left-clicks a chest, plus right-click and shift-click menu activation.
  Only server-confirmed purchases affect profit.
- Confirm Kismet use from the server's chat message or inventory marker, counting
  each use once even when both arrive. A confirmed Kismet starts the session timer.
- Accept fractional Cata XP in completion chat and log imports, rounding to the
  nearest whole XP for saved history. These runs now update Runs, Last Run,
  observed XP/run, and the remaining-run estimate.

## Changes in 1.0.13

- Restore lifetime profit and the correct average chest value in Total mode.
- Read formatted scoreboard floors correctly and update the HUD from completion
  chat. Observed XP/run and runs remaining use that floor; without an observed
  run they show N/A until a run completes (Hardcoded mode remains available).
- Refresh market prices after joining, default to median auction prices, and
  correct the reversed Bazaar buy/sell mapping.
- Share reward parsing between normal chests and Croesus, including ultimate
  books, quantities and item IDs. Price availability no longer controls whether
  a reward can be identified. Unknown rewards keep the chest incomplete.
- Persist confirmed claims awaiting prices and retry without duplicate accounting.

## Changes in 1.0.12

- Updated the development dependency and required Devonian version to 1.31.9 for
  Minecraft 26.1.2.

- Purchase clicks create pending attempts. A matching server reward heading or
  purchased marker confirms the claim. Rerolls require the server's used-Kismet
  marker. Failed or unconfirmed attempts do not change totals. H-key fake opens
  remain explicit and persist `source = "fake-open"`.
- Claim identity is independent of prices. Confirmed Kismet uses retain their
  claim association, price availability, and consumption state in the saved ledger.
  A missing Kismet quote blocks recording unless Count Missing As Zero is selected.
- All reward valuation uses DPH's pricing service. Whole-chest Devonian aggregate
  estimates no longer override incomplete itemized calculations.
- New records carry account UUID and profile ID. Chest, item and Kismet totals
  include the current profile plus older local records with unknown ownership,
  filtered by the selected time scope. Old ownership fields remain unchanged.
  `/dph legacy` shows historical lifetime counters, including records discarded
  by older versions that cannot be assigned to a time window.
- HUD scope labels show only the time scope and stay inside the panel. Chest
  parsing recognizes essence quantities in reward names as well as lore and
  scans the complete reward slot range used by Devonian 1.31.9.
- Chest, drop, and Kismet histories are no longer truncated. Records discarded by
  earlier versions cannot be reconstructed automatically. Legacy pricing remains
  explicitly unknown and is never recomputed from today's prices.
- `/dph reset` resets the observed averaging boundary while preserving run history.
  XP/run uses completed runs on the displayed floor; the `/h session` suffix uses
  current-profile session XP, including reconciled unattributed intervals. Summary
  messages label their rate denominator.
- Log import reads dates from `YYYY-MM-DD-N.log[.gz]` archive names and handles
  midnight rollover. Undated files, including `latest.log`, are skipped rather
  than assigned a guessed date. Imported ownership remains unknown. Failed files
  are retried independently.
- History writes run in one background queue. `runs.json.bak` retains the previous
  readable version. Unreadable history disables automatic replacement. `/dph`
  reports persistence health; `/dph recoverbackup` validates the backup and
  preserves the damaged original as `runs.json.unreadable-<id>` before recovery.
- Configuration uses Devonian's existing **Mod** subcategory; DPH no longer alters
  private category structures. Disabling DPH stops automatic chest/run recording.

Chest context is bounded to the current connection and selected run. When the
server supplies no durable Croesus run ID, that identity cannot be reconstructed
across a restart; pending costs remain saved but are never attached to a guessed
new chest. Inventory/chat confirmation paths still require in-game validation
against live server behavior; unit tests exercise the markers used by the bundled
Devonian reference implementation.

## Development dependencies and preview

`libs/devonian-1.31.9.jar` is the repository-supplied development dependency; obtain
it by cloning this repository. Its SHA-256 is:

```text
17ebb41ee38738c0e69aa03db16f40d774265880ca8a51f701b7c132f4ca31cd
```

This 1.31.9 JAR is built from the official 26.1 branch; see
[dependency provenance](libs/devonian-1.31.9.md) for the exact commit and build steps.
The build verifies that checksum before compiling. GitHub Actions builds and
runs the tests with Java 25. The local inspection checkout is no longer tracked
as a submodule.

`run-hud-preview.bat` uses the configured JDK and resolves paths from its own
location. The desktop preview and Minecraft HUD share `HudGeometry`; both modes
measure a shared width. Preview data and fonts are illustrative. Missing source
files now produce an explicit error. The profit-range counting script is a
heuristic over retained samples, not evidence that an item dropped.

## License

DungeonProgressHud is available under the [MIT License](LICENSE).
