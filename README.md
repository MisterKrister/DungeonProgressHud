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
- Optional chest count and average chest profit display

## Requirements

- Minecraft 1.21.10
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
/dph importlogs
/dph profit
/dph fake
/dph profit toggle
/dph profit session
/dph profit total
/dph profit <window>
```

- `/dph` shows the current mod status.
- `/dph refresh` forces an API refresh and updates the XP baseline without adding an observed run sample.
- `/dph reset` clears observed XP samples and resets the API XP baseline.
- `/dph session`, `/dph daily`, and `/dph weekly` print run and profit summaries.
- `/dph importlogs` imports recent dungeon completion messages from client logs.
- `/dph fake` records the currently selected/open reward chest without clicking it.
- `/dph profit toggle`, `session`, `total`, or `<window>` change the chest profit HUD view. Windows accept values like `7d`, `14d`, or `2w`.

## API Usage

The mod can use a Hypixel API key to fetch the current player's selected
SkyBlock profile and Catacombs experience. Do not commit API keys or local
config files to the repository.

When the HUD is visible, the mod attempts an automatic API refresh every five
minutes. Manual refreshes are intentionally baseline-only so they do not skew
Last Run XP or observed XP/run estimates.

## Building

```powershell
gradle build
```

The built jar is written to `build/libs/`.

## License

MIT
