# DungeonProgressHud

DungeonProgressHud is a client-side Fabric mod for Hypixel SkyBlock dungeons.
It adds a small HUD for Catacombs progress, estimated runs left, and dungeon
reward chest profit tracking.

## Features

- Catacombs level and XP display
- Target Catacombs level progress
- Estimated runs left from observed or configured XP per run
- Dungeon reward chest profit tracking
- Session or total chest profit view
- Optional chest count and average chest profit display

## Requirements

- Minecraft 1.21.10
- Fabric Loader 0.18.4 or newer
- Fabric API
- Fabric Language Kotlin
- Devonian 1.18.8 or compatible
- Java 21

## Commands

```text
/dph
/dph refresh
/dph reset
/dph fake
/dph profit toggle
/dph apikey <key>
```

`/dph profit toggle` switches the chest profit HUD between session and total
views.

## API Usage

The mod can use a Hypixel API key to fetch the current player's selected
SkyBlock profile and Catacombs experience. Do not commit API keys or local
config files to the repository.

## Building

```powershell
gradle build
```

The built jar is written to `build/libs/`.

## License

MIT
