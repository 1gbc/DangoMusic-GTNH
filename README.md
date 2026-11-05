# DangoMusic GTNH Edition

Phase 3 development project for Minecraft 1.7.10 and Forge 10.13.4.1614. The bootstrap screen shows the current stage. The three providers, playback, original UI, lyrics and HUD will be integrated in subsequent phases.

The port is based on [DangoMusic v1.7.3](https://github.com/Sorihiko/DangoMusic/tree/1e82730e6cbf19301e33cc6901f210bcffe9ea2c). See [the audit](docs/PORTING_AUDIT.md), [the porting plan](docs/PORTING_PLAN.md) and [the current validation record](docs/PHASE3_VALIDATION.md).

## Build

Use JDK 25 for Gradle. The Forge Java sources target Java 8. On Windows run `gradlew.bat build`. Build artifacts are written to `build/libs`.

## License

GPL-3.0. See LICENSE. Build conventions and wrapper come from the GTNH ExampleMod template. See LICENSE-build-template.

