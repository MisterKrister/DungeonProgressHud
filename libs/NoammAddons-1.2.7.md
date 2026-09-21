# NoammAddons 1.2.7 dependency

- Upstream: https://github.com/Noamm9/NoammAddons
- Release: https://github.com/Noamm9/NoammAddons/releases/tag/1.2.7
- Source: https://github.com/Noamm9/NoammAddons/tree/6173dc6
- Artifact: https://github.com/Noamm9/NoammAddons/releases/download/1.2.7/NoammAddons-1.2.7-26.1.2-legit.jar
- SHA-256: `2e4b9d8ce5219cbad39f8fb66e43b739746f907c250357f5523b13c3d48499e1`
- License: CC0-1.0 (upstream LICENSE).

This is the unmodified published **legit** artifact for Minecraft 26.1.2.
It supplies the feature/settings registry, HUD editor, location tracking, and
loading-only price fallback. It is required at runtime and is not bundled into
DungeonProgressHud. `verifyNoammAddons` checks the compile dependency before building.

The feature singleton lives under `com.github.noamm9.features.impl.dungeon`
because NoammAddons scans its own package for singleton features and derives
the menu category from `impl.dungeon`. Its scanner initializes the feature and
registers its settings/HUD before loading the selected NoammAddons config.

Older Devonian JARs are historical references and are not used by this branch.
