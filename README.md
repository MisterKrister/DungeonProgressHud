# DungeonProgressHud

A client-side Fabric HUD for Hypixel SkyBlock: Catacombs and class progression,
run statistics, dungeon chest profit, and M7 drops.

**[Download 1.0.15](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.15)**
— Minecraft **26.1.2**, Devonian **1.32.9**.

## Installation

1. Use Java 25 and Fabric Loader 0.19.3+ for Minecraft 26.1.2.
2. Put Fabric API, Fabric Language Kotlin, Devonian **1.32.9**, and
   `DungeonProgressHud-1.0.15.jar` in your instance's `mods` folder. Replace older copies.
3. For live Catacombs and class XP, add SkyBlock Profile Viewer 1.8.4+ or a
   Minecraft 26.1.2-compatible SkyBlocker.

SkyBlockAPI 4.2.19 and its support libraries are bundled; no separate copy is needed.

## Using the HUD

Open `/devonian` → **Dungeon Progress HUD** to choose visible rows, targets, pricing,
and position. In an inventory, **Shift + drag** reorders rows within sections.
Use the **Items/Profit** switch to change views.

- Track level progress, XP/run, XP/hour, runs remaining, chest profit, and M7 drops.
- Choose Session, Total, or a rolling time window. Session time pauses after five
  minutes outside a dungeon.
- Set **Class Progress** to Current Class or All Classes; choose a class target,
  next-level or level-50 goals, and optional **Class Runs Remaining** estimates.
- Saved XP and last-run estimates appear while profile data loads. Profile data
  and prices refresh automatically; `/dph refresh` updates XP without adding a run.

## Commands

| Command | Purpose |
| --- | --- |
| `/dph` | Profile, price, and save status. |
| `/dph refresh` | Refresh profile data. |
| `/dph prices` | Pricing modes, refresh status, and missing prices. |
| `/dph session\|daily\|weekly\|total\|<window>` | Change the shared statistics scope. |
| `/dph summary [session\|daily\|weekly]` | Print a summary; defaults to the last 24 hours. |
| `/dph profit\|items [session\|total\|<window>\|toggle]` | Show tracker status or change its shared scope. |
| `/dph items add <item> [count] [chestCost]` | Add manual drops and their net profit. |
| `/dph items prices` | List supported items and default M7 chest costs. |
| `/dph items reset` | Clear drops across all profiles; keep chest profit history. |
| `/dph reset` | Reset XP averaging and baselines; keep run history. |
| `/dph order reset` | Restore the default row order. |
| `/dph fake` | Record an open reward chest without buying it; default hotkey: `H`. |
| `/dph importlogs` | Import dated archive logs; unowned runs stay outside current-profile totals. |
| `/dph legacy` | Show unowned history and historical counters. |
| `/dph recoverbackup` | Restore the history backup while preserving the current file. |

Windows accept days (`7` or `7d`), weeks (`2w`), or 30-day months (`1m`), capped at
365 days. `toggle` clears a rolling window first, otherwise switches Session/Total.

### Manual drops

```text
/dph items add handle
/dph items add recomb 3
/dph items add handle 1 90000000
```

Use Tab for item names and `/dph items prices` for default costs. Join SkyBlock and
let your profile load first. Count defaults to 1 (maximum 1,000); `chestCost` is
coins per item. Each adds one drop and one chest record, valued as
`(market price - chest cost) × count`. Missing prices block the entry. Repeating
an entry counts it again; manual entries add no runs, XP, keys, or Kismets.

## Pricing and saved data

Profit is reward value minus chest cost and any enabled key/Kismet costs. Defaults:
**Instant Buy** Bazaar prices, **Median** auction prices, essence and Kismets
included, Dungeon Chest Keys excluded. Confirmed chests with missing prices wait
under **Pending Prices**, including across restarts. **Count Missing As Zero**
records them immediately without later repricing.

History lives in `<Fabric config directory>/DungeonProgressHud/runs.json`;
`runs.json.bak` keeps the previous readable copy. Unreadable history blocks saving;
check `/dph` before using `/dph recoverbackup`. Existing recorded profits retain
their original values. Total includes legacy lifetime remainders with unknown dates.

## Building

Set `JAVA_HOME` to a Java 25 JDK, then run from the repository root:

```powershell
.\gradlew.bat build
.\gradlew.bat test
```

On Linux/macOS, use `bash ./gradlew build` or `bash ./gradlew test`.
Output: `build/libs/DungeonProgressHud-1.0.15.jar`.

The wrapper supplies Gradle 9.5.1; the build uses Kotlin 2.3.20 and Loom 1.16.3.
The pinned Devonian JAR is checksum-verified; see its
[provenance](libs/devonian-1.32.9.md). After building, `run-hud-preview.bat` opens
the local HUD preview.

[Release notes](https://github.com/MisterKrister/DungeonProgressHud/releases) ·
[MIT license](LICENSE)
