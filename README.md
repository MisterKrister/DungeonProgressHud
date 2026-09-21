# DungeonProgressHud

A client-side Fabric HUD for Hypixel SkyBlock: Catacombs and class progression,
run statistics, dungeon chest profit, and M7 drops.

**Experimental NoammAddons migration — 1.0.16-noamm.1**
— Minecraft **26.1.2**, [NoammAddons **1.2.7 legit**](https://github.com/Noamm9/NoammAddons/releases/tag/1.2.7).
Build from this branch using the instructions below. The Devonian release remains
available as [1.0.15](https://github.com/MisterKrister/DungeonProgressHud/releases/tag/v1.0.15).

## Installation

1. Use Java 25 and Fabric Loader 0.19.3+ for Minecraft 26.1.2.
2. Put Fabric API **0.155.2+26.1.2** or newer, Fabric Language Kotlin
   **1.13.13+kotlin.2.4.10** or newer, NoammAddons **1.2.7 legit**, and
   `DungeonProgressHud-1.0.16-noamm.1.jar` in your instance's `mods` folder. Replace older copies.
3. For live Catacombs and class XP, add SkyBlock Profile Viewer 1.8.4+ or a
   Minecraft 26.1.2-compatible SkyBlocker.

SkyBlockAPI 4.2.19 and its support libraries are bundled; no separate copy is needed.
Devonian is no longer required. Existing `DungeonProgressHud/runs.json` history,
pending chest claims, and row order are reused. HUD settings and position start
with NoammAddons defaults; configure them in `/na`. Devonian settings are left intact.

## Using the HUD

Open `/na` → **Dungeon**, then right-click **Dungeon Progress HUD** to choose
visible rows, targets, and pricing. Use NoammAddons' **HUD editor** to drag the
panel and scroll to scale it. In an inventory, **Shift + drag** reorders rows within sections.
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
The optional **NoammAddons Loading Fallback** uses NoammAddons' price cache while
SkyBlockAPI prices load; auction fallback prices are lowest BIN.

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
Output: `build/libs/DungeonProgressHud-1.0.16-noamm.1.jar`.

The wrapper supplies Gradle 9.5.1; the build uses Kotlin 2.4.10 and Loom 1.16.3.
The pinned NoammAddons JAR is checksum-verified; see its
[provenance](libs/NoammAddons-1.2.7.md). After building, `run-hud-preview.bat` opens
the local HUD preview.

The automated checks cover native feature discovery, pricing, packet hooks, and
saved history. A live SkyBlock check is still needed for the `/na` settings/HUD
editor, inventory row dragging, profile refresh, and reward chest tracking.

[Release notes](https://github.com/MisterKrister/DungeonProgressHud/releases) ·
[MIT license](LICENSE)
