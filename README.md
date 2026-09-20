# MTG-Player

Lokaler Commander-Tisch mit KI-Gegnern auf Basis von Forge, mit eigener Browser-UI.
Design: `docs/superpowers/specs/2026-09-16-mtg-player-design.md`.

## Voraussetzungen

- Java 17, Maven ≥ 3.8.1 (`sudo apt install openjdk-17-jdk-headless maven`)
- Node ≥ 20 (für das Frontend, ab M2)

## Einmalig: Forge bauen

```bash
git submodule update --init --depth 1   # Fork Haste-MC/forge, Branch mtg-player (Basis: Tag forge-2.0.14)
cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true   # installiert 2.0.14-mtgplayer nach ~/.m2
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

Archidekt: Deck-URL (oder nur die ID) einfügen – öffentliche Decks; der Deckname wird übernommen.

Kartenbilder kommen von Scryfall und werden unter `~/.mtg-player/cache/images/` gecacht (erstes Spiel mit
neuen Karten lädt ein paar Sekunden nach; ohne Internet bleiben es Textboxen).

Phasenleiste über dem Prompt: Klick auf eine Phase setzt/entfernt einen Stop – dort wird angehalten, sofern du
etwas spielen kannst; ohne Volle Kontrolle passt Forge weiterhin automatisch, wenn nichts spielbar ist
(getrennt für eigene und gegnerische Züge, je nachdem wessen Zug gerade ist). "Volle Kontrolle" hält in
jeder Phase an und schaltet das automatische Passen ab. Tutor-Effekte (Bibliothek durchsuchen) öffnen einen Listen-Dialog mit Kartendetails.
Veraltete Klicks (Prompt hat inzwischen gewechselt) werden von der Bridge ignoriert.

Entwicklung am Frontend: `cd web && npm run dev` (Vite auf :5173, verbindet sich mit der Bridge auf :8081).
`npm run shot -- http://127.0.0.1:8080 out.png fixtures/table.json` erzeugt einen Playwright-Screenshot (Fixture-Zustand, `?debug=1` wird automatisch angehängt).

Log unten rechts: Kategorie-Chips blenden Zeilen ein/aus (Mana und Phase sind standardmäßig ausgeblendet).
Spieler, die gerade als Ziel wählbar sind, bekommen einen gestrichelten Rahmen um den Kopfbereich.

Gleiche Länder/Token liegen als Stapel (bis zu vier sichtbare Ebenen, ×N); getappte Karten des Stapels
liegen gedreht darunter. Klick tappt die erste ungetappte. Sobald eine Karte des Stapels wählbar, im Kampf
oder mit Marken ist, werden alle einzeln gezeigt. Getappte Karten drehen sich in der eigenen Zone und in den
Zuschauer-Panels; nur die kompakten Gegnerzeilen der Tischansicht zeigen sie abgedunkelt mit ⟳. Forges
Effekt-Hilfskarten und Embleme erscheinen als Chips in der Zeile "Effekte"
(Gegner: im Panelkopf), Hover zeigt den Text im Detail-Panel rechts.
Zuschauer-Panels und eigene Zone: Kreaturen oben, übrige bleibende Karten in der Mitte, Länder unten; die
Kartengröße passt sich der Panelgröße an.
Alternative Bridge-Ports lassen sich per URL setzen: `?wsPort=8082` (gleicher Host) oder `?ws=ws://host:port` (eigene WebSocket-URL).

## Bridge

```bash
cd bridge
mvn -q test                                # alle Tests inkl. KI-Spiel und End-to-End über WebSocket (Minuten)
mvn -q compile exec:java                   # Bridge-Server (WebSocket 8081, HTTP 8080)
mvn -q compile exec:java -Dexec.args="--ai-demo 42"   # headless KI-Spiel wie in M1
mvn -q compile exec:java -Dexec.args="--bench"         # N Spiele KI gegen KI, siehe Abschnitt "Bench"
```

Ports: `-Dmtgplayer.wsPort=…`, `-Dmtgplayer.httpPort=…`; Bind-Adresse `-Dmtgplayer.bind=…` (Standard `0.0.0.0`, damit Windows unter WSL2 per localhost rankommt); Frontend-Verzeichnis: `-Dmtgplayer.web=…` (Standard `../web/dist`).
`ForgeBoot.init()` schreibt bei jedem Start `bridge/assets/forge.profile.properties` (generiert, git-ignoriert) und lenkt
Forges Nutzerdaten damit nach `~/.mtg-player/`; `bridge/assets/res` ist ein Symlink auf `forge/forge-gui/res`.
Der Assets-Pfad ist mit `-Dmtgplayer.assets=<dir>` überschreibbar; Maven setzt ihn für `test` und `exec:java` automatisch.

## Wenn der Tisch einfriert

Friert ein Spiel ein (kein Prompt mehr, Log steht), ist meist der Spiel-Thread mit einer Ausnahme abgebrochen.
Die Bridge schreibt jeden solchen Abbruch mit Stacktrace nach `~/.mtg-player/logs/bridge.log` und als rote
Zeile ins Browser-Log („Spiel abgebrochen …"). Für einen Bugreport reicht der letzte Block aus der Datei.

## Forge-Fork

Seit dem KI-Paket Stufe 2 läuft die Bridge gegen einen Fork von Forge (`Haste-MC/forge`, Branch `mtg-player`,
Maven-Version `2.0.14-mtgplayer`, damit der Fork-Build das Original in `~/.m2` nicht überschreibt). Im Submodule
ist `origin` der Fork und `upstream` Card-Forge; neue Forge-Versionen kommen per `git fetch upstream && git merge
forge-<version>` auf den Branch. Nach jeder Änderung an Forge: obiges `mvn install` erneut, dann Bridge neu bauen.
Was der Fork ändert, steht in `docs/forge-fork.md` – aktuell: Zeitbudget für die Voll-Simulation (die
KI-Bedenkzeit gilt damit für alle KI-Modi), `GameCopier` robust gegen Monarch-Effektkarte, den
`<Nothing>`-Kampfplatzhalter und außerhalb des Spielfelds erinnerte Token/Effektkarten, sowie das Debug-Flag
`-Dforge.ai.sim.debug` für die Top-Level-Entscheidungen des Pickers.

## Bench (KI gegen KI)

Stufe 3: Kandidaten unter Budget sortiert → 70 % [55–82] im Spiegel; 30 s Budget bringt nichts (59 %):
[docs/bench/2026-09-20-stufe-3-kandidaten.md](docs/bench/2026-09-20-stufe-3-kandidaten.md).

Erster Messlauf und Einordnung: [docs/bench/2026-09-20-sim-vs-standard.md](docs/bench/2026-09-20-sim-vs-standard.md)
(Simulations-KI gewinnt das Spiegel-Match zu ~76 %, aber 28 % der Spiele enden in Timeout/Absturz). Stufe-2-Nachweise
zu den beiden Fixes: [docs/bench/2026-09-20-stufe-2-zeitbudget.md](docs/bench/2026-09-20-stufe-2-zeitbudget.md)
(0 Timeouts statt 9) und [docs/bench/2026-09-20-stufe-2-copier.md](docs/bench/2026-09-20-stufe-2-copier.md)
(0 Abstürze an Monarch/`<Nothing>`; Siegquote gegenüber dem Ausgangslauf statistisch unverändert, andere
Grundgesamtheit).

Misst, ob eine KI-Einstellung gegen eine andere gewinnt: N Spiele 1-gegen-1, headless, mit Seed. Ohne Zeitbudget
(`--timeout 0`) ist ein Sim-Sitz je Spiel-Index reproduzierbar wie ein Standard-Sitz; mit Zeitbudget hängt die
Deadline jeder Simulations-Entscheidung an der Wanduhr, nicht am Seed – ein Wiederholungslauf auf einer anderen
Maschine oder unter Last kann andere Kandidaten abbrechen und damit einen anderen Zug wählen. Bench-Läufe mit
Zeitbudget sind ab einer gewissen Boardkomplexität als Verteilung (mehrere Seeds, Konfidenzintervall) zu lesen,
nicht als wiederholbares Einzelspiel-Ergebnis.

```bash
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1"
```

Precon-Namen mit Leerzeichen müssen in `-Dexec.args` in Anführungszeichen stehen (Maven trennt sonst am Leerzeichen).

| Option | Bedeutung | Standard |
|---|---|---|
| `--games N` | Zahl der Spiele | 40 |
| `--a spec` / `--b spec` | KI-Sitze, `AiConfig.parse`, z. B. `sim:Reckless`, `std`, `hybrid:Cautious` | `sim:Default` / `std:Default` |
| `--deck-a ref` / `--deck-b ref` | Deck: `precon:<Name>` oder `saved:<Name>` | erste zwei Precons alphabetisch |
| `--turns N` | Zugdeckel (Spielerzüge) → Unentschieden | 200 |
| `--timeout s` | KI-Bedenkzeit in Sekunden; Richtwert je Entscheidung, kann bis etwa das Doppelte überschreiten (gilt auch für die Simulation) | 5 |
| `--seed n` | Basis-Seed; Spiel i nutzt `seed + i` | aktuelle Zeit |
| `--out dir` | Ausgabeverzeichnis | `~/.mtg-player/bench/` |
| `--game-timeout min` | Zeitlimit je Spiel im Kindprozess (danach `destroyForcibly`, Spiel zählt als Absturz) | 30 |
| `--in-process` | jedes Spiel im aufrufenden Thread statt in einem eigenen JVM-Kindprozess (Flag, kein Wert) | aus |

Ausgabe: `<out>/<yyyy-MM-dd-HHmmss>-<a>-vs-<b>.md` (Tabelle, Siegquote mit 95-%-Wilson-Intervall, Parameter, Seed,
eine Zeile je Spiel) und die gleichnamige `.json` mit allen Einzelspielen. Nach jedem Spiel eine Fortschrittszeile
auf stdout. Läuft Minuten bis Stunden; Ctrl-C schreibt den Zwischenstand.

**Ein JVM-Kindprozess je Spiel.** Ein Forge-eigener Absturz während der Simulation (z. B. `GameCopier`
"Couldn't map \<Nothing\>", ausgelöst wenn `Combat.removeFromCombat` beim Verlassen eines angegriffenen
Planeswalkers/einer Battle eine dem Kopierer unbekannte Platzhalterkarte anlegt) vergiftet dabei nicht nur
das eine Spiel, sondern unbekannten globalen Zustand für den Rest der JVM – Folgespiele stürzen danach
reihenweise ab oder "enden" nach Sekunden ohne echten Zug. Deshalb läuft standardmäßig jedes Bench-Spiel in
einem frischen `java`-Kindprozess (`mtgplayer.Main --bench-one <i> <dieselben Bench-Optionen>`, mit
frischem Heap als Nebeneffekt): stdout des Kindprozesses liefert die Zeile `BENCH_RESULT <json>`, stderr
geht nach `<out>/game-<i>.log`. Kein Ergebnis, Exit ≠ 0 oder Ablauf von `--game-timeout` zählen als Absturz
nur dieses einen Spiels, nicht als Abbruch des ganzen Laufs; Ctrl-C beendet einen noch laufenden
Kindprozess mit. `--in-process` schaltet zurück auf das alte Verhalten (ein Thread, keine Isolation) – für
schnelle lokale Tests, wenn ein Forge-Absturz kein Risiko ist (z. B. ein einzelnes Spiel oder Decks, die
bekanntermaßen stabil laufen).

`sim` (`USE_FULL_SIMULATION`) simuliert Angriffe/Blocks/Ziele voraus und ist entsprechend rechenintensiv;
der Speicherverbrauch ist inzwischen unkritisch (`AiConfig.newLobbyPlayer` leert Forges `AiCache` nach jeder
Simulationskopie, siehe `.superpowers/sdd/sim-oom-investigation.md` – ohne den Fix wuchs der Heap pro
Entscheidung unbegrenzt; gilt auch für menschliche Spiele mit Sim-KI, nicht nur für den Bench). `--timeout`
gilt auch für die Simulation: der Fork gibt jeder Zauberwahl-Entscheidung ein Zeitbudget von `--timeout`
Sekunden (`SimulationController`-Deadline, siehe `docs/forge-fork.md`) – nach Ablauf werden keine weiteren
Kandidaten, Ziele, Modi oder tieferen Ebenen mehr bewertet, mindestens ein Kandidat wird aber immer
durchgerechnet und das Ergebnis ist der beste bis dahin gefundene Zug. Ohne Budget (Upstream-Forge) hing
eine einzelne Entscheidung im Bench über 30 Minuten. `hybrid` (`USE_HYBRID_SIMULATION`, nur Zauberauswahl
simuliert) ist deutlich schneller. KI-Sitze spielen inzwischen das Profil `Default` (Datei `res/ai/Default.ai`) statt
Forges eingebauter Standard-Heuristiken – `AiConfig.newLobbyPlayer` ruft immer `setAiProfile`.

**Sim-Entscheidungen nachlesen:** `-Dforge.ai.sim.debug=true` (Fork-Flag) lässt den Picker jede Top-Level-
Entscheidung auf stdout schreiben – Phase, Hand, jeden bewerteten Kandidaten mit Wert, gewählten Zug und Plan.
Beim `--bench` gibt der Elternprozess das Flag an die Kindprozesse weiter (Ausgabe in `<out>/game-<i>.out.log`);
ein einzelnes Spiel spielt man direkt nach: `java -Dforge.ai.sim.debug=true -cp … mtgplayer.Main --bench-one <i>
<Bench-Optionen>` (Seed = `--seed` + i, Sitzreihenfolge wie im Lauf). Befund zur vermeintlichen Nichtstun-Schwäche:
[docs/bench/2026-09-20-stufe-2-nichtstun.md](docs/bench/2026-09-20-stufe-2-nichtstun.md).

## Offen

Gleiche Länder im Deckbau stapeln, 5–6-Spieler-Raster im Zuschauer-Modus nur per CSS vorbereitet (kein Fixture),
sehr volle Zuschauer-Boards bei 1280×720 werden klein (Karten bis 42 px), Moxfield bewusst nicht.
