# Devonian 1.32.9 dependency

This JAR was built from the official Minecraft 26.1 branch, targeting Minecraft 26.1.2.
It is a source build, not a downloaded GitHub release asset: the public releases
listed 1.28.9 while the 26.1 branch declared 1.32.9 at acquisition time.

- Source: https://github.com/Synnerz/devonian/tree/798f126ee0e765b7a1bd41405d27523882210278
- Source archive: https://codeload.github.com/Synnerz/devonian/zip/798f126ee0e765b7a1bd41405d27523882210278
- Source archive SHA-256: `2c8eca980ff8ccb1fe2e0a7f6022edd10c1b8243b41575ba462b1e24eed83595`
- JAR SHA-256: `7313a02aff8930441862487cb7feb50f548d105b0647f236166e1fd66563b857`
- Toolchain: Java 25.0.3, Gradle 9.5.1, Kotlin 2.3.20, Loom 1.16.3.
- Build command (using DungeonProgressHud's wrapper): `gradlew -p <extracted-source> -Ploom_version=1.16.3 build --no-daemon`

The extracted source's settings.gradle maps the Loom plugin ID to
`net.fabricmc:fabric-loom:${requested.version}` via plugin resolutionStrategy.
This resolves the stable Loom module directly. Application source was unchanged.
The build used these environment variables for upstream provenance:

```text
GIT_COMMIT_HASH=798f126ee0e765b7a1bd41405d27523882210278
GIT_COMMIT_TIME=2026-09-20T21:24:19Z
GIT_COMMIT_MESSAGE=build: bump v1.32.9
```

The commit timestamp must remain ISO-8601 because Devonian parses it as an Instant.
Upstream build timestamps make independently rebuilt JAR hashes differ.

DungeonProgressHud compiles against 1.32.9 only. Older JARs are retained as
historical references. Install only one Devonian version in Minecraft's mods folder.
