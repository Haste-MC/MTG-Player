# Statistik Stück 1 – Partien erfassen, speichern, auswerten – Design

Stand: 2026-09-22. Kevins Auftrag: „Statistiken der Decks und vorgeschlagene Verbesserungen zu Anfälligkeiten bei
Matches"; Beschluss: Variante A (Daten zuerst, danach Sparring, dann Schwächen-Analyse). Zusatz: „Sparring und
echte Matches von oder gegen KI sollten zählen. Aber es sollte im Nachhinein auch löschbar sein, so dass ein Crash
oder kurz schauen dann aufgeben nicht in die Bewertung fließt."

Dieses Stück liefert die Datengrundlage und den Statistik-Screen. Sparring (Stück 3) und Schwächen/Vorschläge
(Stück 2) bekommen eigene Specs.

## 1. Erfassung (`mtgplayer.stats.MatchRecorder`)

Ein Guava-`@Subscribe`-Empfänger an Forges `Game` (wie in `AiMatch` bereits für `GameEventTurnEnded` genutzt),
registriert von `HumanMatch.start`/`startSpectator` (und später von `AiMatch`). Gezählt wird je Sitz über typisierte
Events statt Log-Text:

| Event | Zählung |
|---|---|
| `GameEventTurnBegan(turnOwner, turnNumber)` | aktueller Zug; Landabgabe des Vorzugs abschließen |
| `GameEventLandPlayed(player, land)` | Länder je Zug (`landsByTurn`), Gesamtzahl |
| `GameEventMulligan(player)` | Mulligans |
| `GameEventSpellAbilityCast(sa, …)` | Zauber (`sa.isSpell()`), Mana-Summe (`sa.getHostCard().getCMC()`), Commander-Casts (`getHostCard().isCommander()`) |
| `GameEventPlayerDamaged(target, source, amount, combat, infect)` | Schaden genommen (Kampf/Nicht-Kampf) und – über `source.getController()` – Schaden gemacht |
| `GameEventGameFinished` | Abschluss: Endstand je Spieler aus `Game.getPlayers()`/`getLostPlayers()` |

Am Ende liest der Recorder aus dem Spielzustand nach: Leben, Gift, Commander-Steuer
(`Player.getCommanderCast(commander)`, Steuer = 2 · (Casts − 1) je Commander), Zug des Ausscheidens (aus dem
`TurnBegan`-Zähler beim Verlust), Verlustgrund aus `Player.getOutcome().lossState` (`Conceded`,
`LifeReachedZero`, `CommanderDamage`, `Milled`, `Poisoned`, `SpellEffect`, `OpponentWon`, `IntentionalDraw`),
Sieger aus `Game.getOutcome()`.

Verpasste Landabgaben: je Zug, in dem der Sitz am Zug war und **kein** Land gespielt hat, während er eine Hand
hatte (geprüft beim Abschluss des Zuges: `getCardsIn(Hand)` nicht leer); gezählt bis zum Ausscheiden.
`firstMissedLandDrop` = erster solcher Zug.

Der Recorder ist reine Buchhaltung ohne Forge-Nebenwirkungen und liefert am Ende einen `MatchRecord`.

## 2. Datensatz (`mtgplayer.stats.MatchRecord`, JSON)

```json
{ "v": 1, "id": "2026-09-22T19:31:02.418Z-7f3a01", "startedAt": "…", "endedAt": "…", "durationMs": 812345,
  "source": "live|spectate|sparring", "turns": 14, "reason": "AllOpponentsLost", "draw": false,
  "counted": true, "excludeReason": null,
  "seats": [ { "name": "Du", "deck": "Titania, Gaea Incarnate", "human": true,
               "ai": { "mode": "sim", "profile": "Default" },
               "winner": true, "lossReason": null, "eliminatedTurn": null,
               "mulligans": 1, "lands": 7, "landsByTurn": [0,1,2,2,3,…],
               "missedLandDrops": 2, "firstMissedLandDrop": 4,
               "spells": 12, "spellMana": 34, "commanderCasts": 2, "commanderTax": 2, "firstCommanderTurn": 4,
               "damageDealt": 21, "damageTaken": 18, "combatDamageTaken": 12,
               "lifeEnd": 22, "poisonEnd": 0 } ] }
```

`v` ist die Formatversion des Datensatzes (aktuell 1, erstes Feld); Datensätze ohne das Feld gelten als v1,
spätere Änderungen der Definition bleiben so unterscheidbar. Die `id` ist der Endzeitpunkt (ISO, Millisekunden)
plus sechs Hex-Zeichen aus einem hochzählenden Zähler – auch mehrere Partien in derselben Millisekunde
(Sparring, Stück 3) bekommen verschiedene Ids.

`deck` ist der Deckname, unter dem die Partie gestartet wurde (Lobby-Auswahl: Precon- oder Speichername, bei
Textliste der Import-Name) – die Statistik gruppiert danach. `ai` fehlt bei menschlichen Sitzen.

**Nicht gewertet (automatisch, `counted: false` mit `excludeReason`):**
- `turns < 3` → „zu kurz"
- ein Sitz mit `lossReason == "Conceded"` → „aufgegeben"
- die Partie wurde über „Aufgeben"/„Beenden" oder einen Neustart abgebrochen (`HumanMatch.end()` meldet
  `markAborted()`) → „abgebrochen"
- Absturz während der Partie (`CrashLog` meldet sich beim Recorder) → „Absturz"
- **Zugdeckel**: eine headless Partie lief in die Notbremse (siehe unten) → „Zugdeckel“

Bei „abgebrochen" und „Absturz" trägt **kein** Sitz `winner: true` und `draw` ist `false`: Forges erzwungenes
Ende (`GameEndReason.AllHumansLost`) macht sonst jeden Sitz ohne eigenen Ausgang zum Sieger, und ein
nachträgliches „gewertet" würde daraus frei erfundene Siege machen.

### Zugdeckel

Headless Partien (Bench, später Sparring) brechen nach `maxTurns` Spielerzügen ab (`AiMatch`, Standard im Bench 200).
Technisch setzt die Bridge dafür bei allen Sitzen `intentionalDraw()` und beendet mit `GameEndReason.Draw`, sonst macht
Forges `Player.onGameOver()` jeden Sitz zum Sieger. Der Verlustgrund jedes Sitzes ist dadurch `IntentionalDraw` –
fachlich hat sich aber niemand auf ein Remis geeinigt, die Partie wurde abgeschnitten.

Solche Partien zählen deshalb **nicht** in die Bilanz (`counted: false`, `excludeReason: "Zugdeckel"`); die Sitze
behalten ihren echten Ausgang (Remis, kein Sieger), eine Neuwertung von Hand erfindet also nichts. Die Häufigkeit ist
aber selbst eine Kennzahl: ein Deck, das regelmäßig in den Deckel läuft, hat keine verlässliche Siegbedingung.
`summarize` liefert dafür `turnCappedGames` (Anzahl) und `turnCappedRate` (Anteil an gewerteten + Zugdeckel-Partien);
der Screen zeigt eine Kachel „Zugdeckel“, sobald es mindestens eine gibt. `MatchRecorder.markTurnCapped()` setzt das
Flag, `AiMatch` ruft es dort auf, wo es heute schon `turnCapped` setzt. `IntentionalDraw` bleibt als Todesursache nur
für echte, im Spiel vereinbarte Remis übrig.

## 3. Speicher (`mtgplayer.stats.MatchStore`)

`~/.mtg-player/matches.json`: ein JSON-Array, neueste zuletzt; Schreiben atomar (Temp-Datei + `ATOMIC_MOVE`).
Deckel 2000 Datensätze (älteste fallen raus). API: `add(record)`, `all()`, `delete(id)`, `setCounted(id, boolean)`
(setzt `counted` und löscht/behält `excludeReason` als Hinweis). Kaputte Datei → leere Liste + Fehlerzeile in
`bridge.log` statt Absturz (die Datei wird beim nächsten Schreiben ersetzt) – über `CrashLog.warn`, nicht
`CrashLog.report`: die laufende Partie hat damit nichts zu tun und darf davon weder als „Absturz" aus der
Wertung fallen noch im Browser als abgebrochen gelten.

## 4. Protokoll

- Bei Verbindung und nach jeder Änderung: `{ "type": "matches", "matches": [ … ] }` (ganze Liste – klein genug).
- Client → Bridge: `{ "type": "deleteMatch", "id": "…" }`, `{ "type": "setMatchCounted", "id": "…", "counted": true|false }`;
  Fehler wie gehabt als `error` („Partie <id>: …").
- Nach einer beendeten Partie schickt die Bridge zusätzlich zur `gameOver`-Nachricht die aktualisierte `matches`-Liste.

## 5. Auswertung (Web, rein, getestet: `web/src/matchStats.ts`)

`deckNames(records)` → Decks mit mindestens einer Partie (Name + Anzahl). `summarize(records, deck)` über die
**gewerteten** Partien mit diesem Deck. Ausgewertet wird **deckbezogen, nicht sitzbezogen**: wer das Deck gespielt
hat (Mensch oder KI), spielt keine Rolle – im Sparring pilotiert eine KI Kevins Deck, und genau diese Partien
sollen zählen. Je Partie zählt höchstens ein Sitz mit diesem Deck (bevorzugt der menschliche), damit ein
Spiegelspiel eine Partie bleibt:
- Bilanz: Siege/Niederlagen/Unentschieden, Siegquote mit 95-%-Wilson-Intervall
- Ø Zuglänge, Ø Dauer; Mulligan-Quote (Anteil Partien mit ≥ 1 Mulligan) und Ø Mulligans
- Ø Länder bis zum **eigenen** Zug 3 und 5 – nur über Partien, in denen der Sitz so viele eigene Züge hatte
  (`landsByTurn.length > n`); gibt es keine solche Partie, fehlt die Kennzahl und die Kachel zeigt „–".
  Anteil Partien mit verpasster Landabgabe und Ø verpasste
- Ø Zauber je Partie, Ø Mana-Summe; Ø Zug des ersten Commander-Casts (`firstCommanderTurn`); Ø Commander-Steuer
- Todesursachen (Verteilung der `lossReason` des gewerteten Sitzes), Ø Schaden genommen/gemacht
- Gegner-Tabelle: je gegnerischem Deck Partien/Siege

Das Modul rechnet nur; es kennt keine Bewertung („gut/schlecht") – das ist Stück 2.

## 6. Screen „Statistik"

Neuer Screen (Store `screen: "lobby" | "table" | "stats"`), Knopf in der Lobby neben „Spiel starten"; zurück über
„Zur Lobby". Aufbau: links Deckliste (Name + Partienzahl, Auswahl), rechts die Kennzahlen aus §5 als Kacheln,
darunter die Partienliste (Datum, Quelle, Gegner-Decks, Ergebnis, Züge, Dauer) mit zwei Bedienelementen je Zeile:
Papierkorb (löscht, Zwei-Klick-Bestätigung wie beim Deck-Löschen) und Schalter „gewertet" (setzt `counted`).
Nicht gewertete Zeilen sind gedimmt und tragen den Grund. Über der Liste ein Filter „nur gewertete" (Standard an).
Leerer Zustand: „Noch keine Partien – spiele eine Runde oder nutze später das Sparring."

## 7. Nachweise

- `MatchRecorderTest` (Szenen-Harness `mtgplayer.scene.Scene`): Länder je Zug, verpasste Landabgabe, Mulligan,
  Zauber/Mana, Commander-Cast und -Steuer, Schaden beidseitig, Verlustgrund `LifeReachedZero`.
- `MatchRecorderAiTest`: ein echtes kurzes `AiMatch` (Zugdeckel 4, zwei Precons) – Recorder liefert einen
  plausiblen Datensatz (Sitze, Züge, Länder > 0, keine Ausnahme).
- `MatchStoreTest`: hinzufügen/lesen/löschen/`setCounted`, atomares Schreiben, Deckel, kaputte Datei.
- `BridgeEndToEndTest`: `matches` bei Verbindung; `deleteMatch` unbekannt → `error`.
- Web: `matchStats.test.ts` (Wilson, Mittelwerte, Filter auf gewertete, Gruppierung, leere Eingabe),
  `store.test.ts` (Reducer `matches`), Screenshot des Screens mit Fixture.

## Nicht in diesem Stück

Sparring-Knopf (Stück 3), Schwächen-Regeln und Kartenvorschläge (Stück 2), Verlaufsgrafiken, Export.
