# Kartenaufzeichnung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Je Partie festhalten, was jede Karte eines eigenen Decks getan hat – damit „hat in deinen Partien nie gewirkt" ein belegter Schnittgrund wird und Kevin die Zahlen selbst nachlesen kann.

**Architecture:** Der `MatchRecorder` zählt während der Partie je **Karten-Id** mit (die Namen in `CardView` sind bei verdeckten Karten unzuverlässig) und löst die Ids beim Spielende über `Player.getAllCards()` zu echten Namen und Endzonen auf. Das Ergebnis geht als eigene Datei je Partie nach `~/.mtg-player/cards/<id>.json` – `MatchRecord` und `matches.json` bleiben unverändert. Eine Auswertung je Deck liest diese Dateien und speist Tabelle und Schnittgründe.

**Tech Stack:** Java 21 (Records, Jackson, JUnit 5), Forge-Ereignisbus, React 18 + TypeScript, Zustand, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-25-kartenaufzeichnung-design.md` – bei Widersprüchen gilt die Spec.

## Global Constraints

- **Niemals im Hauptbaum `/home/kevin/projects/MTG-Player` Maven laufen lassen oder kompilieren.** Dort läuft Kevins Bridge als `mvn -q compile exec:java` aus `bridge/target/classes`; ein Neuübersetzen unter der laufenden JVM schießt seine Partie mit `NoSuchMethodError` ab. Java-Arbeit passiert im Worktree `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/kampf` (enthält `forge`-Symlink und `bridge/assets/forge.profile.properties`); vorher `git -C <worktree> checkout --detach <aktueller main-HEAD>`, danach dort committen und im Hauptbaum `git cherry-pick <hash>`. Dort scheitert `git add -A` an einem Submodul/Symlink-Konflikt – mit expliziten Pfaden stagen.
- Niemals `mvn clean`. Maven mit `timeout 590`, Tool-Timeout 600000 ms. Vor jedem Maven-Lauf prüfen: `ps -eo args= | grep -c '[c]lassworlds'` muss `1` oder `0` sein (die `1` ist Kevins Bridge).
- Kein `pkill`; Ports 8080/8081 gehören Kevins Bridge. Vite-Dev-Server für Screenshots auf **5199**, am Ende über die eigene PID beenden.
- **Tests dürfen nie nach `~/.mtg-player` schreiben** – Pfade kommen aus `ForgeBoot.dataDir()` (surefire setzt `mtgplayer.data` auf `target/test-data`), Dateitests benutzen `@TempDir`.
- `MatchRecord` und seine Formatversion 2 bleiben unverändert; alte Datensätze müssen gültig bleiben.
- Ein Sitz wird nur aufgezeichnet, wenn sein Deckname zu Kevins eigenen Decks gehört (Namen aus dem `DeckStore`). Precons und fremde Decks erzeugen keine Zeile.
- Commits deutsch, klein geschrieben, Präfix `bridge:` / `ui:` / `docs:` / `test:`. Trailer exakt `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, kein weiterer Co-Author.
- Kommentare deutsch und erklären das Warum, wie im Nachbarcode.

---

## Dateien

| Datei | Rolle |
|---|---|
| `bridge/src/main/java/mtgplayer/stats/CardLog.java` | Datenformat je Partie (Record) und Zusammenfassung je Name |
| `bridge/src/main/java/mtgplayer/stats/MatchRecorder.java` | zählt je Karten-Id mit, löst beim Spielende auf |
| `bridge/src/main/java/mtgplayer/stats/CardStore.java` | schreibt/liest/löscht `~/.mtg-player/cards/<id>.json` |
| `bridge/src/main/java/mtgplayer/stats/CardStats.java` | Auswertung je Deck über die gewerteten Partien |
| `bridge/src/main/java/mtgplayer/protocol/Messages.java` | Nachricht `cardStats` |
| `bridge/src/main/java/mtgplayer/server/Bridge.java` | Behandlung von `deckCards`, Löschweg, Übergabe an Vorschläge |
| `bridge/src/main/java/mtgplayer/decks/Suggestions.java` | neuer Schnittgrund aus eigenen Partien |
| `bridge/src/main/java/mtgplayer/match/HumanMatch.java`, `AiMatch.java` | reichen eigene Decknamen und die Kartensenke durch |
| `web/src/protocol.ts`, `web/src/store.ts` | Typen und Ablage |
| `web/src/components/DeckCards.tsx`, `Stats.tsx`, `web/src/styles.css` | Tabelle „Karten" |
| `web/fixtures/statboard-cards.json` | Fixture für den Screenshot |

---

### Task 1: Kartenbiografie aufzeichnen

**Files:**
- Create: `bridge/src/main/java/mtgplayer/stats/CardLog.java`
- Modify: `bridge/src/main/java/mtgplayer/stats/MatchRecorder.java`
- Create: `bridge/src/test/java/mtgplayer/stats/CardLogTest.java`

**Interfaces:**
- Produces:
  - `record CardLog(int v, String id, List<SeatCards> seats)` mit `int VERSION = 1`
  - `record CardLog.SeatCards(int seat, String deck, List<Card> cards)`
  - `record CardLog.Card(String name, Integer copies, Integer hand, Integer cast, Integer castTurn, Integer countered, Integer lost, String end)` – Zähler mit Wert 0 stehen als `null` in der Datei (Jackson `NON_NULL`)
  - neuer Konstruktor `MatchRecorder(Game, String source, Integer aiTimeout, Map<RegisteredPlayer,String> deckNames, Set<String> ownDecks, Consumer<MatchRecord> sink, Consumer<CardLog> cardSink)`; die bisherigen Konstruktoren delegieren mit leerem Set und `null`-Kartensenke
  - `MatchRecorder.cardLog()` gibt den fertigen `CardLog` zurück (für Tests und für Aufrufer ohne Senke), `null` vor dem Abschluss

- [ ] **Step 1: Worktree auf Stand bringen**

```bash
SCRATCH=/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad
git -C $SCRATCH/kampf checkout --detach $(git -C /home/kevin/projects/MTG-Player rev-parse HEAD)
```

- [ ] **Step 2: Die fehlschlagenden Tests schreiben**

`bridge/src/test/java/mtgplayer/stats/CardLogTest.java` prüft die Zusammenfassung ohne Forge-Partie, über die
Bau-Schnittstelle von `CardLog` (eine `merge`-Methode, die Zeilen je Name zusammenfasst):

```java
package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Zusammenfassung der Kartenzeilen (Spec 2026-09-25-kartenaufzeichnung §2). Vier Kopien derselben Karte
 *  werden eine Zeile: Zaehler addiert, castTurn der frueheste. */
class CardLogTest {

    private static CardLog.Card card(String name, int hand, int cast, Integer castTurn, String end) {
        return new CardLog.Card(name, 1, hand, cast, castTurn, 0, 0, end);
    }

    @Test
    void fasstKopienZusammen() {
        List<CardLog.Card> merged = CardLog.merge(List.of(
                card("Cultivate", 1, 1, 5, "graveyard"),
                card("Cultivate", 1, 0, null, "library")));
        assertEquals(1, merged.size());
        CardLog.Card c = merged.get(0);
        assertEquals(2, c.copies());
        assertEquals(2, c.hand());
        assertEquals(1, c.cast());
        assertEquals(5, c.castTurn(), "frueheste Wirkung zaehlt");
    }

    @Test
    void behaeltEinzelneKarteUnveraendert() {
        List<CardLog.Card> merged = CardLog.merge(List.of(card("Nesting Dragon", 1, 0, null, "hand")));
        assertEquals(1, merged.size());
        assertEquals("hand", merged.get(0).end());
        assertNull(merged.get(0).castTurn());
    }

    @Test
    void sortiertNachNamen() {
        List<CardLog.Card> merged = CardLog.merge(List.of(
                card("Zuran Orb", 1, 0, null, "library"), card("Cultivate", 1, 0, null, "library")));
        assertEquals(List.of("Cultivate", "Zuran Orb"), merged.stream().map(CardLog.Card::name).toList());
    }

    @Test
    void ohneZeilenLeereListe() {
        assertTrue(CardLog.merge(List.of()).isEmpty());
    }
}
```

Dazu ein Szenentest über den echten Recorder in `bridge/src/test/java/mtgplayer/stats/CardRecordingTest.java`,
der eine kleine Partie aufsetzt (Aufbau wie `bridge/src/test/java/mtgplayer/ai/GoadTest.java`: `Scene.of(...)`,
Karten in Zonen legen, `s.setPhase(...)`, `s.step(n)`) und prüft:
- eine vom Sitz gewirkte Karte trägt `cast >= 1` und einen `castTurn`,
- eine Karte, die in der Hand liegen bleibt, trägt `end: "hand"` und `cast` null,
- eine Karte, die die ganze Partie in der Bibliothek liegt, taucht mit `end: "library"` auf,
- ein Sitz mit einem Deck, das **nicht** in `ownDecks` steht, erzeugt keine `SeatCards`,
- ein Spielstein taucht in keiner Zeile auf.

Lies vor dem Schreiben `GoadTest.java` und `MeldTitaniaTest.java`, um den Szenen-Aufbau und die
`Scene`-Hilfen zu übernehmen; erfinde keinen eigenen Rahmen.

- [ ] **Step 3: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='CardLogTest,CardRecordingTest' -DfailIfNoSpecifiedTests=false test
```

Erwartet: „cannot find symbol: class CardLog".

- [ ] **Step 4: `CardLog` schreiben**

Record wie oben, dazu `public static List<Card> merge(List<Card> rows)`: gruppiert nach Name (Reihenfolge
egal, Ausgabe nach Name sortiert), addiert `copies`/`hand`/`cast`/`countered`/`lost`, nimmt den kleinsten
`castTurn` (null, wenn keiner), und als `end` die Zone der Kopie, die zuletzt in der Eingabe steht. Zähler mit
Wert 0 werden zu `null`, damit die Datei nicht mit Nullen voll läuft.

- [ ] **Step 5: Recorder erweitern**

- Neues Feld je Sitz: `Map<Integer, CardTally> byCardId` (Id → Zähler plus zuletzt gesehener Name), nur
  angelegt, wenn der Sitz aufgezeichnet wird (`ownDecks.contains(seat.deckName())`).
- In `onCardChangeZone`: Bibliothek → Hand erhöht `hand` beim Besitzer; Spielfeld → Friedhof/Exil erhöht
  `lost`. Die vorhandenen Zähler bleiben unverändert – die neue Zählung hängt sich daneben.
- In `onSpellCast`: `cast` erhöhen, `castTurn` setzen, wenn noch leer (`Math.max(1, turns)`).
- In `onLandPlayed`: `cast` erhöhen (Länder werden nicht gewirkt, zählen hier aber als gespielt) und
  `castTurn` setzen.
- In `onSpellRemovedFromStack` (dem Zweig, der `spellsCountered` zählt): `countered` erhöhen.
- In `finish()`, **bevor** der Datensatz gebaut wird: für jeden aufgezeichneten Sitz über
  `player.getAllCards()` laufen, je `Card` Name und Endzone (`card.getZone()`/`getZone().getZoneType()`,
  kleingeschrieben) festhalten und mit den Id-Zählern verbinden; Ids ohne Treffer behalten den zuletzt
  gesehenen Namen und bekommen `end: "none"`. Spielsteine (`card.isToken()`) fallen raus. Danach
  `CardLog.merge(...)` je Sitz.
- Der fertige `CardLog` geht an `cardSink` (falls gesetzt) und steht über `cardLog()` bereit. Wie beim
  Datensatz gilt: eine abgebrochene, abgestürzte oder zugedeckelte Partie liefert **keinen** `CardLog` –
  dieselbe Bedingung wie für `counted`.
- Alles in denselben `try/catch (RuntimeException)`-Rahmen wie die übrigen Zähler; ein Fehler erhöht
  `counterFailures` und darf die Partie nie stören.

- [ ] **Step 6: Tests laufen lassen – jetzt grün**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='CardLogTest,CardRecordingTest' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

- [ ] **Step 7: Rückwirkung prüfen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.stats.*Test' -DfailIfNoSpecifiedTests=false test
cd <worktree> && git add bridge/src/main/java/mtgplayer/stats bridge/src/test/java/mtgplayer/stats
git commit -m "bridge: kartenbiografie je partie aufzeichnen

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 2: Ablage und Löschweg

**Files:**
- Create: `bridge/src/main/java/mtgplayer/stats/CardStore.java`
- Create: `bridge/src/test/java/mtgplayer/stats/CardStoreTest.java`
- Modify: `bridge/src/main/java/mtgplayer/match/HumanMatch.java`, `bridge/src/main/java/mtgplayer/match/AiMatch.java`, `bridge/src/main/java/mtgplayer/sparring/SparringRun.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`

**Interfaces:**
- Consumes: `CardLog` aus Task 1.
- Produces: `CardStore(Path dir)`, `CardStore.standard()`, `void write(CardLog)`, `Optional<CardLog> read(String id)`, `void delete(String id)`.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`CardStoreTest` mit `@TempDir`: schreiben und wieder lesen ergibt dieselben Zeilen; `read` auf eine
unbekannte Id liefert ein leeres Optional; eine kaputte Datei liefert ein leeres Optional statt einer
Ausnahme (und schreibt eine Notiz über `CrashLog.note`, wie `MatchStore` es tut); `delete` entfernt die
Datei und ist auf eine unbekannte Id ein Nichts; zwei Schreibvorgänge nacheinander überschreiben sauber.
Sieh dir `MatchStoreTest` an und übernimm dessen Aufbau.

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=CardStoreTest -DfailIfNoSpecifiedTests=false test
```

- [ ] **Step 3: `CardStore` schreiben**

Atomar schreiben wie `MatchStore` (eindeutige Temp-Datei, `Files.move` mit `ATOMIC_MOVE`), Verzeichnis bei
Bedarf anlegen, `standard()` nimmt `ForgeBoot.dataDir().resolve("cards")`. Dateiname ist die Partie-Id; die
Id muss vor der Verwendung als Dateiname geprüft werden (nur `[A-Za-z0-9_-]`, sonst nicht schreiben und eine
Notiz hinterlassen) – eine Id aus fremder Quelle darf nie in einen Pfad wandern.

- [ ] **Step 4: Aufrufer verbinden**

- `HumanMatch` und `AiMatch` bekommen die eigenen Decknamen und die Kartensenke durchgereicht und geben sie
  an den neuen Recorder-Konstruktor. Die eigenen Decknamen sind die Namen aus dem `DeckStore`
  (`DeckStore.standard()` im Sparring-Kindprozess, der vorhandene Store in der Bridge) – such im jeweiligen
  Aufrufer nach der Stelle, an der die Decks aufgelöst werden, und nimm den Store, den es dort schon gibt.
- Der Löschweg der Bridge (`deleteMatch`) löscht zusätzlich die Kartendatei.
- „nicht gewertet" (`setMatchCounted`) löscht **nichts** – die Datei bleibt liegen, die Auswertung überspringt
  ungewertete Partien.

- [ ] **Step 5: Tests laufen lassen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.stats.*Test,mtgplayer.server.*Test,mtgplayer.sparring.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
cd <worktree> && git add bridge/src && git commit -m "bridge: kartendaten je partie ablegen und mit der partie loeschen

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 3: Auswertung je Deck

**Files:**
- Create: `bridge/src/main/java/mtgplayer/stats/CardStats.java`
- Create: `bridge/src/test/java/mtgplayer/stats/CardStatsTest.java`

**Interfaces:**
- Consumes: `CardLog`, `CardStore`, `MatchRecord`.
- Produces:
  - `record CardStats(int games, int withCardData, boolean enough, List<Card> cards)`
  - `record CardStats.Card(String name, String imageKey, String manaCost, Integer cmc, int handGames, int castGames, Double avgCastTurn, int stuckGames, int neverDrawnGames, int counteredGames, int lostGames)`
  - `static CardStats of(String deck, List<MatchRecord> matches, CardStore store)`
  - `static final int MIN_GAMES = 5`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`CardStatsTest` baut Partien und Kartendateien von Hand (kein Forge-Spiel nötig, `ForgeBoot.init()` nur für
`imageKey`/`manaCost`) und prüft:
- zwei Partien, in denen eine Karte je einmal auf der Hand war und einmal gewirkt wurde → `handGames` 2,
  `castGames` 1, `avgCastTurn` aus dem einen Wert,
- eine ungewertete Partie (`counted: false`) zählt weder in `games` noch in die Karten,
- eine Partie ohne Kartendatei senkt `withCardData`, nicht `games`,
- `stuckGames` zählt nur Partien mit Hand **ohne** Wirkung, `neverDrawnGames` nur Partien ohne Hand,
- Grundländer (`Forest`) tauchen nicht in `cards` auf,
- unter `MIN_GAMES` Partien mit Daten ist `enough` falsch, die Karten kommen trotzdem mit,
- ein Deckname mit `//` findet auch Datensätze, die ihn mit `__` tragen (alte Datensätze, siehe
  `matchStats.ts`/`deckKey`) – such im vorhandenen Code nach der Normalisierung und benutze dieselbe.

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=CardStatsTest -DfailIfNoSpecifiedTests=false test
```

- [ ] **Step 3: `CardStats` schreiben**

Über die gewerteten Partien des Decks: Kartendatei lesen, den Sitz mit diesem Deck nehmen, je Kartenname die
Partien zählen. `imageKey`, `manaCost` und `cmc` kommen aus Forges Kartendatenbank
(`FModel.getMagicDb().getCommonCards().getCard(name)`; unbekannte Karte → Felder bleiben leer, die Zeile
bleibt). Grundländer (`Plains`, `Island`, `Swamp`, `Mountain`, `Forest`) fallen raus. Sortiert wird nach
`stuckGames` absteigend, dann `handGames` absteigend, dann Name – die Reihenfolge, die das UI zuerst zeigt.

- [ ] **Step 4: Tests laufen lassen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.stats.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
cd <worktree> && git add bridge/src && git commit -m "bridge: kartenauswertung je deck ueber die gewerteten partien

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 4: Protokoll `deckCards`

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Modify: `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java` (oder eine neue Testklasse im selben Muster)

**Interfaces:**
- Produces: `{"type":"cardStats","deck":…,"games":…,"withCardData":…,"enough":…,"cards":[…]}`; Anfrage `{"type":"deckCards","deck":"<Name>"}`; unbekanntes Deck → `error` „Kartenauswertung <name>: unbekanntes Deck".

- [ ] **Step 1: Test schreiben, der die Antwort über eine echte Bridge prüft**

Nach dem Muster von `BridgeSuggestCardsTest` (dort steht, wie eine Test-Bridge mit eingesetzten Stores
gebaut wird): eine Partie mit Kartendatei vorbereiten, `deckCards` schicken, Antwort prüfen (Typ, Deckname,
`games`, eine erwartete Karte). Dazu der Fehlerfall mit unbekanntem Deck.

- [ ] **Step 2: Test laufen lassen – er muss scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.server.*Test' -DfailIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Nachricht und Behandlung schreiben**

`Messages.CardStatsMsg` wie `Messages.DeckAnalysisMsg` aufgebaut; in `Bridge.handle` ein
`case "deckCards" -> deckCards(msg.path("deck").asText())`, die Methode im Muster von `analyzeDeck`
(Hintergrund-Task `"deck-cards"`, Deckauflösung über `forAnalysis`, Fehlertext wie oben, `catch
(RuntimeException)` mit `printStackTrace`). Der `CardStore` wird wie die anderen Stores als Feld gehalten und
über den Test-Konstruktor einsetzbar gemacht.

- [ ] **Step 4: Tests laufen lassen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.server.*Test,mtgplayer.protocol.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
cd <worktree> && git add bridge/src && git commit -m "bridge: nachricht deckCards liefert die kartenauswertung

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 5: Schnittgrund aus eigenen Partien

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/decks/Suggestions.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Modify: `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java`

**Interfaces:**
- Consumes: `CardStats` aus Task 3.
- Produces: `Suggestions.of(Deck deck, List<String> roles, Integer bracket, Edhrec.Page page, Map<String, CardStats.Card> own)` – `own` darf leer sein; die bisherige Signatur bleibt als Überladung mit leerer Map erhalten, damit vorhandene Tests weiter gelten.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

In `SuggestionsTest`: Eine Karte des Decks, die laut Kartenauswertung in mindestens drei Partien auf der Hand
war und **nie** gewirkt wurde, wird als Schnitt gewählt – auch dann, wenn eine andere Karte nach den
bisherigen Regeln (kein EDHREC-Eintrag, teurer) vorne läge. Die Begründung nennt die Zahlen:
„in 9 Partien 6× auf der Hand, nie gewirkt". Zweiter Test: mit weniger als drei Hand-Partien greift die alte
Rangfolge unverändert. Dritter Test: leere Kartenauswertung ändert nichts (Gegenprobe gegen Regression).

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=SuggestionsTest -DfailIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Rangfolge erweitern**

Neue erste Stufe in der Schnitt-Rangfolge (die bestehenden Stufen rücken nach hinten): Karte war in
mindestens **drei** Partien auf der Hand (benannte Konstante) und hat `castGames == 0`. Begründung genau in
der Form „in {N} Partien {M}× auf der Hand, nie gewirkt", wobei N die Partien mit Kartendaten sind und M die
`handGames`. Der Rest der Begründungslogik bleibt unverändert; zwei Gründe werden weiterhin mit „und"
verbunden. Die Bridge übergibt beim Beantworten von `suggestCards` die Kartenauswertung des Decks, aber nur
wenn `enough` gilt – sonst eine leere Map.

- [ ] **Step 4: Tests laufen lassen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.decks.*Test,mtgplayer.server.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
cd <worktree> && git add bridge/src && git commit -m "bridge: schnittgrund aus den eigenen partien sticht die edhrec-gruende

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 6: Tabelle „Karten" im Statistik-Board

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/store.ts`
- Create: `web/src/components/DeckCards.tsx`, `web/src/deckCards.ts`, `web/src/deckCards.test.ts`
- Modify: `web/src/components/Stats.tsx`, `web/src/styles.css`
- Create: `web/fixtures/statboard-cards.json`

**Interfaces:**
- Consumes: Antwort `cardStats` aus Task 4.
- Produces: Komponente `DeckCards` (Props `{ deck: string }`), reine Funktionen `sortCards(cards, mode)` und `cardLine(card, withCardData)` in `deckCards.ts`.

Alles im Hauptbaum unter `web/`, kein Java, kein Maven.

- [ ] **Step 1: Typen und Ablage**

`protocol.ts`: `CardStat` und `CardStatsMsg` passend zur Antwort (alle Zähler Pflicht, `avgCastTurn`,
`imageKey`, `manaCost`, `cmc` optional); die ausgehende Nachricht `{ type: "deckCards", deck }` in
`Outbound`. `store.ts`: `cardStats: Record<string, CardStatsMsg>` und offene Anfragen im selben Muster wie
`suggestCards` (der `error`-Zweig muss die offene Anfrage löschen – sonst hängt der Knopf, siehe den
Kommentar im Store).

- [ ] **Step 2: Die fehlschlagenden Tests schreiben**

`deckCards.test.ts` prüft `sortCards` in allen drei Modi („Handlungsbedarf", „Name", „am häufigsten
gewirkt") – jeweils mit vertauschter Eingabereihenfolge, damit der Test nicht die Eingabe nachbetet – und
`cardLine` für: nie gewirkt, mehrfach gewirkt mit Durchschnittszug, nie gezogen, liegen geblieben.

- [ ] **Step 3: Tests laufen lassen – sie müssen scheitern**

```bash
cd /home/kevin/projects/MTG-Player/web && npx vitest run src/deckCards.test.ts
```

- [ ] **Step 4: Modul und Komponente schreiben**

`DeckCards.tsx`: Abschnitt „Karten" unter den Kartenvorschlägen, Laden erst auf Klick („Karten laden"),
danach Kopfzeile „Karten · 9 von 12 gewerteten Partien mit Aufzeichnung" und die Tabelle (Miniatur über
`CardImage`, Name, Manakosten, die Sätze aus `cardLine`). Unter der Untergrenze (`enough: false`) statt der
Tabelle: „Noch zu wenige Partien mit Aufzeichnung (3 von 5 nötig)". Umschalter für die Sortierung. Ein
„neu laden"-Knopf wie bei den Vorschlägen. Fehlerfall: Knopf wieder benutzbar.

- [ ] **Step 5: Einhängen, Styles, Fixture**

In `Stats.tsx` unter den Kartenvorschlägen einhängen. Styles ans Ende von `styles.css` im Stil der
Nachbarabschnitte, ohne neue Farbvariablen. `web/fixtures/statboard-cards.json` mit einer `cardStats`-Antwort
für „Krenko Goblins": mindestens eine nie gewirkte Karte mit hohen `stuckGames`, eine oft gewirkte mit
`avgCastTurn`, eine nie gezogene. Echte Kartennamen und `imageKey`-Schreibweise wie in
`fixtures/statboard-suggestions.json`, keine erfundenen Werte.

- [ ] **Step 6: Prüfen, Screenshot, committen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
(npx vite --port 5199 --strictPort > /tmp/vite-5199.log 2>&1 &) && sleep 5
node scripts/shot-stats.mjs http://localhost:5199 /tmp/karten.png --deck="Krenko Goblins" --cards
```

`shot-stats.mjs` kennt das Flag `--cards` noch nicht; ergänze es nach dem Vorbild von `--suggest` (Knopf
klicken, dann die Fixture einspielen). Das PNG mit dem Read-Tool **ansehen**: Tabelle lesbar, Zahlen
stimmig, kein Überlauf, Bilder da. Danach den Vite-Prozess über seine PID beenden.

```bash
cd /home/kevin/projects/MTG-Player && git add web/src web/fixtures web/scripts
git commit -m "ui: kartentabelle je deck im statistik-board

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Abschluss

- Ledger-Zeile in `.superpowers/sdd/progress.md`, dann `git push`.
- Kevin braucht einen Bridge-Neustart; die Aufzeichnung beginnt mit der nächsten Partie, alte Partien
  bekommen keine Kartendaten.

---

### Task 7: Zeitachse zählt eigene Züge (Formatversion 3)

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/stats/MatchRecord.java` (VERSION 2 → 3, Kommentar an `TurnPoint`)
- Modify: `bridge/src/main/java/mtgplayer/stats/MatchRecorder.java` (`notePoint`-Aufruf)
- Modify: `bridge/src/test/java/mtgplayer/stats/` (Test für die neue Zählweise)
- Modify: `web/src/components/MatchTimeline.tsx`, `web/src/protocol.ts` (Kommentar zur Bedeutung je Version)

**Interfaces:**
- Consumes: nichts Neues.
- Produces: `TurnPoint.turn` bedeutet ab `v: 3` den eigenen Zug des Sitzes.

- [ ] **Step 1: Test schreiben, der die Zählweise unterscheidet**

Ein Szenentest mit **mehr als zwei Sitzen**, in dem mindestens zwei eigene Züge eines Sitzes vergehen: die
Zeitachse dieses Sitzes muss `turn` 1, 2, 3 … tragen, nicht 1, 4, 7 … Ohne mehr als zwei Sitze fallen beide
Zählweisen zusammen und der Test würde nichts beweisen. Zusätzlich: `MatchRecord.VERSION` ist 3, und ein
Datensatz trägt `v: 3`.

- [ ] **Step 2: Test laufen lassen – er muss scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.stats.*Test' -DfailIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Umstellen**

`MatchRecorder.onTurnBegan` ruft `notePoint(turnOwner, turnOwner.ownTurns)` statt `e.turnNumber()` – der
Zähler ist an dieser Stelle bereits erhöht, `landsByTurn` benutzt ihn direkt darüber genauso.
`MatchRecord.VERSION` auf 3, Kommentar an `TurnPoint` und am Feld: ab v3 eigener Zug, darunter Partiezug.
**Nicht** anfassen: `turns`, `eliminatedTurn` und alle Prüfungen der Form `v >= 2`.

- [ ] **Step 4: Client beschriften**

`MatchTimeline.tsx` bekommt die Formatversion des Datensatzes und beschriftet die x-Achse: ab v3
„Eigener Zug", darunter „Partiezug (alle Sitze)". Der Kommentar an `TurnPoint` in `protocol.ts` sagt
dasselbe. Kein Umrechnen alter Datensätze.

- [ ] **Step 5: Prüfen und committen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.stats.*Test,mtgplayer.server.*Test' -DfailIfNoSpecifiedTests=false test
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

Danach zwei Commits (Bridge im Worktree mit Cherry-Pick, Client im Hauptbaum), Präfixe `bridge:` und `ui:`.
