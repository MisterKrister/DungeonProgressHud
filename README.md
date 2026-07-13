# DungeonProgressHud

DungeonProgressHud is a client-side Fabric mod for Hypixel SkyBlock. It combines
Catacombs progression, run statistics, dungeon chest profit, and M7 drop tracking
in one configurable HUD.

**Current release:** 1.0.9 for Minecraft 26.1.2

[Download 1.0.9](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.9)

## What it tracks

### Catacombs progress

- Current Catacombs level and total XP
- Progress toward the next level and a configurable target level
- Remaining XP and estimated runs to the target
- Observed or manually configured XP per run
- Last run XP, scoped run count, and XP per hour
- Automatically detected normal and Master Mode floor
- Dungeon-aware session time with a five-minute grace period between runs

Run completions are read from chat and recent logs. Profile refreshes can fill in
missed completion records without counting the same XP twice.

### Dungeon chest profit

- Wood through Bedrock reward chests
- Croesus chest rewards
- Bazaar and auction pricing through bundled SkyBlockAPI 4.2.8
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

DPH calculates new chest profit directly. Devonian pricing can be used as a
temporary compatibility fallback while SkyBlockAPI's asynchronous caches load.

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
- Devonian 1.25.9 or compatible
- SkyBlock Profile Viewer 1.8.4+ or SkyBlocker for player-profile data

SkyBlockAPI and its compatible 26.1 support libraries are bundled inside the
DungeonProgressHud JAR. Do not install a second SkyBlockAPI copy alongside it.

## Installation

1. Install Fabric Loader for Minecraft 26.1.2.
2. Add Fabric API, Fabric Language Kotlin, and Devonian to the instance's `mods` folder.
3. Add SkyBlock Profile Viewer or SkyBlocker if you want live Catacombs profile data.
4. Download `DungeonProgressHud-1.0.9.jar` from the [1.0.9 release](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.9).
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

The default modes match SkyMyce-style chest valuation:

- Bazaar: **Instant Buy**
- Auction: **Lowest BIN**
- Missing prices: **Mark Chest Incomplete**
- Devonian loading fallback: **Enabled**
- Essence value: **Included**
- Dungeon Chest Key cost: **Excluded**
- Kismet Feather cost: **Included**

SkyBlockAPI refreshes its Bazaar and auction caches asynchronously. `/dph prices`
reports `Loading`, `Partial`, or `Ready`; DPH will not silently record a
zero-valued chest when the required pricing data is unavailable.

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

## Building from source

The project targets Kotlin 2.3.20 and Java 25. A Java 25 JDK and Gradle are
required.

```powershell
gradle build
```

The production artifact is written to:

```text
build/libs/DungeonProgressHud-1.0.9.jar
```

Run only the unit tests with:

```powershell
gradle test
```

## License

DungeonProgressHud is available under the [MIT License](LICENSE).
