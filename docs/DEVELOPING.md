# Developing Stonkcraft

## Build and run

```sh
./gradlew build                 # jar in build/libs/stonkcraft-<version>.jar (needs Fabric API in your mods folder)
./gradlew runClient             # dev client
./gradlew runClient -Pshowroom  # small flat dev world; -Puitour screenshots every screen, -Pshots every block and logo
./gradlew runClient -Psurvival  # the survival world in run/saves/nyse-survival
./gradlew runClient -Pdemo      # the old showcase floor in run/saves/hcdemo, screenshot, quit
./gradlew runServer             # dev server
./gradlew test
```

Gradle has to run on JDK 25 (Loom 1.18 needs it; the mod itself targets Java 21). Use JDK 25 as your `JAVA_HOME`, or
set `org.gradle.java.home` in `~/.gradle/gradle.properties`. `-Pcompat` adds Sodium, Lithium and Mod Menu to the dev
client, and `-Pworld=<save>` opens any world in `run/saves`. Pushing a `v*` tag publishes a release
(`.github/workflows/build.yml`).

## Source layout

* `hl/`: wire formatting, read-only HTTP and WebSocket clients
* `market/`: prices for the main dex, the xyz dex and commodity tokens
* `trading/`: paper and watch accounts, access control
* `unit/`: Commodity Exchange items, pegs and settlement
* `signal/`: signals, the engine, copy desks
* `game/`: market clock, speedruns, advancements, liquidation effects
* `block/`, `net/`, `command/`; `client/`: screens, renderers, HUD
