# Aufzeichnung der gewirkten Karten – Design

Stand: 2026-09-25. Kevins Auftrag nach den Kartenvorschlägen: „dann bau die aufzeichnung der gewirkten
karten". Ziel ist der Schnittgrund, der bisher fehlte – „hat in deinen Partien nie gewirkt" – und eine
Kartentabelle je Deck, in der Kevin dasselbe selbst nachlesen kann.

Entschieden: **volle Kartenbiografie** (Hand, gewirkt, gekontert, verloren, Endzone), **nur für Sitze, die
eines von Kevins eigenen Decks spielen** (auch wenn die KI es im Sparring steuert), und **beides** als
Verwendung – eigene Tabelle im Statistik-Board und Schnittgrund in den Kartenvorschlägen.

## 1. Warum eigene Dateien

`~/.mtg-player/matches.json` hält heute 25 Datensätze in 100 KB (Median 4,3 KB je Partie) und wird bei
**jedem** Spielende vollständig neu geschrieben, Obergrenze 2000 Datensätze. Eine Kartenbiografie kostet rund
7 KB je Sitz – im Datensatz selbst würde die Datei auf ein Vielfaches wachsen und jedes Spielende teurer
machen. Deshalb:

- Kartendaten kommen nach `~/.mtg-player/cards/<partie-id>.json`, eine Datei je Partie, geschrieben genau
  einmal. Sparring-Kindprozesse schreiben damit nebeneinander statt in dieselbe Datei.
- `MatchRecord` **ändert sich nicht**; die Formatversion bleibt bei 2 und alle bisherigen Datensätze bleiben
  gültig. Die Verbindung ist die Partie-Id.
- Fehlt zu einer Partie die Kartendatei (ältere Partien, gelöschte Datei), gilt sie als „keine Kartendaten" –
  nie als „Karte kam nie vor".

## 2. Aufzeichnung (`mtgplayer.stats.CardLog`, genutzt von `MatchRecorder`)

Der Recorder bekommt zusätzlich die Namen von Kevins eigenen Decks (`Set<String> ownDecks`, vom Aufrufer aus
dem `DeckStore`). Ein Sitz wird nur aufgezeichnet, wenn sein Deckname darin steht; alle anderen Sitze
erzeugen keine Zeile.

Während der Partie zählt der Recorder **je Karten-Id**, nicht je Name – ein verdecktes Permanent trägt in
`CardView` nicht seinen wahren Namen, die Zuordnung über die Id ist die einzige verlässliche. Gezählt wird an
den bereits abonnierten Ereignissen:

| Zähler | Quelle |
|---|---|
| `hand` | `GameEventCardChangeZone`, Bibliothek → Hand (Starthand und Nachziehen; nach einem Mulligan zählt die neue Hand erneut) |
| `cast` | `GameEventSpellAbilityCast` (Zauber, Ursprungskarte) und `GameEventLandPlayed` (Länder werden nicht gewirkt) |
| `castTurn` | **eigener** Zug des ersten `cast` (`ownTurns` des Sitzes, mindestens 1) – dieselbe Zählweise wie `firstCommanderTurn` und `firstMissedLandDrop`; der globale Zugzähler wäre in einer 4er-Runde das Vierfache und mit einem Duell nicht vergleichbar |
| `countered` | `GameEventSpellRemovedFromStack` ohne vorherige Auflösung – dieselbe Erkennung wie `spellsCountered` |
| `lost` | `GameEventCardChangeZone`, Spielfeld → Friedhof/Exil |

Beim Spielende (`finish()`) läuft der Recorder über `Player.getAllCards()` **des aufgezeichneten Sitzes** –
das sind echte `Card`-Objekte mit wahrem Namen – und hält je Id Name und Endzone fest
(`hand`, `library`, `graveyard`, `exile`, `battlefield`, `command`, `stack`). Damit bekommt auch jede Karte
eine Zeile, die die ganze Partie in der Bibliothek lag; genau diese Zeile trennt „nie gezogen" von
„schlechte Karte". Eine Id, die dort nicht mehr auftaucht, behält den zuletzt in einem Ereignis gesehenen
Namen und die Endzone `none`.

Zum Schluss werden die Zeilen **je Name zusammengefasst** (vier Kopien derselben Karte sind eine Zeile,
Zähler addiert; `castTurn` ist der kleinste, `end` die Zone der zuletzt zusammengefassten Kopie – bei
mehreren Kopien trägt die Zeile zusätzlich `copies`). Spielsteine (`isToken`) fallen raus, sie stehen in
keinem Deck.

### Dateiformat

```json
{ "v": 1, "id": "<partie-id>", "seats": [
  { "seat": 0, "deck": "Titania Landfall",
    "cards": [
      { "name": "Sakura-Tribe Elder", "copies": 1, "hand": 2, "cast": 1, "castTurn": 4,
        "countered": 0, "lost": 1, "end": "graveyard" },
      { "name": "Nesting Dragon", "copies": 1, "hand": 1, "cast": 0, "countered": 0, "lost": 0, "end": "hand" }
    ] } ] }
```

Nullwerte werden weggelassen (wie im übrigen Protokoll). `seat` ist der Sitzindex aus `MatchRecord.seats`.

## 3. Ablage (`mtgplayer.stats.CardStore`)

- `write(CardLog log)` schreibt `~/.mtg-player/cards/<id>.json` atomar (Temp-Datei, `ATOMIC_MOVE`), wie
  `MatchStore` es tut.
- `read(String matchId)` liefert `Optional<CardLog>`; kaputte Datei → leeres Optional plus `CrashLog.note`,
  nie eine Ausnahme nach außen.
- `delete(String matchId)` entfernt die Datei; der vorhandene `deleteMatch`-Weg der Bridge ruft das mit auf.
- Tests schreiben nie nach `~/.mtg-player`: der Pfad kommt wie bei `MatchStore` aus `ForgeBoot.dataDir()`.

## 4. Auswertung (`mtgplayer.stats.CardStats`)

`CardStats.of(String deck, List<MatchRecord> matches, CardStore store)` läuft über die **gewerteten**
Partien dieses Decks (`counted`, Deckname normalisiert wie in `matchStats.ts`), liest je Partie die
Kartendatei und fasst zusammen:

```json
{ "games": 12, "withCardData": 9,
  "cards": [ { "name": "Nesting Dragon", "imageKey": "c:…", "manaCost": "3 {R}", "cmc": 4,
               "handGames": 6, "castGames": 0, "avgCastTurn": null,
               "stuckGames": 5, "neverDrawnGames": 3, "counteredGames": 0, "lostGames": 0 } ] }
```

- `games`: gewertete Partien des Decks. `withCardData`: davon die mit Kartendatei – die Basis aller
  folgenden Zahlen, und die einzige, die im UI genannt werden darf.
- `handGames`: Partien, in denen die Karte auf der Hand war. `castGames`: Partien, in denen sie gewirkt
  wurde. `avgCastTurn`: Mittel der ersten Wirk-Züge (nur über Partien mit `cast`), sonst `null`.
- `stuckGames`: Partien, in denen sie auf der Hand war und **nicht** gewirkt wurde.
- `neverDrawnGames`: Partien, in denen sie nie auf die Hand kam.
- Grundländer (Wald, Insel, Sumpf, Gebirge, Ebene) werden mit aufgezeichnet, aber in der Auswertung
  **weggelassen** – dreißig Wälder oben in jeder Tabelle sagen nichts, und Landflut hat eigene Kennzahlen.
- Unter **fünf** Partien mit Kartendaten liefert `CardStats` die Karten trotzdem, setzt aber
  `enough: false`; UI und Vorschläge behandeln das als „noch keine Aussage" (dieselbe Untergrenze wie
  `findings.ts`).

Kein Zwischenspeicher: die Auswertung liest die Dateien bei jeder Anfrage neu (bei 500 Partien rund 3,5 MB,
einmal je Klick). Das hält den Zustand ehrlich – eine gelöschte oder umgewertete Partie wirkt sofort.

## 5. Protokoll

```json
{ "type": "deckCards", "deck": "<Name>" }
```
→
```json
{ "type": "cardStats", "deck": "<Name>", "games": 12, "withCardData": 9, "enough": true, "cards": [ … ] }
```

Unbekanntes Deck → `error` „Kartenauswertung <name>: unbekanntes Deck". Die Antwort kommt aus einem
Hintergrund-Task (`runBackgroundTask("deck-cards", …)`) wie `analyzeDeck` – Dateilesen gehört nicht auf den
UI-Thread.

## 6. Anzeige (`web/src/components/DeckCards.tsx`)

Im Statistik-Board ein eigener Abschnitt „Karten" unter den Kartenvorschlägen, je Deck, geladen wie die
Vorschläge **erst auf Klick** (er liest Dateien, das soll nicht beim Durchblättern passieren).

- Kopfzeile: „Karten · 9 von 12 gewerteten Partien mit Aufzeichnung". Unter fünf Partien mit Daten steht
  stattdessen „Noch zu wenige Partien mit Aufzeichnung (9 von 5 nötig)" und die Tabelle bleibt zu.
- Tabelle je Karte: Miniatur, Name, Manakosten, „6 von 9 Partien auf der Hand", „nie gewirkt" bzw.
  „4× gewirkt, im Schnitt ab Zug 5", „5× ungenutzt liegen geblieben", „3× nie gezogen".
- Sortierung standardmäßig nach dem Handlungsbedarf: häufig auf der Hand, selten gewirkt zuerst
  (`stuckGames` absteigend, dann `handGames` absteigend, dann Name). Umschalter auf „Name" und auf
  „am häufigsten gewirkt".
- Karten ohne jede Aufzeichnung (nie in einer Partie mit Kartendatei aufgetaucht) stehen nicht in der
  Tabelle – sie entstehen erst durch die Aufzeichnung, nicht aus der Deckliste.

## 7. Schnittgrund in den Kartenvorschlägen

`Suggestions.of(...)` bekommt zusätzlich die Kartenauswertung des Decks (`Map<String, CardStats.Card>`, leer
wenn `enough: false`). Die Rangfolge der Schnittkandidaten bekommt eine neue **erste** Stufe:

1. Karte war in mindestens drei Partien auf der Hand und wurde **nie** gewirkt – Grund:
   „in 9 Partien 6× auf der Hand, nie gewirkt".
2. danach wie bisher: nicht bei EDHREC geführt, niedriger Anteil, teuerste Karte der Rolle.

Der neue Grund sticht die bisherigen, weil er aus Kevins eigenen Partien kommt statt aus fremden Decks. Er
erscheint nur, wenn die Auswertung `enough` meldet; sonst bleibt alles wie heute.

## 8. Tests

- **Java**: `CardLog`-Zusammenfassung (mehrere Kopien, Spielstein fällt raus, Endzone aus `getAllCards`,
  Id ohne Endzone behält den gesehenen Namen); `CardStore` (schreiben/lesen/löschen, kaputte Datei);
  `CardStats` (Zählung über mehrere Partien, ungewertete Partie zählt nicht, fehlende Kartendatei senkt
  `withCardData`, Grundländer fallen raus, `enough` unter fünf Partien); Szenentest über den Recorder, der
  eine kleine Partie spielt und prüft, dass eine gewirkte Karte `cast` und eine liegen gebliebene `end:
  hand` trägt; `Suggestions` mit Kartendaten (neuer Schnittgrund sticht, ohne `enough` unverändert).
- **TypeScript**: Sortierung und Texte der Tabelle, Verhalten unter der Untergrenze, Verhalten ohne Antwort
  und bei `error`.
- **Screenshot**: Statistik-Board mit geladener Kartentabelle (Fixture) über `scripts/shot-stats.mjs`.

## 9. Nicht enthalten

- Karten der Gegner (eigene Baustelle, vierfache Datenmenge).
- Siegquote mit und ohne eine bestimmte Karte – bei diesen Stichproben wäre das Rauschen mit Nachkommastelle.
- Aktivierte Fähigkeiten (anderes Ereignis, eigenes Stück).
- Nachträgliche Aufzeichnung für bereits gespielte Partien – die Daten gibt es erst ab jetzt.

## 10. Nachtrag: Zeitachse auf eigene Züge (Formatversion 3)

Kevins Regel – „die turns müssen jeweils für jeden einzeln gezählt werden, da Forge jeden Zug hochzählt und
nicht wie üblich für jeden Spieler" – gilt auch für die Zeitachse. Sie speichert heute Forges globale
Zugnummer (`notePoint(turnOwner, e.turnNumber())`): In einer 4er-Runde liegen die Punkte eines Sitzes bei
1, 5, 9 … und die des nächsten bei 2, 6, 10 …; zwei Kurven liegen versetzt statt übereinander, und „Zug 20"
ist in Wahrheit die fünfte eigene Runde.

- `MatchRecord.VERSION` steigt auf **3**. Ab v3 trägt `TurnPoint.turn` den **eigenen** Zug des Sitzes
  (`ownTurns`, derselbe Zähler wie `landsByTurn`, das schon so zählt). Alle übrigen Felder bleiben, wie sie
  sind; die vorhandenen Prüfungen im Client lauten durchweg `v >= 2` und gelten damit weiter.
- Alte Datensätze werden **nicht** umgerechnet: eine Division durch die Sitzzahl wäre geraten, sobald ein
  Spieler ausgeschieden ist. Sie behalten ihre globalen Zugnummern.
- `MatchTimeline.tsx` beschriftet die Achse nach der Formatversion des Datensatzes: ab v3 „Eigener Zug",
  darunter „Partiezug (alle Sitze)". Gemischt wird nie – die Version gilt für den ganzen Datensatz.
- `MatchRecord.turns` (Partiedauer) und `eliminatedTurn` bleiben globale Zugnummern: die eine ist als
  „Partiezüge insgesamt" beschriftet, die andere wird nur als Verhältnis zu `turns` ausgewertet, in dem sich
  die Zählweise herauskürzt.
