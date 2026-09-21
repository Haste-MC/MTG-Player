# Übernehmen und Löschen

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Archidekt-Import übernimmt gleichnamige lokale Decks ohne Tag (Marke „übernehmen"); eigene Decks lassen sich in der Kachel löschen (Zwei-Klick-Bestätigung).

**Architecture:** Bridge: `DeckSource.uniqueName` → Übernahme bei untagged Kollision; `DeckStore.delete`; `deleteDeck`-Nachricht. Web: vierter `EntryState`, Lösch-Knopf mit Bestätigungszustand in `DeckPicker`, `dropMissing` bei jeder Deckänderung in `Lobby`. Spec: `docs/superpowers/specs/2026-09-21-uebernehmen-loeschen-design.md`.

**Tech Stack:** Java 21/JUnit 5, React 18/TS/Vitest/Playwright.

## Global Constraints

- Branch `feature/uebernehmen-loeschen`. Commits deutsch, Kleinschreibung, Präfix `bridge:`/`ui:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- Kevins Bridge (8080/8081) nicht anfassen; nie `mvn clean`; ein Maven-Prozess gleichzeitig; eigene Test-Bridge 18086/18097, PID am Ende killen (nicht `pkill -f java`).
- Web: `npm test`, `npm run build` grün; Screenshots unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/loeschen/`, mit Read ansehen.
- Übernahme nur bei Kollision mit einem Deck **ohne** `archidekt:`-Tag; mit anderem Tag weiterhin `"<Name> (<Id>)"`. Zustand `"übernehmen"` ist nicht vorausgewählt. Löschen nur gespeicherte Decks, Bestätigung 4 s.

---

### Task 1: Bridge – Übernahme und `deleteDeck`

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/decks/DeckSource.java` (`uniqueName` → `targetName`), `DeckStore.java` (`delete`), `bridge/src/main/java/mtgplayer/server/Bridge.java` (`case "deleteDeck"`)
- Test: `bridge/src/test/java/mtgplayer/decks/DeckSourceTest.java`, `DeckStoreTest.java`, `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`

- [ ] **Step 1: Failing Tests** – `DeckSourceTest`:

```java
    @Test
    void importArchidektUebernimmtGleichnamigesDeckOhneTag(@TempDir Path dir) throws Exception {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        // lokales Deck ohne Tag unter dem Archidekt-Namen (alter Textimport)
        store.save("Fun With Fungus", Precons.load("Abzan Armor [TDC] [2025]"));
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        assertEquals("1", store.archidektId("Fun With Fungus"));
        assertEquals("Thelon of Havenwood", store.load("Fun With Fungus").getCommanders().get(0).getName());
    }
```

(der bestehende Kollisionstest mit Suffix muss das lokale Deck jetzt mit einem **anderen** Tag `archidekt:99` anlegen – anpassen). `DeckStoreTest`:

```java
    @Test
    void deleteEntferntDateiUndMeldetUnbekannt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("Weg damit", Precons.load("Abzan Armor [TDC] [2025]"));
        store.delete("Weg damit");
        assertEquals(List.of(), store.names());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.delete("Weg damit"));
        assertTrue(e.getMessage().contains("Weg damit"));
    }
```

`BridgeEndToEndTest`: `{"type":"deleteDeck","name":"gibt es nicht"}` → `error` beginnend mit `Löschen gibt es nicht:`.

- [ ] **Step 2: Rot**, **Step 3: Implementieren** – `DeckSource`: `uniqueName` umbenennen in `targetName(name, id)`: bei Kollision `String tag = store.archidektId(n)`; `tag == null` → `return n` (Übernahme unter dem gespeicherten Namen), sonst Suffix; Javadoc anpassen. `DeckStore.delete(name)`: Datei per `readDisplayName`-Suche finden, `Files.delete`, unbekannt → `IllegalArgumentException("unbekanntes Deck: " + name)`, IO-Fehler → `IllegalStateException`. `Bridge`: `case "deleteDeck" -> runBackgroundTask("delete", () -> { try { store.delete(name); ws.send(lobby) } catch (RuntimeException e) { e.printStackTrace(); ws.send(new Messages.ErrorMsg("Löschen " + name + ": " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()))); } })`.
- [ ] **Step 4: Grün** – benannte Klassen, dann `mvn -q test` komplett (~190 Tests).
- [ ] **Step 5: Commit** – `bridge: archidekt-import uebernimmt gleichnamiges deck ohne tag; deleteDeck`.

---

### Task 2: Web – Marke „übernehmen", Lösch-Knopf, Picks bereinigen, Screenshots, README

**Files:**
- Modify: `web/src/protocol.ts` (Outbound `deleteDeck`), `web/src/archidektPlan.ts` (+test), `web/src/components/DeckPicker.tsx`, `web/src/components/Lobby.tsx`, `web/src/styles.css`, `web/scripts/shot-lobby.mjs` (`--click=<selector>`), `web/fixtures/lobby.json`, `web/fixtures/archidekt-decks.json`, `README.md`

- [ ] **Step 1: Failing Tests** – `archidektPlan.test.ts`: Eintrag ohne Id-Treffer, aber eigenes Deck gleichen Namens ohne `archidekt` → `"übernehmen"`; gleicher Name **mit** `archidekt` anderer Id → `"neu"`; `defaultSelection` enthält „übernehmen" nicht; `updateAllIds` ebenfalls nicht.
- [ ] **Step 2: Implementieren**
  - `EntryState |= "übernehmen"`; `classify`: nach dem Id-Treffer-Check `own.some((d) => d.name === e.name && d.archidekt == null) ? "übernehmen" : "neu"`; `updateAllIds` filtert auf `aktuell`/`geändert` explizit.
  - `DeckPicker`: `STATE_CLASS["übernehmen"] = "uebernehmen"`, Marke mit `title="ersetzt das gleichnamige lokale Deck"`. Lösch-Knopf in „Eigene Decks": `button.delete` (`🗑`, `aria-label="Deck löschen"`), Zustand `confirmDelete?: string` (Deckname) mit 4-s-Timer (`useEffect` mit `setTimeout`, Cleanup) und Reset bei Klick außerhalb (Handler auf dem Panel-Backdrop bzw. `mousedown` auf `document` mit Cleanup); im Bestätigungszustand Text „Wirklich löschen?" und Klasse `confirm`; zweiter Klick → `setDeleting(name)`, `send({ type: "deleteDeck", name })`; `deleting` endet mit `decks`-Änderung (Status „Deck gelöscht.") oder Warn-Log (rot) – dieselbe Mechanik wie `syncing` (gemeinsam in einen kleinen `useOp`-Helfer ziehen, wenn es die Datei klarer macht; sonst parallel). Während `deleting` Knopf disabled „löscht …".
  - `Lobby.tsx`: Effekt auf `[precons, decks]` nach dem Laden: `setHuman((h) => dropMissing({ human: h, ais }, precons, decks).human)` und analog `ais` (einmal über eine kleine Hilfsfunktion, damit nicht zwei setState mit veraltetem `ais`: `const cleaned = dropMissing({ human, ais }, precons, decks); if (JSON.stringify(cleaned) !== JSON.stringify({ human, ais })) { setHuman(cleaned.human); setAis(cleaned.ais); }`).
  - CSS: `.state-badge.uebernehmen` (orange-soft: `rgba(255,170,100,.2)`/`#ffd2a8`), `.deck-card .delete` (Pille links unten oder neben Resync rechts oben: Resync bleibt rechts oben, Löschen rechts unten `bottom: 6px; right: 6px`), `.deck-card .delete.confirm { background: rgba(229,72,77,.25); border-color: var(--red); color: #fff; }`.
  - `shot-lobby.mjs`: `--click=<selector>` (wiederholbar, klickt nach `--tab`/`--apply` den ersten Treffer, 200 ms Wartezeit).
  - Fixtures: `lobby.json` drittes eigenes Deck `"Koma Ramp"` ohne `archidekt` (Commander Koma, World-Eater, `imageKey` `c:Koma, World-Eater|KHM|1`); `archidekt-decks.json` neuer Eintrag Id 55555 Name `"Koma Ramp"`.
  - README: Sätze zu „übernehmen" (ersetzt gleichnamiges Deck ohne Tag; mit anderem Tag Suffix) und Löschen (Zwei-Klick, nur eigene Decks, gewählter Sitz wird leer); Protokollzeile `deleteDeck`.
- [ ] **Step 3: Tests/Build/Screenshots** – `npm test`, `npm run build`; eigene Bridge 18086/18097; `node scripts/shot-lobby.mjs http://127.0.0.1:18086 <scratch>/loeschen/saved-confirm.png --open-panel --tab=saved --click=".deck-card .delete"` und `archidekt-uebernehmen.png --open-panel --tab=archidekt --apply=fixtures/archidekt-decks.json`; ansehen (Bestätigungsknopf rot lesbar, Marke orange, nichts überlappt).
- [ ] **Step 4: Commit** – `ui: marke uebernehmen, deck loeschen mit bestaetigung, sitze bereinigen, readme`.
