# DungeonProgressHud

DungeonProgressHud is a client-side Fabric mod for Hypixel SkyBlock dungeons.
It adds a small HUD for Catacombs progress, estimated runs left, and dungeon
reward chest profit tracking.

Current release: **1.0.9** for **Minecraft 26.1.2**.

## Features

- Catacombs level and XP display
- Target Catacombs level progress
- Estimated runs left from scoped observed or configured XP per run
- Automatic player-data refresh through SkyBlock Profile Viewer or SkyBlocker while the HUD is visible
- Dungeon completion chat/log tracking for last run XP, scoped XP/run averages, and XP/hour
- Dungeon reward chest profit tracking using bundled SkyBlockAPI Bazaar and auction data
- Configurable Instant Buy/Instant Sell and Lowest BIN/Median/Mean valuations
- Missing-price health reporting and `/dph prices` diagnostics
- Optional Dungeon Chest Key, essence, and Kismet Feather cost accounting
- Detailed persisted chest breakdowns without repricing historical records
- Session, total, or rolling-window chest profit view
- Session, total, or rolling-window M7 item drop tracker
- Dungeon-aware session timer with a five-minute grace period between runs
- Shared session, total, or rolling-window scope for run count and observed XP; XP/hour uses the active session timer
- Optional chest count and average chest profit display

## Requirements

- Minecraft 26.1.2
- Fabric Loader 0.19.3 or newer
- Fabric API
- Fabric Language Kotlin
- Devonian 1.25.9 or compatible
- SkyBlockAPI 4.2.8 is bundled with the mod
- SkyBlock Profile Viewer 1.8.4 or newer, or SkyBlocker
- Java 25

## Commands

GUI settings are managed through Devonian with `/devonian`.

```text
/dph
/dph refresh
/dph reset
/dph prices
/dph session
/dph daily
/dph weekly
/dph total
/dph <scope>
/dph summary
/dph summary session
/dph summary daily
/dph summary weekly
/dph importlogs
/dph profit
/dph fake
/dph profit toggle
/dph profit session
/dph profit total
/dph profit <window>
/dph items
/dph items toggle
/dph items session
/dph items total
/dph items <window>
/dph items reset
```

- `/dph` shows the current mod status.
- `/dph refresh` refreshes player data and updates the XP baseline without adding an observed run sample.
- `/dph reset` clears observed XP samples and resets the profile XP baseline.
- `/dph prices` reports Bazaar and auction cache health, active valuation modes, loading fallback state, and IDs missing from the last chest.
- `/dph session`, `/dph daily`, `/dph weekly`, `/dph total`, and `/dph <scope>` change the shared run/profit/item tracker view. The Run Count, observed XP/run, and last-run lines use the same scope. Scopes accept values like `1`, `2`, `7`, `1w`, `2w`, or `1m`.
- `/dph summary`, `/dph summary session`, `/dph summary daily`, and `/dph summary weekly` print run and profit summaries.
- `/dph importlogs` imports recent dungeon completion messages from client logs.
- `/dph fake` records the currently selected/open reward chest without clicking it.
- `/dph profit ...` and `/dph items ...` remain aliases for changing or showing the same tracker scope.
- Undated legacy/backfill item drops are only counted in total.

## Player Data

DungeonProgressHud uses SkyBlock Profile Viewer's authenticated, cached profile
API and falls back to SkyBlocker when Profile Viewer cannot supply data. A
personal Hypixel API key is not required. Install either provider and ensure it
can authenticate with its service.

Chest pricing also requires no personal API key. SkyBlockAPI loads public
Hypixel Bazaar data and its auction price cache asynchronously. Devonian pricing
is used only as an optional compatibility fallback while those caches load.

Hypixel's direct SkyBlock profiles endpoint does not return profile data without
authenticated API access, so the mod deliberately uses one of those providers
instead of asking for a separate key.

While the HUD is visible, it refreshes profile data about every five minutes.
Using `/dph refresh` only updates the displayed profile data and XP baseline;
it does not count as a dungeon run or change Last Run XP.

Observed XP/run uses raw XP stored in `runs.json` plus any scoped profile-refresh
observations that do not duplicate a completion-chat record. Run Count, XP/run,
and Last Run follow the selected session, total, or rolling-window scope.

## Saved Data

DungeonProgressHud stores its run/profit data in the normal Fabric config
folder, under `DungeonProgressHud/runs.json`. This uses Fabric's instance paths,
so it works on Windows and Linux without hardcoded launcher folders.

New chest records retain gross reward value, opening/key/Kismet costs, pricing
sources, and missing item IDs. Historical records keep their stored net profit
and are not repriced during migration.

## Building

```sh
gradle build
```

The built jar is written to `build/libs/`.

## License

MIT
