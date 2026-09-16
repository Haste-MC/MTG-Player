# MTG-Player – Design

Stand: 2026-09-16

## Ziel

Ein lokaler Commander-Tisch (2–6 Spieler, voller Kartenpool) mit KI-Gegnern und
einer modernen Browser-Oberfläche. Regeln, Kartendatenbank und KI kommen von
Forge; neu entstehen nur eine Java-Bridge und ein React-Frontend.

**V1-Umfang:** Ein menschlicher Spieler gegen 1–5 KI-Gegner, alles auf einem
Rechner. Mehrere Menschen übers Netz sind ein späterer Ausbau; die Bridge wird
so geschnitten, dass jeder Sitz eine eigene `IGuiGame`-Instanz und eine eigene
Verbindung hat, damit der Ausbau kein Umbau wird.

**Nicht in V1:** Animationen, Drag & Drop, Sound, Mobile-Layout, Deckbuilder,
Spiel speichern/laden, Undo, Netzwerkspiel, Moxfield.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Regel-Engine | Forge (`forge-game`, `forge-ai`, `forge-gui`), Git-Submodule auf festem Release-Tag, unverändert |
| Anbindung | Bridge implementiert `IGuiBase` + `IGuiGame` – dritte GUI neben Desktop und Mobile |
| Bridge-Sprache | Java 17, Maven-Modul, nur die Bridge |
| Frontend | React + TypeScript + Vite, Store mit Zustand |
| Prioritätsstil | Arena: Auto-Pass, wenn nichts spielbar; Phasen-Stops; "Volle Kontrolle"-Toggle |
| Decks | Textliste (Arena/Archidekt-Format) in V1, Archidekt-URL in V2, kein Moxfield |
| KI-Decks | Forges Commander-Precons, wählbares AI-Profil |
| Kartenbilder | Scryfall über Bridge-Proxy mit Disk-Cache |
| Projektort | `~/projects/MTG-Player`, eigenes Repo |

## 1. Gesamtbild

```
MTG-Player/
├── forge/              git-Submodule, fester Release-Tag (Java 17, Maven)
├── bridge/             Java-Maven-Modul, hängt an forge-gui + forge-ai
│   └── src/main/java/mtgplayer/
│       ├── Main.java            startet Forge-Model + HTTP/WebSocket-Server
│       ├── WebGuiBase.java      IGuiBase: Pfade, Bild-Loader (leer), UI-Thread
│       ├── WebGuiGame.java      IGuiGame: Zustand raus, Entscheidungen rein
│       ├── protocol/            JSON-Nachrichten (Jackson), Serializer für GameView/PlayerView/CardView
│       ├── match/               Lobby → GameRules → HostedMatch.startMatch(...)
│       ├── decks/               Textlisten-Parser (Forges DeckSerializer), später Archidekt
│       └── images/              Scryfall-Proxy mit Disk-Cache
├── web/                React + TS + Vite
└── docs/superpowers/   Specs und Pläne
```

Ein Prozess (`java -jar bridge.jar`) serviert auf `localhost:8080` das
statische Frontend, die Bilder und den WebSocket. Kein Docker, kein zweiter
Server.

### Forge-Andockpunkte

- `IGuiBase` (per `GuiBase.setInterface`): Dateipfade auf `forge/forge-gui/res`,
  Bild-Loader liefert nichts (Bilder macht der Browser), "auf UI-Thread
  ausführen" = Single-Thread-Executor der Bridge.
- `IGuiGame`: eine Instanz pro menschlichem Sitz. Bekommt Zustandsupdates
  (`updateZones`, `updateStack`, `updatePhase`, `updateLives`, …) und stellt
  Fragen (`one`, `many`, `order`, `confirm`, `getAbilityToPlay`,
  `assignCombatDamage`, …). Anzeige-Details, die wir nicht brauchen, werden
  als No-Op implementiert.
- Spielstart wie in Desktop/Mobile: `HostedMatch.startMatch(GameRules(Commander),
  players, guis)` mit `RegisteredPlayer.forCommander(deck)` pro Sitz,
  `LobbyPlayerHuman` für den Menschen, `LobbyPlayerAi` mit Profil für Bots.

### Threading

Forge läuft das Spiel auf einem eigenen Game-Thread. Zwei Arten von Fragen:

1. **Input-System** (Prio, Karten spielen, Mana zahlen, Angreifer/Blocker):
   Forge legt ein `Input` auf die `InputQueue`, markiert wählbare
   Karten/Spieler und blockiert per Latch. Die Bridge schickt
   `{type:"input", …}` an den Browser; ein Klick wird zu `selectCard`,
   `selectPlayer`, `selectButtonOk` oder `selectButtonCancel` auf dem
   Bridge-UI-Thread.
2. **Synchrone Dialoge** (`one`, `many`, `order`, `confirm`, Modi, X-Wert):
   Forge ruft die Methode auf dem Game-Thread und erwartet den Rückgabewert.
   Die Bridge schickt `{type:"choice", id, …}` und blockiert auf einem
   `CompletableFuture`, bis `{type:"answer", id, …}` zurückkommt.

Zustandsupdates gehen als vollständiger Snapshot aus Sicht des menschlichen
Spielers raus. Kein Diff-Protokoll.

### Forge bleibt unverändert

Alles im Submodule bleibt, wie es ist. Braucht es doch einen Patch, wird das
ein dokumentierter Fork mit genau diesem Patch.

## 2. Protokoll

JSON über eine WebSocket-Verbindung, Nachrichtentyp im Feld `type`.

### Bridge → Browser

| type | Inhalt | Wann |
|---|---|---|
| `lobby` | verfügbare Precons, AI-Profile, zuletzt verwendete eigene Decks | nach Connect |
| `state` | vollständiger Snapshot (unten) | nach jedem Forge-Update, gedrosselt auf max. ~30/s |
| `input` | `prompt`, `selectableCards[]`, `selectablePlayers[]`, `buttons:{ok?:label, cancel?:label}` | Forge wartet auf Klick |
| `choice` | `id`, `kind` (`one`/`many`/`order`/`confirm`/`number`/`ability`/`damage`), `title`, `options[]`, `min`, `max` | synchroner Dialog |
| `log` | Zeile aus Forges GameLog | laufend |
| `gameOver` | Gewinner, Grund | Ende |
| `error` | Text | Bridge-Fehler, kein Regelfehler |

### Browser → Bridge

| type | Inhalt |
|---|---|
| `startGame` | `humanDeck` (Text), `opponents:[{deck: preconId \| text, profile}]`, `startingLife` (40) |
| `selectCard` / `selectPlayer` | `id`, `alt: true` bei Rechtsklick (Forge: andere Fähigkeit wählen) |
| `ok` / `cancel` | – |
| `answer` | `id`, `value` (Index, Index-Liste, Reihenfolge, Zahl, bool) |
| `setStops` | `{phase: bool}` getrennt für eigene und gegnerische Züge |
| `fullControl` | `bool` |
| `concede` | – |

### Snapshot (`state`)

```ts
{
  turn: number, activePlayer: id, phase: "MAIN1"|..., priority: id,
  players: [{ id, name, isHuman, life, poison, commanderDamage: {[from]: n},
              zones: { hand: CardRef[]|count, library: count, graveyard: CardRef[],
                       exile: CardRef[], command: CardRef[], battlefield: CardRef[] },
              manaPool: {W,U,B,R,G,C} }],
  stack: [{ id, text, source: CardRef, controller: id, targets: [] }],
  cards: { [id]: { name, setCode, collectorNumber, faceDown, tapped, sick,
                   power, toughness, damage, counters: {}, attachedTo, attachments[],
                   controller, owner, oracleText, types[], keywords[],
                   attacking?: target, blocking?: [ids], chosenColors?, ... } }
}
```

`cards` ist ein flaches Wörterbuch nach Forge-ID; Zonen halten nur IDs. Für
Karten, die der Spieler nicht sehen darf, kommt nur `{ id, faceDown: true }`.
Die Bridge filtert über `CardView.canBeShownTo(humanPlayerView)`, bevor
etwas rausgeht – das ist die einzige Stelle für Informationsverbergung.

### Arena-Stil

`forge-gui` bringt Auto-Yield und Phasen-Stops mit (`isUiSetToSkipPhase`,
`autoPassCancel`). Die Bridge hält die Stop-Konfiguration und beantwortet
Forges Rückfrage daraus. `fullControl: true` schaltet alle Stops an. Kommt
ein `input`, bei dem der Spieler keine spielbare Karte und keine
aktivierbare Fähigkeit hat und die Phase keinen Stop hat, passt die Bridge
selbst; der Browser sieht das `input` nicht. Diese Entscheidung ist eine
reine Funktion (Phase, Stops, spielbare Aktionen → passen ja/nein) und liegt
in der Bridge, damit sie testbar ist.

### Bilder

`GET /img/{setCode}/{collectorNumber}` → Bridge liefert aus
`~/.mtg-player/images/`, sonst holt sie das Bild von
`api.scryfall.com/cards/{set}/{num}?format=image` (max. ~8 Requests/s) und
cached es.

## 3. Frontend

Zwei Screens, ein Store.

### Lobby

Textfeld für das eigene Deck (mit "zuletzt verwendet"), 1–5 Gegner-Slots mit
durchsuchbarem Precon-Dropdown oder Textliste, AI-Profil pro Slot,
Start-Button. Parser-Fehler (unbekannte Karte, kein Commander) erscheinen
neben dem Textfeld.

### Tisch

```
┌─────────────────────────────────────────────────────────┬────────┐
│  Gegner 1   │  Gegner 2   │  Gegner 3   │  Gegner 4     │ Stack  │
│  Life · CMD │  Life · CMD │  Life · CMD │  Life · CMD   │        │
│  Battlefield (kompakt)    ...                            │────────│
├─────────────────────────────────────────────────────────┤ Log    │
│  Phasenleiste  UT UP DR M1 BC DA DB CD EC M2 END  [◉ Stops] │      │
├─────────────────────────────────────────────────────────┤        │
│  DEIN Battlefield – Kreaturen oben, Länder/Rest unten    │        │
│  CMD │ Lib │ Grave │ Exile      Life 40 · Manapool       │        │
├─────────────────────────────────────────────────────────┤        │
│  Hand (Fächer, Hover = groß)             [Prompt] [OK] [X]│      │
└─────────────────────────────────────────────────────────┴────────┘
```

- 1 Gegner: ganze obere Zeile. 2–5 Gegner: aufgeteilt, jede Zone scrollbar,
  Klick auf den Namen öffnet ein Vollbild-Overlay dieser Zone.
- Wählbare Karten (`input.selectableCards`) leuchten, alles andere ist
  während eines Inputs gedimmt. Eine Regel: *leuchtet es, kann man es
  klicken.* Rechtsklick auf eine leuchtende Karte = `alt: true`.
- Prompt-Zeile unten rechts mit Forges Text und OK/Cancel-Labels aus dem
  `input`. Enter = OK, Esc = Cancel, Leertaste = Prio passen.
- `choice`-Dialoge als modales Overlay je nach `kind`: Liste (one/many),
  sortierbare Liste (order), Ja/Nein (confirm), Zahl (number),
  Fähigkeitenliste (ability), Schadenszuweisung (damage: Blocker mit
  +/-Feldern).
- Karten-Hover zeigt rechts groß Bild, Oracle-Text, Zähler, Anhänge.
  Friedhof/Exil/Kommandozone öffnen per Klick ein Overlay.
- Phasenleiste: aktuelle Phase hervorgehoben; Klick toggelt den Stop,
  umschaltbar zwischen eigenem Zug und gegnerischen Zügen. Daneben der
  "Volle Kontrolle"-Toggle.
- Stack rechts, oberstes Element oben, mit Quelle und Zielen; Log darunter.
- Commander-Schaden als Zahl neben dem Life, Tooltip zeigt von wem.

### Zustand

Ein `useGameStore` (Zustand-Lib) hält den letzten `state`, den offenen
`input`/`choice` und die Stops. Komponenten sind reine Funktionen davon. Das
Frontend kennt keine Regeln; es zeigt, was die Bridge erlaubt.

## 4. Decks

- Textliste → Forges `DeckRecognizer`/`DeckSerializer` parsen das
  Arena-/Archidekt-Format ("1 Sol Ring", optional Set-Code). Commander:
  Zeile im `Commander`-Abschnitt, sonst die erste legendäre Kreatur; bei
  Mehrdeutigkeit fragt die Lobby nach.
- Zuletzt verwendete Decks liegen als `.dck` in `~/.mtg-player/decks/`.
- Archidekt (V2): `GET https://archidekt.com/api/decks/{id}/` liefert JSON
  mit Kartennamen und Kategorien (inkl. "Commander"). Die Bridge baut daraus
  die Textliste, Rest wie oben.
- KI-Gegner: Forges Commander-Precons aus `res/`, weil von Forge auf
  KI-Tauglichkeit geprüft.

## 5. Fehlerbehandlung

- Parser-Fehler → `error` mit Zeilennummer, Lobby zeigt sie; kein Spielstart.
- Exception im Game-Thread (Forge-Bug bei einer Karte) → Bridge fängt sie,
  schickt `error` + Stacktrace ins Log, Spiel gilt als beendet. Kein
  Weiterspielversuch.
- WebSocket-Trennung (Tab zu, Reload) → Bridge hält Spiel und offenen
  `input`/`choice`; bei Reconnect kommt sofort `state` + die offene Frage.
  Der Game-Thread merkt nichts.
- Doppelte `answer` auf dieselbe `id` wird ignoriert.

## 6. Tests

- **Bridge (JUnit):** Serializer gegen handgebaute `GameView`-Objekte; der
  Sichtbarkeitsfilter ist der wichtigste Test (gegnerische Hand darf nie
  Namen enthalten). Auto-Pass-Logik als reine Funktion. Deck-Parser mit
  Beispiel-Listen.
- **Integrationstest ohne Browser:** 4 KI-Spieler, Commander, bis Spielende,
  ohne Exception. Läuft vor jedem Merge.
- **Frontend (Vitest):** Store-Reducer, Dialog-Komponenten mit festen
  `choice`-Payloads. Kein E2E gegen echtes Forge in V1.

## 7. Meilensteine

| # | Ergebnis |
|---|---|
| M0 | Java 17 + Maven installiert, Forge-Submodule gebaut, `bridge` startet und meldet "N Karten geladen" |
| M1 | Headless: 4 KI spielen ein Commander-Spiel zu Ende, Log auf stdout |
| M2 | Erstes spielbares Spiel: WS + Minimal-UI, Prio passen, Land legen, Zauber mit Auto-Mana, Ziele, Angriff/Block |
| M3 | Alle Dialogarten, Arena-Auto-Pass mit Stops, Volle Kontrolle, Mulligan |
| M4 | Lobby: Textlisten-Import, Precon-Auswahl, AI-Profile, Deck-Speicherung |
| M5 | Tisch-Feinschliff: Bilder-Cache, Hover-Detail, Zonen-Overlays, Log, Commander-Schaden, Shortcuts |
| M6 | Archidekt-URL |

M0–M2 tragen das Risiko (Forge-Anbindung, Threading). Ab M2 ist der Rest
Fleißarbeit.

## Offene Annahmen

- Forges aktueller Release-Tag baut mit Java 17 und Maven ohne Sonderschritte.
  Wird in M0 geprüft; die konkreten Interface-Namen (`IGuiGame`,
  `HostedMatch`, `InputQueue`) werden dort gegen den echten Stand verifiziert.
- Scryfall liefert für alle Forge-Set-Codes ein Bild; für Forge-eigene Codes
  (Custom-Sets) gibt es einen Platzhalter.
- UI-Texte auf Deutsch, Kartentexte auf Englisch.
