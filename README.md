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

Browser: <http://127.0.0.1:8080>. Lobby → je Sitz eine Deck-Kachel (Commander-Bild) anklicken öffnet ein
Panel mit den Reitern „Precons" / „Eigene Decks" / „Import" und einer Suche (Deck- oder Commander-Name);
1–5 KI-Gegner, dazu optional eine Serie (Best of 3/5/7, sonst aus) → Spiel starten. Die letzte Wahl je Sitz
bleibt im Browser gemerkt (auch nach „Zur Lobby").
Steuerung: leuchtende Karten sind klickbar, Rechtsklick = andere Fähigkeit, Enter/Leertaste = OK,
Esc = Abbrechen. Forge passt automatisch, wenn du nichts tun kannst (Arena-Stil).

Eigene Decks: im Panel unter „Import" einen Archidekt- oder Arena-Export einfügen (`1 Sol Ring (c21) 263`,
Sektion `Commander` oder erste legendäre Kreatur als Commander). Das Deck wird unter `~/.mtg-player/decks/`
gespeichert und erscheint danach unter „Eigene Decks". Unbekannte Karten werden mit Zeile gemeldet, das
Spiel startet dann nicht.

Archidekt: Deck-URL (oder nur die ID) einfügen – öffentliche Decks; der Deckname wird übernommen. Die
Archidekt-Id steht als Tag (`archidekt:<id>`) in der gespeicherten `.dck`-Datei; die Deck-Kachel unter
„Eigene Decks" bekommt dadurch einen „↻ Resync"-Knopf, der das Deck neu von Archidekt lädt (z. B. nach
Änderungen an der Liste dort), ohne dass du erneut importieren musst.

Archidekt-Konto: der Reiter „Archidekt" im Deck-Panel holt mit dem Benutzernamen (Enter oder „Decks laden",
der Name bleibt im Browser gemerkt) die Liste der öffentlichen Commander-Decks des Kontos – nur öffentliche
und ungelistete Decks, private sieht Archidekt ohne Login nicht. Jede Kachel trägt eine Marke: **neu** (noch
nicht importiert), **aktuell** (der gespeicherte Stand entspricht Archidekt) oder **geändert** (auf Archidekt
seither bearbeitet); die geänderten sind vorab angehakt. „Ausgewählte holen (n)" importiert bzw. aktualisiert
die angehakten Decks nacheinander (neue mit ihrem Archidekt-Namen, bekannte unter dem gespeicherten Namen),
„Alle aktualisieren" nimmt alle schon importierten Decks des Kontos. Während des Laufs zeigt die Fußzeile
„3/7 · Deckname …", danach „7/7 fertig" und rot die Decks, die nicht geladen werden konnten – der Lauf geht
bei Fehlern weiter. Dafür stehen in der `.dck`-Datei zwei Tags: `archidekt:<id>` und
`archidekt-updated:<Stand>` (Archidekts `updatedAt` beim Import). Decks, die vor dieser Funktion importiert
wurden (nur Tag `archidekt:`), erscheinen einmal als **geändert** und sind vorab angehakt; nach dem ersten
Aktualisieren tragen sie den Stand. Auch ein einfacher „↻ Resync" unter „Eigene Decks" schreibt jetzt
`archidekt-updated`. Heißt ein lokales Deck ohne Archidekt-Tag (z. B. ein früherer Textimport) genauso wie
ein neu zu importierendes Archidekt-Deck, zeigt die Kachel die Marke **übernehmen**: der Import ersetzt den
Inhalt dieses Decks und versieht es mit den Tags – nicht vorab angehakt, auch nicht bei „Alle
aktualisieren". Trägt das gleichnamige Deck dagegen das Tag eines anderen Archidekt-Decks, speichert der
Import unter „<Name> (<Id>)" – überschrieben wird nichts.

Löschen: jede Kachel unter „Eigene Decks" hat einen Papierkorb-Knopf; der erste Klick wird zu „Wirklich löschen?"
(nach 4 s oder einem Klick daneben wieder weg), der zweite löscht die Datei. Nur eigene Decks, keine Precons.
War das Deck in einem Sitz gewählt, ist der Sitz danach leer.

Spielende: Der Dialog bietet neben „Zur Lobby" auch „Nochmal spielen" – startet dieselbe Deck-/KI-
Konstellation direkt neu, ohne den Umweg über die Lobby. Läuft eine Serie (Lobby-Einstellung „Serie"), zeigt
der Dialog den Stand (z. B. „Du 2 · KI 1 1") und der Knopf wird zu „Neue Serie", sobald jemand die nötigen
Siege hat.

Kartenbilder kommen von Scryfall und werden unter `~/.mtg-player/cache/images/` gecacht (erstes Spiel mit
neuen Karten lädt ein paar Sekunden nach; ohne Internet bleiben es Textboxen). Spielsteine holen ihr Bild aus
Scryfalls Token-Set der Edition (z. B. `tc21`); Token ohne Editionseintrag bleiben Textboxen.

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
Angelegte Auren/Equipment liegen hinter ihrem Wirt und schauen oben heraus (in der Gegnerzeile seitlich); jede
Kante ist hover-/klickbar. Flüche auf Spielern stehen als „Aura"-Chip im Panelkopf des verzauberten Spielers.
Die Grab-/Exil-Liste öffnet zur Bildmitte hin.
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
`mvn test` schreibt Log, `matches.json`, Bench-Ausgaben und Forge-Profil aller Tests nach `bridge/target/test-data`
statt nach `~/.mtg-player/`; `-Dmtgplayer.data=<dir>` lenkt diese Ablage auch für eigene Läufe (z. B. `--bench`) um.

Protokoll (WebSocket, JSON, Feld `type`, Details in `docs/superpowers/specs/2026-09-16-mtg-player-design.md`):
die `lobby`-Nachricht listet Precons und eigene Decks als `DeckInfo` (Name, Commander mit Bild, bei
Archidekt-Importen die `archidekt`-Id); die Client-Nachricht `resyncDeck` (Deckname) lädt ein solches Deck
neu von Archidekt und die Bridge antwortet mit einer frischen `lobby`- oder mit einer `error`-Nachricht;
`deleteDeck` (Deckname) löscht ein eigenes Deck (nie ein Precon) und antwortet ebenso mit `lobby` oder `error`.
`archidektList` (Benutzername) liefert `archidektDecks` (Id, Name, `updatedAt`, Vorschaubild je öffentlichem
Commander-Deck des Kontos) oder `error`; `archidektImport` (Ids) importiert/aktualisiert die Decks nacheinander
und meldet je Deck `archidektProgress` (`done`, `total`, `current` = gespeicherter Name des laufenden Decks bzw.
`Deck <id>` bei einem Neuimport – der Client zeigt dafür den Namen aus der Konto-Liste, `errors`), nach
jedem Deck eine frische `lobby`-Nachricht und zum Schluss `archidektProgress` mit `current: null`; ein zweiter
`archidektImport` während eines Laufs wird mit `error` abgewiesen.
`thinking` (`player` = Sitz mit Priorität oder `null`, `seconds`) meldet ab 3 s ohne sichtbaren Fortschritt
je Sekunde, dass gerechnet wird; `seconds: 0` löscht die Anzeige wieder (kommt einmal, sobald es weitergeht
oder das Spiel endet). Wartet die Bridge auf eine Eingabe **von dir**, schweigt sie – die eigene Bedenkzeit
ist kein Rechnen.

## Statistik

Jede beendete Partie (eigenes Spiel und Zuschauer-Modus, später auch das Sparring) wird mitgeschrieben: je Sitz
Deckname, Sieger bzw. Verlustgrund, Mulligans, Länder je Zug und verpasste Landabgaben, Zauber und Mana-Summe,
Commander-Casts samt Steuer und Zug des ersten Commanders, Schaden gemacht/genommen sowie Leben und Gift am
Ende; dazu Dauer, Züge und Quelle der Partie. Die Datensätze liegen als JSON-Array in
`~/.mtg-player/matches.json` (neueste zuletzt, Deckel 2000 Partien, atomar geschrieben); eine kaputte Datei
wird beim nächsten Schreiben ersetzt, statt den Start zu verhindern.

Nicht jede Partie soll in die Bewertung fließen. Automatisch **nicht gewertet** werden Partien unter drei Zügen
(„zu kurz"), Partien mit einer Aufgabe („aufgegeben"), am Zugdeckel abgeschnittene Partien („Zugdeckel"),
abgebrochene Partien („abgebrochen": die Partie wurde
über „Aufgeben"/„Beenden" oder einen Neustart abgebrochen) und abgestürzte Partien („Absturz") – sie stehen mit
dem Grund in der Liste, zählen aber nicht in den Kennzahlen. Bei „abgebrochen" und „Absturz" gilt kein Sitz als
Sieger, ein nachträgliches „gewertet" kann daraus also keine Siege erfinden.

„Zugdeckel" ist die Notbremse der headless Partien (Bench, Sparring): nach der maximalen Zugzahl setzt die Bridge
alle Sitze auf ein vereinbartes Remis und beendet das Spiel, sonst liefe eine festgefahrene KI-Partie endlos.
Technisch ist das ein Remis, fachlich hat sich aber niemand darauf geeinigt – die Partie wurde abgeschnitten,
deshalb zählt sie nicht. Der echte Ausgang (Remis, kein Sieger) bleibt trotzdem im Datensatz stehen. Wie oft ein
Deck dort hineinläuft, ist selbst eine Kennzahl und steht als eigene Kachel „Partien am Zugdeckel" (Anteil an den
gewerteten plus den Zugdeckel-Partien dieses Decks, darunter „n von m"): ein Deck, das regelmäßig in den Deckel läuft, hat keine
verlässliche Siegbedingung. Der Knopf „Statistik" in der Lobby öffnet den Screen:
links die Decks mit ihrer Partienzahl, rechts die Kennzahlen des gewählten Decks (Bilanz und Siegquote mit
95-%-Wilson-Intervall, Ø Züge und Dauer, Mulligans, Länder bis zum eigenen Zug 3/5 – nur über Partien, die so
lange liefen, sonst „–" –, verpasste Landabgaben, Zauber und Mana,
Commander-Zug und -Steuer, Schaden, Todesursachen, Gegner-Tabelle), darunter alle Partien. Je Zeile setzt die
Checkbox „gewertet" eine Partie wieder hinein oder heraus, der Papierkorb löscht sie (zwei Klicks, wie beim
Deck-Löschen); der Filter „nur gewertete" steht standardmäßig an. Ausgewertet wird **deckbezogen**: wer das Deck
gespielt hat – du oder eine KI – spielt keine Rolle, damit die kommenden Sparring-Partien (KI spielt dein Deck)
mitzählen; je Partie zählt höchstens ein Sitz je Deck, ein Spiegel bleibt also eine Partie.

Je Partie hält der Datensatz auch die eingestellte KI-Bedenkzeit (`aiTimeout`, Sekunden je Entscheidung); in
der Partienliste steht sie als kleines Feld „<n> s" neben der Quelle. Sie gehört zum Vergleich von Dauern dazu –
eine lange Partie kann an der hohen Bedenkzeit liegen und nicht am Deck. Ältere Datensätze kennen den Wert
nicht, dort fehlt das Feld.

Protokoll: die Bridge schickt bei Verbindung, nach jeder Änderung und nach jedem Spielende
`matches` (die ganze Liste); der Client schickt `deleteMatch` (`id`) und `setMatchCounted` (`id`, `counted`),
Fehler kommen als `error` („Partie <id>: …") und stehen im Screen über der Liste.

Als Nächstes: Sparring (die KI spielt dein Deck gegen sich selbst, füllt die Statistik schnell) und daraus eine
Schwächen-Analyse mit Kartenvorschlägen.

## Wenn der Tisch einfriert

Friert ein Spiel ein (kein Prompt mehr, Log steht), ist meist der Spiel-Thread mit einer Ausnahme abgebrochen.
Die Bridge schreibt jeden solchen Abbruch mit Stacktrace nach `~/.mtg-player/logs/bridge.log` und als rote
Zeile ins Browser-Log („Spiel abgebrochen …"). Für einen Bugreport reicht der letzte Block aus der Datei.

Steht dort nichts, rechnet vermutlich nur die KI. Ein Sitz im Simulations-Modus braucht je Entscheidung bis zur
vollen Bedenkzeit, und gemessen kosten **vier Simulations-Sitze 20–31 s je Entscheidung** – in dieser Zeit
erreicht den Browser nichts. Deshalb steht ab 3 s Stille „… denkt" in der Prompt-Leiste (Zuschauer-Modus: in
der Fußzeile); solange die Sekunden dort hochlaufen, ist alles in Ordnung.

Bleibt der Tisch trotzdem stehen, hilft der Wachhund: nach 120 s Stille legt die Bridge **einmal je Vorfall**
den Stacktrace des Spiel-Threads im Log ab (nur dort, nicht im Browser):

```bash
grep -A40 Wachhund ~/.mtg-player/logs/bridge.log | tail -60
```

Der Stack sagt, was los ist:

* `GameSimulator` / `GameCopier` (auch `SpellAbilityPicker`, `AiController`) – die KI rechnet. Kein Fehler,
  höchstens ein Grund, die Bedenkzeit zu senken oder weniger Simulations-Sitze zu setzen.
* `ChoiceBroker` / `CompletableFuture.get` – **echter Hänger**: der Spiel-Thread wartet auf eine Antwort, die
  nie kommt (verlorene Frage, abgerissene Verbindung). Das gehört in den Bugreport.

Während die Bridge auf *deine* Eingabe wartet, ruhen Anzeige und Wachhund – sonst stünde nach zwei Minuten
Nachdenken ein `ChoiceBroker`-Stack im Log, also genau die Signatur des echten Hängers.

## Forge-Fork

Seit dem KI-Paket Stufe 2 läuft die Bridge gegen einen Fork von Forge (`Haste-MC/forge`, Branch `mtg-player`,
Maven-Version `2.0.14-mtgplayer`, damit der Fork-Build das Original in `~/.m2` nicht überschreibt). Im Submodule
ist `origin` der Fork und `upstream` Card-Forge; neue Forge-Versionen kommen per `git fetch upstream && git merge
forge-<version>` auf den Branch. Nach jeder Änderung an Forge: obiges `mvn install` erneut, dann Bridge neu bauen.
Was der Fork ändert, steht kommitweise in `docs/forge-fork.md`: Zeitbudget für die Voll-Simulation (die
KI-Bedenkzeit gilt für alle KI-Modi), `GameCopier` robust gegen Monarch-Effektkarte, `<Nothing>`-Kampfplatzhalter,
erinnerte Token, ausgeschiedene Spieler, gemeldete Karten und Goad; Kandidaten nach Nützlichkeit sortiert; die KI
arbeitet auf eigene Meld-Bedingungen hin und lehnt Goad-Marken ab, die in einen verlorenen Pflichtangriff führen;
Mulligan mit Farb-/Kurvenprüfung (`MULLIGAN_CHECK_COLORS`, Vergleichsprofil `Legacy` = altes Verhalten); Landgewicht
in der Sim-Bewertung; Debug-Flag `-Dforge.ai.sim.debug`.

## Bench (KI gegen KI)

Stufe 4: Mulligan prüft Farben und Kurve (Fork-Property `MULLIGAN_CHECK_COLORS`, Vergleichsprofil `Legacy`) →
Abzan-Spiegel 28:12, Ahoy 24:16 gegen das alte Verhalten, davon nach Kontrolle mit gleichen Seeds ~5–8 Punkte
Effekt: [docs/bench/2026-09-20-stufe-4-mulligan.md](docs/bench/2026-09-20-stufe-4-mulligan.md).

Stufe 4: Landgewicht in der Bewertung → Ahoy-Spiegel 30:10 = 75 % [60–86] (vorher 28:12); die anderen drei
Bewertungs-Schritte waren neutral und sind zurückgenommen: [docs/bench/2026-09-20-stufe-4-bewertung.md](docs/bench/2026-09-20-stufe-4-bewertung.md).

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
eine Zeile je Spiel, Spalte „Nichtstun" = Sitze mit ≥ 5 gespielten Ländern und ≤ 2 gewirkten Zaubern, aus den
Forge-Logzeilen `<Sitz> played …`/`<Sitz> cast …` gezählt; Summenzeile `Nichtstun A x / B y`) und die gleichnamige
`.json` mit allen Einzelspielen (`fewSpells` je Spiel, `fewSpellsA/B` in der Summary). Nach jedem Spiel eine Fortschrittszeile
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
