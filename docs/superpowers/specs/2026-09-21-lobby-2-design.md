# Lobby 2 – Deck-Kacheln, Auswahl merken, Nochmal/Serie, Archidekt-Resync – Design

Stand: 2026-09-21. Kevins Wünsche: Dropdowns zu hell (aufgeklappte Liste), Bilder in der Deckliste bzw. Deckauswahl
überarbeiten, gewählte Decks nach einem Spiel behalten / Replay / Best-of, Resync für Archidekt-Decks. Gewählt: Variante
A „Deck-Kacheln mit Commander-Bild".

## 1. Protokoll

`lobby`-Nachricht: `precons` und `decks` werden Listen von `DeckInfo` statt Strings:

```ts
interface DeckInfo { name: string; commanders: { name: string; imageKey?: string }[]; archidekt?: string }
```

`archidekt` = Archidekt-Deck-Id (nur bei eigenen Decks, die aus Archidekt importiert wurden). Neue Client-Nachricht
`{ type: "resyncDeck", name: string }`. Bridge antwortet mit einer neuen `lobby`-Nachricht (Erfolg) oder `error`
(Text „Resync <name>: …"). Kein weiterer Client außer `web/`, deshalb keine Abwärtskompatibilität nötig; `store.ts`
und Tests werden angepasst.

## 2. Bridge

- `Messages.Lobby(List<DeckInfo> precons, List<DeckInfo> decks, …)`; `DeckInfo(String name, List<Commander> commanders,
  String archidekt)`; `Commander(String name, String imageKey)` mit `PaperCard.getImageKey(false)`.
- `Precons.infos()`: einmal beim ersten Aufruf alle Precons laden (Deck → Commander-Karten) und statisch cachen
  (die Liste ändert sich zur Laufzeit nicht). `DeckStore.infos()`: bei jedem Aufruf aus den Dateien (ändern sich).
- Archidekt-Herkunft: `DeckSource.resolve` setzt bei `archidekt`-Importen vor dem Speichern das Deck-Tag
  `archidekt:<id>` (`Deck.getTags()`, Forge schreibt Tags in `[metadata]` `Tags=`, Trenner „,"). `DeckStore.infos()`
  liest die Id aus dem Tag (`DeckSerializer.readDeckMetadata`-Weg über `load(name).getTags()` oder die `Tags=`-Zeile
  wie `Name=`).
- `resyncDeck`: `Bridge.handle` → Hintergrund-Task (Netz): `store.load(name)` → Tag → `archidekt.fetch(id)` →
  `DeckImport.parse` → Probleme → `error`; sonst `store.save(name, deck)` mit demselben Tag (Name bleibt der
  gespeicherte, nicht der aktuelle Archidekt-Name) → `lobby`. Ohne Tag → `error` „kein Archidekt-Deck".
- Tests: `DeckStoreTest` (Tag rein/raus, `infos()` mit Commander-Bild), `DeckSourceTest` (Archidekt-Import setzt Tag),
  `BridgeTest`/vorhandenes Muster für `resyncDeck` mit Fake-Fetcher (Erfolg + Fehlerfall), `StateSerializer` unberührt.

## 3. Web – Deckauswahl (`web/src/components/DeckPicker.tsx`, neu)

Ersetzt `picker()` in `Lobby.tsx` (pro Sitz eine Instanz, Prop `pick: Pick`, `onChange`, `label`).

- **Kachel** (`.deck-tile`, Button): links Commander-Art (`CardImage` über `imageKey`, 56×78 px, bei zwei Commandern
  beide nebeneinander halb überlappt), rechts Deckname (zweizeilig abgeschnitten) und darunter eine Marke
  (`Precon` / `Eigenes Deck` / `Archidekt` / `Textliste`), ohne Auswahl: gestrichelte Kachel „Deck wählen".
- **Panel** (`.deck-panel`, Overlay über der Lobby-Karte, `role="dialog"`, Escape und Klick außerhalb schließen):
  Kopf mit Reitern `Precons · Eigene Decks · Import` und Suchfeld (filtert Name und Commander-Namen, case-insensitiv);
  Inhalt ein Raster (`.deck-grid`, `repeat(auto-fill, minmax(150px, 1fr))`) aus `.deck-card` (Art oben 150×~110
  mit `object-position: top`, Name, Commander-Name klein, Marke); Klick wählt und schließt. Unter „Eigene Decks"
  tragen Archidekt-Decks eine `↻ Resync`-Schaltfläche in der Kachel (stopPropagation), die `resyncDeck` sendet und
  bis zur nächsten `lobby`-Nachricht „synchronisiert …" zeigt; Fehler erscheinen wie heute im `import-error`.
  „Import": die heutigen Formulare Textliste (Name + Textarea) und Archidekt-URL (URL + Name), Knopf „Übernehmen"
  setzt den Pick (`kind: text|archidekt`) und schließt.
- Tastatur: Suchfeld hat beim Öffnen den Fokus; Kacheln sind Buttons (Tab/Enter).

## 4. Web – Dropdowns

Native `select` bleiben für Deck-Art nicht mehr (die Kachel ersetzt sie), nur für KI-Modus/Profil/Best-of. CSS:
`select { background: #161d23; }` (opak statt `rgba(0,0,0,.25)`) und `option { background: #161d23; color:
var(--text); }` – Chromium übernimmt die Farbe der aufgeklappten Liste vom `select`, mit transparentem Hintergrund
fällt es (Windows) auf ein helles System-Popup zurück. Inputs/Textareas behalten ihren Stil.

## 5. Web – Auswahl merken

`web/src/lobbyPicks.ts` (rein, getestet): `savePicks(storage, { human, ais })`, `loadPicks(storage)` →
`{ human: Pick, ais: Pick[] }` (Validierung: bekannte `kind`, Strings; sonst Default). Speicher in `localStorage`
unter `mtg-player.lobbyPicks`, Muster wie `aiSettings.ts` (dort bleibt KI-Modus/Profil/Timeout). `Lobby.tsx` lädt
beim ersten `lobby` mit Daten und speichert bei jeder Änderung; Picks, deren Deck nicht mehr existiert (z. B. gelöschte
Datei), werden beim Laden auf leer gesetzt. Damit sind nach „Zur Lobby" dieselben Decks gewählt.

## 6. Web – Nochmal spielen und Serie

- Store: `lastStart?: StartGameMsg` (beim Senden gesetzt), `series: { key: string; wins: Record<string, number>;
  games: number }` – `key` = JSON der Deck-/KI-Angaben ohne Namen; `gameOver` mit `winner` zählt `wins[winner]++`,
  `games++` (ohne Gewinner: nur `games++`); ein `startGame` mit anderem `key` setzt die Serie zurück.
- Overlay (`Table.tsx`): Titel wie heute, darunter „Serie: Du 2 · KI 1 1" (nur wenn `games > 1` oder Best-of aktiv),
  Knöpfe „Nochmal spielen" (sendet `lastStart` erneut, bleibt in der Tischansicht bis der neue Zustand kommt) und
  „Zur Lobby". Bridge-Voraussetzung: nach `gameOver` ist `match.isRunning()` false (HumanMatch: `isGameOver`), damit
  `startGame` sofort geht – Test in `HumanMatchTest`/vorhanden prüfen, sonst in `Bridge.startGame` ein beendetes
  Spiel per `match.end()` vorher abräumen.
- Best-of: Lobby-Einstellung `Serie: aus / Best of 3 / 5 / 7` (Select, in `aiSettings` mitgespeichert als
  `bestOf: 0|3|5|7`). Erreicht ein Spieler `ceil(n/2)` Siege, zeigt das Overlay „<Name> gewinnt die Serie (2:1)" und
  „Nochmal spielen" wird zu „Neue Serie" (setzt `series` zurück und startet). Die Lobby zeigt den Serienstand als
  Zeile unter dem Start-Knopf, solange `games > 0`.

## 7. Nachweise

- Bridge: Tests aus §2; `mvn -q test` grün.
- Web: `lobbyPicks.test.ts`, `store.test.ts` (Serie: zählen, zurücksetzen bei anderem key, Best-of-Entscheidung als
  reine Funktion `seriesWinner(series, bestOf)`), `deckSearch.test.ts` (Filterfunktion des Panels), bestehende Tests
  angepasst (`lobby`-Fixture mit `DeckInfo`).
- Fixtures: `fixtures/lobby.json` auf `DeckInfo` (mit echten `imageKey`s aus Precons, z. B. `c:Felothar the
  Steadfast|TDC|1`), `shot-lobby.mjs` bekommt `--open-panel` (klickt die
  erste Kachel) und `--tab=<precons|saved|import>`; Screenshots 1600×900: Lobby mit gewählten Kacheln, offenes Panel
  „Precons" mit Bildern, „Eigene Decks" mit Resync-Knopf, „Import"; dazu das Spielende-Overlay mit Serie (Fixture
  `table.json` + `gameOver`-Nachricht `{ "type": "gameOver", "winner": "Du" }`).
- `npm test`, `npm run build`, `layout-check` für die Tisch-Fixtures unverändert grün (Lobby hat keinen Layout-Check;
  Sichtprüfung per Screenshot).

## Nicht in dieser Runde

Deck löschen/umbenennen in der UI, Deck-Vorschau (Kartenliste) im Panel, mehrere Clients, Serie über Neustarts der
Seite hinweg.
