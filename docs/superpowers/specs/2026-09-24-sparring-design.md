# Stück 3 – Sparring – Design

Stand: 2026-09-24. Kevins Vorgaben: Gegner **zufällig aus demselben Bracket** („so werden auch seltene Matchups mal
getestet"), Format **1vs1** (Pod später), Lauf **im Hintergrund**.

Zweck: Das Statistik-Board ist gebaut, aber Kevin hat 5 Partien und **keine einzige** mit den Vorfall-Kennzahlen aus
Runde B. Sparring füllt das in Minuten statt Abenden – und prüft die neuen Kennzahlen gegen echte Partien, bevor
Stück 2 Kartenvorschläge darauf stützt.

## 1. Bracket je Deck

Archidekt liefert `edhBracket` (1–5) je Deck; Kevins Decks stehen auf 3 (geprüft über die API).

- `DeckStore`: drittes Tag `bracket:<1-5>`; `Archidekt.Result` trägt den Bracket, Import und Resync schreiben ihn.
- `Messages.DeckInfo` bekommt `bracket: Integer` (null = unbekannt).
- Manuell setzbar: `{ "type": "setDeckBracket", "name": "…", "bracket": 1-5|null }` → schreibt das Tag und
  antwortet mit `lobby`. Im Deck-Panel („Eigene Decks") zeigt jede Kachel den Bracket als kleine Marke; ein Klick
  darauf öffnet eine Auswahl 1–5 / „unbekannt".
- Precons haben keinen Bracket (null) und gelten für die Gegnerwahl als „unbekannt".

## 2. Gegnerwahl

Für ein Sparring mit Deck D:
1. Kandidaten = alle **gespeicherten** Decks außer D mit demselben Bracket wie D.
2. Sind es weniger als 3, wird auf Bracket ±1 erweitert; sind es dann immer noch weniger als 1, bricht der Lauf mit
   einer Fehlermeldung ab („keine Gegner im Bracket <n>: Bracket setzen oder Decks importieren").
3. Je Partie wird ein Gegner **zufällig** gezogen (gleichverteilt, mit Zurücklegen), Startspieler zufällig.
   Der Lauf protokolliert je Partie den Gegner – so entstehen über viele Partien auch seltene Paarungen.
Hat D keinen Bracket, zählen alle Decks ohne Bracket als Kandidaten (und die Meldung sagt das).

## 3. Ablauf

`{ "type": "sparringStart", "deck": "…", "games": 5|10|20|50, "ai": {"mode":"standard","profile":"Default"},
   "timeout": 5, "maxTurns": 60 }`

- Ein Lauf gleichzeitig; ein zweiter Start → `error` „Sparring läuft noch".
- Je Partie ein **Kindprozess** (wie der Bench, `SubprocessRunner`-Muster): `Main --sparring-one <json>` spielt
  genau eine Partie und schreibt `SPARRING_RESULT <json MatchRecord>` auf stdout. Grund: ein Forge-Absturz darf
  Kevins laufende Bridge nicht mitnehmen, er spielt ja nebenher.
- Der Elternprozess legt jeden gelieferten `MatchRecord` (Quelle `"sparring"`) in den `MatchStore` und schickt
  danach `matches`. Stürzt ein Kind ab oder liefert nichts, wird die Partie gezählt, als Fehler vermerkt und der
  Lauf läuft weiter.
- Fortschritt: `{ "type": "sparringProgress", "done": n, "total": m, "current": "<Gegner>", "errors": [ … ],
  "running": true|false }` nach jedem Spielende und einmal zu Beginn.
- Abbruch: `{ "type": "sparringCancel" }` – laufendes Kind wird beendet, der Lauf endet mit `running: false`.
- Zugdeckel: Partien am Deckel zählen wie gehabt als „Zugdeckel" und fließen nicht in die Bilanz (Runde „Zugdeckel").

KI: **immer Standard** (schnell, konsistent) – gemessen wird das Deck, nicht die KI-Stärke. Der ursprünglich
vorgesehene Schalter auf Simulation (mit Laufzeit-Warnung: in einer 4er-Runde 15+ Minuten je Partie, siehe
Messung vom 23.09.) ist **bewusst nicht gebaut** worden und steht unter „Nicht in diesem Stück"; das Protokoll
nimmt `ai` weiterhin entgegen, die Oberfläche lässt das Feld weg und bekommt damit `AiConfig.DEFAULT`
(Standard / Profil „Default").

## 4. Oberfläche

Im Statistik-Board über den Kacheln, neben dem gewählten Deck: Knopf **„Sparring starten"** mit Auswahl der
Partienzahl (5/10/20/50) und einem Hinweis auf die Gegner („zufällig aus Bracket 3: 7 Decks"). Läuft ein Lauf,
steht dort stattdessen eine Fortschrittszeile „7/20 · gegen Koma, World-Eater …" mit „Abbrechen"; Fehler erscheinen
darunter rot und mehrzeilig. Nach dem Lauf aktualisiert sich das Board von selbst (die `matches`-Nachricht kommt
ohnehin nach jeder Partie).

Die Lobby bekommt keinen zweiten Einstieg – Sparring gehört zur Statistik.

## 5. Nachweise

- Bridge: `SparringOpponentsTest` (Kandidatenwahl: gleicher Bracket, Erweiterung auf ±1, Abbruch ohne Kandidaten,
  Deck ohne Bracket), `DeckStoreTest` (Bracket-Tag schreiben/lesen, `setDeckBracket`), `ArchidektTest`
  (`edhBracket` landet im Ergebnis), `SparringRunTest` (zwei Partien mit einem gefälschten Runner: beide
  Datensätze landen im Store, Fortschritt kommt in der richtigen Reihenfolge, Abbruch beendet den Lauf),
  `BridgeEndToEndTest` (`sparringStart` ohne Gegner → `error`; zweiter Start → „läuft noch").
- Web: `store.test.ts` (Fortschritts-Reducer), Screenshot des Boards mit laufendem Sparring und nach dem Lauf.
- Ein echter Probelauf von 5 Partien 1vs1 mit einem von Kevins Decks; der Bericht nennt Dauer je Partie,
  Fehlerzahl und prüft die neuen Kennzahlen auf Plausibilität (insbesondere `sweepsSuffered` – der Review von
  Runde A verlangt genau diese Gegenprobe).

## Nicht in diesem Stück

Pod-Sparring (3–4 Sitze), Gauntlet aus festen Gegnern, paralleles Rechnen mehrerer Partien, Sparring aus der Lobby.

Dazu die **Wahl des KI-Modus** aus Abschnitt 3: das Sparring läuft immer mit der Standard-KI. Ein Schalter auf
Simulation würde einen Lauf über 20 Partien von Minuten auf Stunden ziehen, ohne die Frage zu beantworten, die
das Sparring stellt (taugt das Deck?) – er misst die KI mit. Kommt später, wenn es dafür einen Anlass gibt.
