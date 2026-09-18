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

## Spielen

Einmalig das Frontend bauen, dann die Bridge starten:

```bash
cd web && npm install && npm run build && cd ..
cd bridge && mvn -q compile exec:java
```

Browser: <http://127.0.0.1:8080>. Lobby → eigenes Precon und 1–5 KI-Precons wählen → Spiel starten.
Steuerung: leuchtende Karten sind klickbar, Rechtsklick = andere Fähigkeit, Enter/Leertaste = OK,
Esc = Abbrechen. Forge passt automatisch, wenn du nichts tun kannst (Arena-Stil).

Eigene Decks: in der Lobby "Textliste" wählen und einen Archidekt- oder Arena-Export einfügen
(`1 Sol Ring (c21) 263`, Sektion `Commander` oder erste legendäre Kreatur als Commander). Das Deck wird
unter `~/.mtg-player/decks/` gespeichert und erscheint danach unter "Eigenes Deck". Unbekannte Karten
werden mit Zeile gemeldet, das Spiel startet dann nicht.

Kartenbilder kommen von Scryfall und werden unter `~/.mtg-player/cache/images/` gecacht (erstes Spiel mit
neuen Karten lädt ein paar Sekunden nach; ohne Internet bleiben es Textboxen).

Phasenleiste über dem Prompt: Klick auf eine Phase setzt/entfernt einen Stop – dort wird angehalten, sofern du
etwas spielen kannst; ohne Volle Kontrolle passt Forge weiterhin automatisch, wenn nichts spielbar ist
(getrennt für eigene und gegnerische Züge, je nachdem wessen Zug gerade ist). "Volle Kontrolle" hält in
jeder Phase an und schaltet das automatische Passen ab. Tutor-Effekte (Bibliothek durchsuchen) öffnen einen Listen-Dialog mit Kartendetails.
Veraltete Klicks (Prompt hat inzwischen gewechselt) werden von der Bridge ignoriert.

Entwicklung am Frontend: `cd web && npm run dev` (Vite auf :5173, verbindet sich mit der Bridge auf :8081).
`npm run shot -- http://127.0.0.1:8080 out.png fixtures/table.json` erzeugt einen Playwright-Screenshot (Fixture-Zustand, `?debug=1` wird automatisch angehängt).

## Bridge

```bash
cd bridge
mvn -q test                                # alle Tests inkl. KI-Spiel und End-to-End über WebSocket (Minuten)
mvn -q compile exec:java                   # Bridge-Server (WebSocket 8081, HTTP 8080)
mvn -q compile exec:java -Dexec.args="--ai-demo 42"   # headless KI-Spiel wie in M1
```

Ports: `-Dmtgplayer.wsPort=…`, `-Dmtgplayer.httpPort=…`; Bind-Adresse `-Dmtgplayer.bind=…` (Standard `0.0.0.0`, damit Windows unter WSL2 per localhost rankommt); Frontend-Verzeichnis: `-Dmtgplayer.web=…` (Standard `../web/dist`).
`ForgeBoot.init()` schreibt bei jedem Start `bridge/assets/forge.profile.properties` (generiert, git-ignoriert) und lenkt
Forges Nutzerdaten damit nach `~/.mtg-player/`; `bridge/assets/res` ist ein Symlink auf `forge/forge-gui/res`.
Der Assets-Pfad ist mit `-Dmtgplayer.assets=<dir>` überschreibbar; Maven setzt ihn für `test` und `exec:java` automatisch.

## Was noch fehlt (M5+)

Spiel-Log im Browser, Spieler-Markierung als Ziel, Archidekt-URL (M6).
