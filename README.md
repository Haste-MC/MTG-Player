# MTG-Player

Lokaler Commander-Tisch mit KI-Gegnern auf Basis von Forge, mit eigener Browser-UI.
Design: `docs/superpowers/specs/2026-09-16-mtg-player-design.md`.

## Voraussetzungen

- Java 17, Maven ≥ 3.8.1 (`sudo apt install openjdk-17-jdk-headless maven`)
- Node ≥ 20 (für das Frontend, ab M2)

## Einmalig: Forge bauen

```bash
git submodule update --init --depth 1
cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
```

## Bridge

```bash
cd bridge
mvn -q test                 # alle Tests, inkl. eines kompletten KI-Spiels (Minuten)
mvn -q compile exec:java    # vier zufällige Precons, KI-Spiel auf stdout
mvn -q compile exec:java -Dexec.args="42"   # mit festem Seed
```

Forges Nutzerdaten liegen unter `~/.mtg-player/`.
`ForgeBoot.init()` schreibt bei jedem Start `bridge/assets/forge.profile.properties` (generiert, git-ignoriert) und lenkt Forges Nutzerdaten damit nach `~/.mtg-player/`; `bridge/assets/res` ist ein Symlink auf `forge/forge-gui/res`.
Der Assets-Pfad ist mit `-Dmtgplayer.assets=<dir>` überschreibbar; Maven setzt ihn für `test` und `exec:java` automatisch.
