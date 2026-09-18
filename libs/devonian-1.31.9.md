# Devonian 1.31.9 dependency

This JAR was built from the official Minecraft 26.1 branch, targeting Minecraft 26.1.2.
It is a source build, not a downloaded GitHub release asset: the public releases
listed 1.28.9 while the 26.1 branch declared 1.31.9 at acquisition time.

- Source: https://github.com/Synnerz/devonian/tree/286a21d076c8e1a13e2364f2344efacbceb350eb
- Source archive: https://codeload.github.com/Synnerz/devonian/zip/286a21d076c8e1a13e2364f2344efacbceb350eb
- Source archive SHA-256: `c02aebda0870f8e068430c3e1b8190254cbcc22c4feff1bc6c60085aae157dc2`
- JAR SHA-256: `17ebb41ee38738c0e69aa03db16f40d774265880ca8a51f701b7c132f4ca31cd`
- Toolchain: Java 25.0.3, Gradle 9.5.1, Kotlin 2.3.20, Loom 1.16.3.
- Build command (using DungeonProgressHud's wrapper): `gradlew -p <extracted-source> -Ploom_version=1.16.3 build --no-daemon`

The extracted source's settings.gradle maps the Loom plugin ID to
`net.fabricmc:fabric-loom:${requested.version}` via plugin resolutionStrategy.
This resolves the stable Loom module directly. Application source was unchanged.
The build used these environment variables for upstream provenance:

```text
GIT_COMMIT_HASH=286a21d076c8e1a13e2364f2344efacbceb350eb
GIT_COMMIT_TIME=2026-09-16T03:42:52Z
GIT_COMMIT_MESSAGE=feat: add DodgeList
```

The commit timestamp must remain ISO-8601 because Devonian parses it as an Instant.
Upstream build timestamps make independently rebuilt JAR hashes differ.

DungeonProgressHud compiles against 1.31.9 only. Older JARs are retained as
historical references. Install only one Devonian version in Minecraft's mods folder.
