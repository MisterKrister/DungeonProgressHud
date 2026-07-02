# DungeonProgressHud

DungeonProgressHud is a client-side Fabric mod for Hypixel SkyBlock dungeons.
It adds a small HUD for Catacombs progress, estimated runs left, and dungeon
reward chest profit tracking.

## Features

- Catacombs level and XP display
- Target Catacombs level progress
- Estimated runs left from observed or configured XP per run
- Automatic Hypixel API refresh while the HUD is visible
- Dungeon completion chat/log tracking for last run XP and XP/run averages
- Dungeon reward chest profit tracking
- Session, total, or rolling-window chest profit view
- Session, total, or rolling-window M7 item drop tracker
- Optional chest count and average chest profit display

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4 or newer
- Fabric API
- Fabric Language Kotlin
- Devonian 1.18.8 or compatible
- Java 21

## Commands

GUI settings are managed through Devonian with `/devonian`.

```text
/dph
/dph refresh
/dph reset
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
- `/dph refresh` forces an API refresh and updates the XP baseline without adding an observed run sample.
- `/dph reset` clears observed XP samples and resets the API XP baseline.
- `/dph session`, `/dph daily`, `/dph weekly`, `/dph total`, and `/dph <scope>` change the shared profit/item tracker view. Scopes accept values like `1`, `2`, `7`, `1w`, `2w`, or `1m`.
- `/dph summary`, `/dph summary session`, `/dph summary daily`, and `/dph summary weekly` print run and profit summaries.
- `/dph importlogs` imports recent dungeon completion messages from client logs.
- `/dph fake` records the currently selected/open reward chest without clicking it.
- `/dph profit ...` and `/dph items ...` remain aliases for changing or showing the same tracker scope.
- Undated legacy/backfill item drops are only counted in total.

## API Usage

Add your Hypixel API key in the HUD settings under `/devonian`. The mod uses it
to read your selected SkyBlock profile and current Catacombs XP.

While the HUD is visible, it refreshes the API data about every five minutes.
Using `/dph refresh` only updates the displayed API data and XP baseline; it
does not count as a dungeon run or change Last Run XP.

## Saved Data

DungeonProgressHud stores its run/profit data in the normal Fabric config
folder, under `DungeonProgressHud/runs.json`. This uses Fabric's instance paths,
so it works on Windows and Linux without hardcoded launcher folders.

## Building

```sh
gradle build
```

The built jar is written to `build/libs/`.

## License

MIT
