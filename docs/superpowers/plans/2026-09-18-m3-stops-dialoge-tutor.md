# M3: Tutor-Suche, Prompt-Sequenz, Phasen-Stops, Kampfschaden- und Listen-Dialoge – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die im ersten Spiel gefundenen Lücken schließen: Bibliothek durchsuchen funktioniert, veraltete Klicks werden verworfen, Phasen-Stops und "Volle Kontrolle" sind steuerbar, Kampfschaden/Mengen/Kartenlisten haben echte Dialoge, und die UI zeigt Kartendetails beim Hover.

**Architecture:** Alles bleibt in den bestehenden Schichten. Bridge: `WebGuiGame` bekommt eine Prompt-Sequenznummer aus Forges `InputQueue` (Observer), eine Stop-Konfiguration für `isUiSetToSkipPhase`, und drei neue Dialog-Kinds über den unveränderten `ChoiceBroker`. Tutor-Suchen laufen über Forges Listen-Dialog statt über Karten-Klicks (Pref `UI_SELECT_FROM_CARD_DISPLAYS=false`), und `Option` trägt jetzt Kartendetails, damit der Browser die Auswahl anzeigen kann. Frontend: Sequenznummer bei jeder Eingabe mitsenden, Phasenleiste mit Stops, neue Dialoge, Hover-Panel.

**Tech Stack:** wie M2 (Java 17, Forge 2.0.14, Jackson, Java-WebSocket, JUnit 5; React 18, TS, Vite, Zustand, Vitest).

## Global Constraints

- Forge-Submodule bleibt unverändert.
- Bestehende Protokoll-Felder und -Typen bleiben abwärtskompatibel; neue Felder sind optional (Jackson `NON_NULL`). Neue Nachrichten Browser → Bridge: `setStops`, `fullControl`. Neue Choice-Kinds: `damage`, `amount`, `cardlist`.
- Sequenznummer: `PromptSnap.seq` (int, ≥ 1) wechselt genau dann, wenn Forges aktuelles `Input`-Objekt wechselt (Observer auf `InputQueue`), nicht bei jedem Re-Push. `selectCard`, `selectPlayer`, `ok`, `cancel` tragen `seq`; die Bridge verwirft Eingaben mit falscher `seq` still (nur Log-Zeile, kein `error`). Fehlt `seq` in der Nachricht, wird sie akzeptiert (Abwärtskompatibilität, Tests).
- Stops-Semantik: `stops.own`/`stops.opp` = Phasen, in denen **angehalten** wird. `isUiSetToSkipPhase` liefert `true` für alle anderen. Defaults (Forges Desktop-Defaults): own `MAIN1, COMBAT_DECLARE_BLOCKERS, MAIN2`; opp `COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, END_OF_TURN`. `fullControl=true` = in jeder Phase anhalten **und** Auto-Pass-ohne-Aktionen aus (`YieldController.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "false")`).
- Threading wie M2: `broker.ask` nie auf dem UI-Thread; Eingaben über `invokeInEdtLater`; `answer` direkt auf dem Socket-Thread.
- Commit-Messages: deutsch, Kleinschreibung, Präfix `m3:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` (verbatim, unabhängig vom ausführenden Modell).
- Maven aus `bridge/`, Bash-Timeout 600000 ms, nie zwei Maven-Prozesse (`pgrep -f surefire`). Vitest/Build aus `web/`.

---

## Dateistruktur

```
bridge/src/main/java/mtgplayer/
├── forge/ForgeBoot.java                MODIFY: Pref UI_SELECT_FROM_CARD_DISPLAYS=false
├── protocol/Snapshot.java              MODIFY: PromptSnap.seq, PlayerSnap.highlighted, Snapshot.stops/fullControl
├── protocol/Messages.java              MODIFY: Option.detail/max/lethal/movable, Choice.amount/atLeastOne, Stops-Record
├── protocol/StateSerializer.java       MODIFY: cardSnap(...) public, PlayerSnap.highlighted
├── gui/WebGuiGame.java                 MODIFY: seq via InputQueue-Observer, Stops, neue Dialoge, Option-Details
├── gui/Stops.java                      NEU: Stop-Konfiguration (reine Datenklasse, testbar)
└── server/Bridge.java                  MODIFY: seq-Prüfung, setStops/fullControl
web/src/
├── protocol.ts                         MODIFY: neue Felder/Kinds/Outbound
├── dialogs.ts                          NEU: reine Validierungen für damage/amount/cardlist (Vitest)
├── dialogs.test.ts                     NEU
├── store.ts                            MODIFY: hover-Karte
├── components/Prompt.tsx               MODIFY: seq mitsenden
├── components/CardBox.tsx              MODIFY: seq mitsenden, hover setzen
├── components/PlayerZone.tsx           MODIFY: seq mitsenden, highlighted-Stil
├── components/PhaseBar.tsx             NEU: Phasenleiste mit Stops + Volle-Kontrolle-Toggle
├── components/ChoiceDialog.tsx         MODIFY: damage/amount/cardlist, Option-Details
├── components/CardDetail.tsx           NEU: Hover-Panel
├── components/Table.tsx                MODIFY: PhaseBar + CardDetail einbauen
└── styles.css                          MODIFY
```

---

### Task 1: Prompt-Sequenznummer und Tutor-Suche

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/forge/ForgeBoot.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/Snapshot.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java`
- Modify: `bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`
- Modify: `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/gui/WebGuiGameTest.java`, `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`

**Interfaces:**
- Produces:
  - `Snapshot.PromptSnap(String message, Integer card, String okLabel, String cancelLabel, boolean okEnabled, boolean cancelEnabled, int seq)`; `PromptSnap.EMPTY` hat `seq = 0`.
  - `Snapshot.PlayerSnap` bekommt am Ende `Boolean highlighted`.
  - `Messages.Option(int index, String label, Integer card, Integer player, Snapshot.CardSnap detail, Integer max, Integer lethal, Boolean movable)` + Convenience-Konstruktor `Option(int, String, Integer, Integer)`.
  - `Messages.Choice` bekommt `Integer amount, Boolean atLeastOne` (am Ende) + der bisherige 8-Arg-Konstruktor bleibt.
  - `StateSerializer.cardSnap(CardView cv, ViewContext ctx) : Snapshot.CardSnap` (public).
  - `WebGuiGame`: `int currentSeq()`, `void onInputChanged()` (paketprivat, bumpt seq + push), und die Eingabemethoden bekommen eine `seq`-Variante: `boolean onSelectCard(int cardId, boolean alt, Integer seq)`, `boolean onSelectPlayer(int playerId, Integer seq)`, `boolean onOk(Integer seq)`, `boolean onCancel(Integer seq)` – `false` = verworfen. Die alten Signaturen ohne `seq` bleiben und delegieren mit `null`.

- [ ] **Step 1: Tutor-Pref**

In `ForgeBoot.init()` ins `adjustPrefs`-Lambda ergänzen (nach `UI_SHOW_ACTIONABLE_HIGHLIGHTS`):

```java
            // Kartenwahl aus Bibliothek/Friedhof/Exil als Listen-Dialog statt Klick auf die Zone –
            // der Browser zeigt diese Zonen nicht als klickbare Panels (Tutor-Effekte hingen sonst)
            prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
```

- [ ] **Step 2: Records erweitern**

`Snapshot.java` – `PromptSnap` ersetzen:

```java
    public record PromptSnap(
            String message,
            Integer card,
            String okLabel,
            String cancelLabel,
            boolean okEnabled,
            boolean cancelEnabled,
            int seq) {

        public static final PromptSnap EMPTY = new PromptSnap("", null, "OK", "Cancel", false, false, 0);

        public PromptSnap withSeq(int newSeq) {
            return new PromptSnap(message, card, okLabel, cancelLabel, okEnabled, cancelEnabled, newSeq);
        }
    }
```

`PlayerSnap`: letztes Feld `boolean hasPriority` → danach `Boolean highlighted` ergänzen (Record-Feld anhängen; Konstruktor-Aufruf im Serializer anpassen).

`Messages.java` – `Option` und `Choice` ersetzen:

```java
    /**
     * detail: Kartendaten (sichtbarkeitsgefiltert) für Auswahlen, deren Karten nicht im Snapshot sind (Tutor).
     * max/lethal: für amount/damage. movable: für cardlist.
     */
    public record Option(int index, String label, Integer card, Integer player,
                         Snapshot.CardSnap detail, Integer max, Integer lethal, Boolean movable) {
        public Option(int index, String label, Integer card, Integer player) {
            this(index, label, card, player, null, null, null, null);
        }
    }

    /**
     * kind: one | many | order | confirm | number | text | ability | entities | reveal | damage | amount | cardlist
     * value der Antwort: Index (one/ability), Index-Liste (many/entities/order/cardlist), bool (confirm),
     * Zahl (number), String (text), Zahlen-Liste je Option (damage/amount). reveal erwartet keine Antwort.
     * amount/atLeastOne nur bei damage/amount.
     */
    public record Choice(String type, int id, String kind, String title, String message,
                         List<Option> options, int min, int max, Integer card, Integer amount, Boolean atLeastOne) {
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max, Integer card) {
            this("choice", id, kind, title, message, options, min, max, card, null, null);
        }
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max,
                      Integer card, Integer amount, Boolean atLeastOne) {
            this("choice", id, kind, title, message, options, min, max, card, amount, atLeastOne);
        }
    }
```

`ChoiceBroker.ask(...)` baut `new Messages.Choice(id, kind, title, message, options, min, max, card)` – unverändert. Zusätzlich eine Überladung `ask(String kind, String title, String message, List<Option> options, int min, int max, Integer card, Integer amount, Boolean atLeastOne)` ergänzen, die den Konstruktor mit `amount`/`atLeastOne` nutzt; die alte 7-Arg-Variante delegiert mit `null, null`.

`StateSerializer.java`: `private static Snapshot.CardSnap card(CardView cv, ViewContext ctx)` → `public static Snapshot.CardSnap cardSnap(CardView cv, ViewContext ctx)` umbenennen (alle internen Aufrufer anpassen). Im `player(...)`-Aufbau am Ende `ctx.highlighted().test(p) ? Boolean.TRUE : null` als neues letztes Argument.

`StateSerializerTest.promptUndSelectableWerdenDurchgereicht`: der `PromptSnap`-Konstruktor bekommt `, 1` als letztes Argument (seq).

- [ ] **Step 3: Failing Tests**

In `WebGuiGameTest` ergänzen:

```java
    @Test
    void seqWechseltNurBeiInputWechsel() {
        int s0 = gui.currentSeq();
        gui.showPromptMessage(null, "a", null);
        gui.updateButtons(null, "OK", "Cancel", true, true, true);
        assertEquals(s0, gui.currentSeq(), "re-push des gleichen Inputs bumpt nicht");
        gui.onInputChanged();
        assertEquals(s0 + 1, gui.currentSeq());
    }

    @Test
    void eingabenMitFalscherSeqWerdenVerworfen() {
        gui.onInputChanged();
        int seq = gui.currentSeq();
        assertTrue(gui.seqOk(null), "ohne seq: akzeptiert");
        assertTrue(gui.seqOk(seq));
        assertFalse(gui.seqOk(seq - 1));
        assertFalse(gui.onOk(seq - 1), "veraltetes ok wird verworfen");
        assertFalse(gui.onSelectCard(1, false, seq + 5), "seq aus der Zukunft wird verworfen");
    }

    @Test
    void optionenTragenKartendetailsWennSichtbar() throws Exception {
        // ohne GameView: detail bleibt null, aber die Struktur muss serialisierbar sein
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("x", 1, 1, List.of("a"), null, null));
        JsonNode c = nextChoice();
        assertFalse(c.get("options").get(0).has("detail"));
        gui.broker().answer(c.get("id").asInt(), Json.parse("0"));
        r.get(5, TimeUnit.SECONDS);
    }
```

Hinweis: `onOk(Integer)`/`onSelectCard(..., Integer)` geben ohne Controller ebenfalls `false` zurück; die Verwerfungslogik selbst wird über `seqOk` geprüft.

- [ ] **Step 4: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=WebGuiGameTest`
Expected: COMPILATION ERROR (`currentSeq`, `onInputChanged`, `seqOk` fehlen).

- [ ] **Step 5: WebGuiGame – seq, Observer, Option-Details**

In `WebGuiGame`:

Felder ergänzen:

```java
    private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(1);
    private java.util.Observer inputObserver;
    private forge.gamemodes.match.input.InputQueue observedQueue;
```

Methoden ergänzen:

```java
    public int currentSeq() {
        return seq.get();
    }

    /** Forges Input-Objekt hat gewechselt: neue Sequenz, damit veraltete Klicks verworfen werden. */
    void onInputChanged() {
        int s = seq.incrementAndGet();
        synchronized (this) {
            prompt = prompt.withSeq(s);
        }
        push();
    }

    boolean seqOk(Integer clientSeq) {
        return clientSeq == null || clientSeq == seq.get();
    }

    @Override
    public void setOriginalGameController(PlayerView player, IGameController gameController) {
        super.setOriginalGameController(player, gameController);
        if (observedQueue != null && inputObserver != null) {
            observedQueue.deleteObserver(inputObserver);
        }
        if (gameController instanceof forge.player.PlayerControllerHuman pch) {
            observedQueue = pch.getInputQueue();
            inputObserver = (o, arg) -> onInputChanged();
            observedQueue.addObserver(inputObserver);
        }
        applyFullControlPref();
    }

    /** M3 Task 2 füllt das; hier nur der Hook, damit Task 1 kompiliert. */
    void applyFullControlPref() { }
```

`showPromptMessage`/`updateButtons`: den `PromptSnap`-Konstruktor um `p.seq()` als letztes Argument ergänzen (beide Stellen). `afterGameEnd()`: `prompt = Snapshot.PromptSnap.EMPTY.withSeq(seq.get());`.

Eingaben mit seq (die alten Methoden bleiben und delegieren):

```java
    public boolean onSelectCard(int cardId, boolean alt, Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veralteter selectCard verworfen"); return false; }
        CardView cv = cardById(cardId);
        IGameController c = getGameController();
        if (cv == null || c == null) return false;
        if (!c.selectCard(cv, null, trigger(alt))) {
            flashIncorrectAction();
        }
        return true;
    }
    public void onSelectCard(int cardId, boolean alt) { onSelectCard(cardId, alt, null); }

    public boolean onSelectPlayer(int playerId, Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veralteter selectPlayer verworfen"); return false; }
        PlayerView pv = playerById(playerId);
        IGameController c = getGameController();
        if (pv == null || c == null) return false;
        c.selectPlayer(pv, trigger(false));
        return true;
    }
    public void onSelectPlayer(int playerId) { onSelectPlayer(playerId, null); }

    public boolean onOk(Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veraltetes ok verworfen"); return false; }
        IGameController c = getGameController();
        if (c == null) return false;
        c.selectButtonOk();
        return true;
    }
    public void onOk() { onOk(null); }

    public boolean onCancel(Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veraltetes cancel verworfen"); return false; }
        IGameController c = getGameController();
        if (c == null) return false;
        c.selectButtonCancel();
        return true;
    }
    public void onCancel() { onCancel(null); }
```

Option-Details: `options(...)` wird eine Instanzmethode (nicht mehr `static`), damit sie `mayView` nutzen kann:

```java
    private <T> List<Messages.Option> options(List<T> choices, FSerializableFunction<T, String> display) {
        List<Messages.Option> out = new ArrayList<>();
        if (choices == null) return out;
        ViewContext ctx = getGameView() == null ? null : new ViewContext(
                getLocalPlayers().isEmpty() ? null : getLocalPlayers().iterator().next(),
                this::mayView, c -> false, c -> false, e -> false, Snapshot.PromptSnap.EMPTY);
        int i = 0;
        for (T t : choices) {
            String label = display != null ? display.apply(t) : String.valueOf(t);
            Integer card = null;
            Integer player = t instanceof PlayerView pv ? pv.getId() : null;
            Snapshot.CardSnap detail = null;
            CardView cv = t instanceof CardView c ? c
                    : (t instanceof SpellAbilityView sav ? sav.getHostCard() : null);
            if (cv != null) {
                card = cv.getId();
                if (ctx != null) {
                    Snapshot.CardSnap snap = StateSerializer.cardSnap(cv, ctx);
                    detail = snap.faceDown() ? null : snap;
                }
            }
            out.add(new Messages.Option(i++, label, card, player, detail, null, null, null));
        }
        return out;
    }
```

Alle bisherigen Aufrufer von `options(...)` bleiben syntaktisch gleich.

- [ ] **Step 6: Bridge – seq durchreichen**

In `Bridge.handle` die vier Fälle ersetzen:

```java
            case "selectCard" -> ui(() -> gui.onSelectCard(msg.path("id").asInt(), msg.path("alt").asBoolean(false), seqOf(msg)));
            case "selectPlayer" -> ui(() -> gui.onSelectPlayer(msg.path("id").asInt(), seqOf(msg)));
            case "ok" -> ui(() -> gui.onOk(seqOf(msg)));
            case "cancel" -> ui(() -> gui.onCancel(seqOf(msg)));
```

und die Hilfsmethode:

```java
    private static Integer seqOf(JsonNode msg) {
        JsonNode s = msg.get("seq");
        return s == null || !s.isInt() ? null : s.asInt();
    }
```

- [ ] **Step 7: E2E erweitern**

In `BridgeEndToEndTest.lobbyStartKeepPrioConcede`, direkt nach dem Erhalt des `Keep`-Prompts (`mull`) und vor `send("{\"type\":\"ok\"}")`:

```java
        int keepSeq = mull.path("prompt").path("seq").asInt();
        assertTrue(keepSeq >= 1, "prompt.seq vorhanden");
        send("{\"type\":\"ok\",\"seq\":" + (keepSeq - 1) + "}"); // veraltet – muss ignoriert werden
        Thread.sleep(1500);
        send("{\"type\":\"requestState\"}");
        JsonNode still = await("state", n -> true, 10);
        assertEquals("Keep", still.path("prompt").path("okLabel").asText(), "veraltetes ok hat nichts ausgelöst");
        send("{\"type\":\"ok\",\"seq\":" + keepSeq + "}");
```

und die bisherige Zeile `send("{\"type\":\"ok\"}");` an dieser Stelle **entfernen** (die Hilfsmethode `awaitMulliganPrompt` für "Play" bleibt wie sie ist; `requestState` erzwingt einen frischen Snapshot, weil ein verworfener Klick keinen Push auslöst).

- [ ] **Step 8: Tests laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='WebGuiGameTest,StateSerializerTest,ChoiceBrokerTest'`
Expected: alle grün (WebGuiGameTest jetzt 16).

Run: `mvn test -Dtest=BridgeEndToEndTest 2>&1 | tail -8` (zweimal)
Expected: grün.

- [ ] **Step 9: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m3: prompt-sequenz aus forges inputqueue, veraltete klicks verworfen, tutor-suche als listen-dialog mit kartendetails

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Phasen-Stops und Volle Kontrolle

**Files:**
- Create: `bridge/src/main/java/mtgplayer/gui/Stops.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/Snapshot.java` (Snapshot.stops, fullControl)
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java` (Stops-Record)
- Modify: `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java` (ViewContext → Snapshot-Felder)
- Modify: `bridge/src/main/java/mtgplayer/protocol/ViewContext.java`
- Modify: `bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`
- Modify: `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/gui/StopsTest.java`, `WebGuiGameTest.java`

**Interfaces:**
- Produces:
  - `mtgplayer.gui.Stops` – `static Stops defaults()`, `boolean stopsAt(boolean ownTurn, PhaseType phase)`, `Stops with(boolean ownTurn, Set<PhaseType> phases)`, `Set<PhaseType> own()`, `Set<PhaseType> opp()`; unveränderlich.
  - `Messages.StopsMsg(List<String> own, List<String> opp)` (Namen der PhaseType-Konstanten).
  - `Snapshot` bekommt `Messages.StopsMsg stops` und `boolean fullControl` (vor `prompt`).
  - `ViewContext` bekommt `Messages.StopsMsg stops, boolean fullControl` (am Ende); `ViewContext.plain(...)` setzt Defaults.
  - `WebGuiGame.setStops(Stops)`, `WebGuiGame.setFullControl(boolean)`, `Stops stops()`, `boolean fullControl()`.
  - Nachrichten Browser → Bridge: `{"type":"setStops","own":[...],"opp":[...]}`, `{"type":"fullControl","value":true}`.

- [ ] **Step 1: Failing Test für Stops**

`bridge/src/test/java/mtgplayer/gui/StopsTest.java`:

```java
package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.phase.PhaseType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

class StopsTest {

    @Test
    void defaultsEntsprechenForgeDesktop() {
        Stops s = Stops.defaults();
        assertTrue(s.stopsAt(true, PhaseType.MAIN1));
        assertTrue(s.stopsAt(true, PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(s.stopsAt(true, PhaseType.MAIN2));
        assertFalse(s.stopsAt(true, PhaseType.UPKEEP));
        assertFalse(s.stopsAt(true, PhaseType.END_OF_TURN));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_BEGIN));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_DECLARE_ATTACKERS));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(s.stopsAt(false, PhaseType.END_OF_TURN));
        assertFalse(s.stopsAt(false, PhaseType.MAIN1));
    }

    @Test
    void withErsetztNurEineSeite() {
        Stops s = Stops.defaults().with(true, EnumSet.of(PhaseType.UPKEEP));
        assertTrue(s.stopsAt(true, PhaseType.UPKEEP));
        assertFalse(s.stopsAt(true, PhaseType.MAIN1));
        assertTrue(s.stopsAt(false, PhaseType.END_OF_TURN), "opp unverändert");
        assertEquals(Set.of(PhaseType.UPKEEP), s.own());
    }

    @Test
    void fromNamesIgnoriertUnbekannte() {
        Stops s = Stops.defaults().with(false, Stops.parse(java.util.List.of("MAIN1", "GIBTS_NICHT")));
        assertEquals(Set.of(PhaseType.MAIN1), s.opp());
    }
}
```

In `WebGuiGameTest` ergänzen:

```java
    @Test
    void isUiSetToSkipPhaseFolgtStopsUndFullControl() {
        assertTrue(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP), "ohne Spiel: eigener Zug angenommen, UPKEEP wird übersprungen");
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.MAIN1));
        gui.setFullControl(true);
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP));
        gui.setFullControl(false);
        gui.setStops(Stops.defaults().with(true, java.util.EnumSet.of(forge.game.phase.PhaseType.UPKEEP)));
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP));
        assertTrue(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.MAIN1));
    }
```

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='StopsTest,WebGuiGameTest'`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Stops schreiben**

`bridge/src/main/java/mtgplayer/gui/Stops.java`:

```java
package mtgplayer.gui;

import forge.game.phase.PhaseType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Phasen, in denen der Spieler angehalten wird – getrennt für eigene und gegnerische Züge. Unveränderlich. */
public final class Stops {

    private final EnumSet<PhaseType> own;
    private final EnumSet<PhaseType> opp;

    private Stops(EnumSet<PhaseType> own, EnumSet<PhaseType> opp) {
        this.own = own;
        this.opp = opp;
    }

    /** Forges Desktop-Defaults (PHASE_HUMAN_* / PHASE_AI_* in ForgePreferences). */
    public static Stops defaults() {
        return new Stops(
                EnumSet.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2),
                EnumSet.of(PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN));
    }

    public boolean stopsAt(boolean ownTurn, PhaseType phase) {
        return (ownTurn ? own : opp).contains(phase);
    }

    public Stops with(boolean ownTurn, Set<PhaseType> phases) {
        EnumSet<PhaseType> copy = phases.isEmpty() ? EnumSet.noneOf(PhaseType.class) : EnumSet.copyOf(phases);
        return ownTurn ? new Stops(copy, opp) : new Stops(own, copy);
    }

    public Set<PhaseType> own() { return Collections.unmodifiableSet(own); }
    public Set<PhaseType> opp() { return Collections.unmodifiableSet(opp); }

    /** Namen der PhaseType-Konstanten → Set; unbekannte Namen werden ignoriert. */
    public static Set<PhaseType> parse(List<String> names) {
        EnumSet<PhaseType> out = EnumSet.noneOf(PhaseType.class);
        if (names == null) return out;
        for (String n : names) {
            try {
                out.add(PhaseType.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // unbekannte Phase vom Client – ignorieren
            }
        }
        return out;
    }

    public static List<String> names(Set<PhaseType> phases) {
        List<String> out = new ArrayList<>();
        for (PhaseType p : PhaseType.values()) {
            if (phases.contains(p)) out.add(p.name());
        }
        return out;
    }
}
```

- [ ] **Step 4: Protokoll und Serializer**

`Messages.java` ergänzen:

```java
    /** Phasen, in denen angehalten wird (Namen der PhaseType-Konstanten), je eigener/gegnerischer Zug. */
    public record StopsMsg(List<String> own, List<String> opp) { }
```

`Snapshot`-Record: vor `PromptSnap prompt` zwei Felder einfügen: `Messages.StopsMsg stops, boolean fullControl` (Import `mtgplayer.protocol.Messages` ist im selben Package – kein Import nötig).

`ViewContext`: Felder `Messages.StopsMsg stops, boolean fullControl` anhängen; `plain(me)` übergibt `new Messages.StopsMsg(List.of(), List.of()), false`. Bestehende Konstruktor-Aufrufe (Tests, `WebGuiGame.pushState`, `WebGuiGame.options`) um die zwei Argumente ergänzen – in `options(...)` `new Messages.StopsMsg(List.of(), List.of()), false`.

`StateSerializer.snapshot(...)`: beim `new Snapshot(...)` `ctx.stops(), ctx.fullControl()` vor `ctx.prompt()` einsetzen.

`StateSerializerTest`: der `ViewContext`-Konstruktor-Aufruf in `promptUndSelectableWerdenDurchgereicht` bekommt am Ende `, new Messages.StopsMsg(List.of(), List.of()), false`.

- [ ] **Step 5: WebGuiGame – Stops und Volle Kontrolle**

Felder:

```java
    private volatile Stops stops = Stops.defaults();
    private volatile boolean fullControl;
```

Methoden (die leere `applyFullControlPref()` aus Task 1 ersetzen):

```java
    public Stops stops() { return stops; }
    public boolean fullControl() { return fullControl; }

    public void setStops(Stops s) {
        stops = s;
        push();
    }

    public void setFullControl(boolean on) {
        fullControl = on;
        applyFullControlPref();
        push();
    }

    /** Volle Kontrolle schaltet auch Forges Auto-Pass-ohne-Aktionen ab – pro Controller (wechselt je Spiel). */
    void applyFullControlPref() {
        IGameController c = getGameController();
        if (c == null || c.getYieldController() == null) return;
        c.getYieldController().setPref(forge.localinstance.properties.ForgePreferences.FPref.YIELD_AUTO_PASS_NO_ACTIONS,
                fullControl ? "false" : "true");
    }

    /** true = Phase überspringen. Ohne Spielzustand wird der eigene Zug angenommen. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        if (fullControl || phase == null) return false;
        boolean ownTurn = playerTurn == null || getLocalPlayers().contains(playerTurn);
        return !stops.stopsAt(ownTurn, phase);
    }
```

`pushState()`: `ViewContext` um `new Messages.StopsMsg(Stops.names(stops.own()), Stops.names(stops.opp())), fullControl` ergänzen.

- [ ] **Step 6: Bridge – Nachrichten**

In `Bridge.handle` zwei Fälle ergänzen:

```java
            case "setStops" -> ui(() -> {
                Stops s = gui.stops();
                if (msg.has("own")) s = s.with(true, Stops.parse(Json.mapper().convertValue(msg.get("own"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })));
                if (msg.has("opp")) s = s.with(false, Stops.parse(Json.mapper().convertValue(msg.get("opp"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })));
                gui.setStops(s);
            });
            case "fullControl" -> ui(() -> gui.setFullControl(msg.path("value").asBoolean(false)));
```

(Imports: `mtgplayer.gui.Stops`, `mtgplayer.protocol.Json`, `java.util.List`.)

- [ ] **Step 7: Tests laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='StopsTest,WebGuiGameTest,StateSerializerTest'`
Expected: grün (StopsTest 3, WebGuiGameTest 17, StateSerializerTest 5).

- [ ] **Step 8: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m3: phasen-stops je zugseite und volle kontrolle (auto-pass aus)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Dialoge für Kampfschaden, Mengen und Kartenlisten

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`
- Modify: `bridge/src/main/java/mtgplayer/gui/ChoiceBroker.java` (10-Arg-`ask`, falls in Task 1 noch nicht geschehen)
- Test: `bridge/src/test/java/mtgplayer/gui/WebGuiGameTest.java`

**Interfaces:**
- Produces (Wire):
  - `damage`: `options` = Blocker in Reihenfolge (`card`, `lethal`), optional zuletzt der Verteidiger (`player` oder `card`, `label` "Verteidiger (Trample)") wenn Trample; `amount` = Gesamtschaden; Antwort = Zahlen-Liste je Option, Summe = `amount`.
  - `amount`: `options` mit `max` je Eintrag (`card`/`player` wenn Entity, sonst nur `label`), `amount`, `atLeastOne`; Antwort = Zahlen-Liste je Option.
  - `cardlist`: `options` = Karten in aktueller Reihenfolge, `movable` markiert verschiebbare; `message` nennt erlaubte Ziele ("oben"/"unten"/"beliebig"); Antwort = Index-Liste als neue Gesamtreihenfolge.

- [ ] **Step 1: Failing Tests**

In `WebGuiGameTest` ergänzen:

```java
    @Test
    void assignGenericAmountFragtUndVerteilt() throws Exception {
        java.util.Map<Object, Integer> target = new java.util.LinkedHashMap<>();
        target.put("Rot", 3);
        target.put("Grün", 3);
        CompletableFuture<java.util.Map<Object, Integer>> r = CompletableFuture.supplyAsync(
                () -> gui.assignGenericAmount(null, target, 3, true, "Mana"));
        JsonNode c = nextChoice();
        assertEquals("amount", c.get("kind").asText());
        assertEquals(3, c.get("amount").asInt());
        assertEquals(true, c.get("atLeastOne").asBoolean());
        assertEquals(3, c.get("options").get(0).get("max").asInt());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[1,2]"));
        java.util.Map<Object, Integer> res = r.get(5, TimeUnit.SECONDS);
        assertEquals(1, res.get("Rot"));
        assertEquals(2, res.get("Grün"));
    }

    @Test
    void assignGenericAmountUngueltigeAntwortFaelltAufAllesAufDenErstenZurueck() throws Exception {
        java.util.Map<Object, Integer> target = new java.util.LinkedHashMap<>();
        target.put("A", 5);
        target.put("B", 5);
        CompletableFuture<java.util.Map<Object, Integer>> r = CompletableFuture.supplyAsync(
                () -> gui.assignGenericAmount(null, target, 4, false, "x"));
        JsonNode c = nextChoice();
        gui.broker().answer(c.get("id").asInt(), Json.parse("[1,1]")); // Summe 2 != 4
        java.util.Map<Object, Integer> res = r.get(5, TimeUnit.SECONDS);
        assertEquals(4, res.get("A"));
        assertEquals(0, res.get("B"));
    }

    @Test
    void manipulateCardListOhneKartenBlocktNicht() {
        assertTrue(gui.manipulateCardList("t", List.of(), List.of(), true, true, false).isEmpty());
    }
```

(Ein Test für `assignCombatDamage` und `manipulateCardList` mit echten `CardView`s ist ohne Spiel nicht möglich – die Logik teilt sich mit `assignGenericAmount` die Hilfsmethode `amounts(...)`, die hier abgedeckt wird. Beide werden im E2E-Spiel und manuell geprüft.)

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=WebGuiGameTest`
Expected: `assignGenericAmountFragtUndVerteilt` schlägt fehl (keine `choice`-Nachricht, weil die M2-Implementierung nicht fragt).

- [ ] **Step 3: Implementierung**

In `WebGuiGame` die drei M2-Methoden ersetzen und eine Hilfsmethode ergänzen:

```java
    /** Zahlen-Liste je Option; null, wenn Antwort fehlt/ungültig oder die Summe nicht passt. */
    private static List<Integer> amounts(JsonNode v, int n, int total, List<Integer> maxPer, boolean atLeastOne) {
        if (v == null || !v.isArray() || v.size() != n) return null;
        List<Integer> out = new ArrayList<>();
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int a = v.get(i).asInt(-1);
            if (a < 0) return null;
            if (atLeastOne && a < 1) return null;
            Integer max = maxPer.get(i);
            if (max != null && max > 0 && a > max) return null;
            out.add(a);
            sum += a;
        }
        return sum == total ? out : null;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        if (damage <= 0) return out;
        List<CardView> targets = blockers == null ? new ArrayList<>() : new ArrayList<>(blockers);
        boolean trample = attacker != null && attacker.getCurrentState().hasTrample() && defender != null;
        List<Messages.Option> opts = new ArrayList<>();
        List<Integer> maxPer = new ArrayList<>();
        int i = 0;
        for (CardView b : targets) {
            opts.add(new Messages.Option(i++, b.getCurrentState().getName(), b.getId(), null, null, null,
                    Math.max(1, b.getLethalDamage()), null));
            maxPer.add(null);
        }
        if (trample) {
            Integer pid = defender instanceof PlayerView pv ? pv.getId() : null;
            Integer cid = defender instanceof CardView dc ? dc.getId() : null;
            opts.add(new Messages.Option(i++, "Verteidiger (Trample)", cid, pid, null, null, null, null));
            maxPer.add(null);
        }
        String title = (attacker == null ? "Angreifer" : attacker.getCurrentState().getName()) + " – " + damage + " Schaden verteilen";
        JsonNode v = broker.ask("damage", title, "Jedem Blocker in Reihenfolge tödlichen Schaden zuweisen, bevor der nächste etwas bekommt.",
                opts, opts.size(), opts.size(), attacker == null ? null : attacker.getId(), damage, Boolean.FALSE);
        List<Integer> a = amounts(v, opts.size(), damage, maxPer, false);
        if (a == null) {
            return autoAssign(targets, damage, trample);
        }
        for (int k = 0; k < targets.size(); k++) {
            if (a.get(k) > 0) out.put(targets.get(k), a.get(k));
        }
        if (trample && a.get(targets.size()) > 0) {
            out.put(null, a.get(targets.size()));
        }
        return out.isEmpty() ? autoAssign(targets, damage, trample) : out;
    }

    /** M2-Verhalten als Rückfallebene: tödlich in Reihenfolge, Rest auf den letzten bzw. bei Trample auf den Verteidiger. */
    private static Map<CardView, Integer> autoAssign(List<CardView> blockers, int damage, boolean trample) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        int rest = damage;
        for (CardView b : blockers) {
            int give = Math.min(Math.max(1, b.getLethalDamage()), rest);
            out.put(b, give);
            rest -= give;
            if (rest <= 0) break;
        }
        if (rest > 0) {
            if (trample) {
                out.put(null, rest);
            } else if (!out.isEmpty()) {
                CardView last = null;
                for (CardView b : out.keySet()) last = b;
                out.put(last, out.get(last) + rest);
            }
        }
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> out = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) return out;
        List<Object> keys = new ArrayList<>(target.keySet());
        List<Messages.Option> opts = new ArrayList<>();
        List<Integer> maxPer = new ArrayList<>();
        int i = 0;
        for (Object k : keys) {
            Integer card = k instanceof CardView cv ? cv.getId() : null;
            Integer player = k instanceof PlayerView pv ? pv.getId() : null;
            Integer max = target.get(k);
            opts.add(new Messages.Option(i++, String.valueOf(k), card, player, null, max, null, null));
            maxPer.add(max);
        }
        String title = amount + " " + (amountLabel == null ? "" : amountLabel) + " verteilen";
        JsonNode v = broker.ask("amount", title, effectSource == null ? "" : effectSource.getCurrentState().getName(),
                opts, opts.size(), opts.size(), effectSource == null ? null : effectSource.getId(), amount, atLeastOne);
        List<Integer> a = amounts(v, keys.size(), amount, maxPer, atLeastOne);
        for (int k = 0; k < keys.size(); k++) {
            out.put(keys.get(k), a == null ? (k == 0 ? amount : 0) : a.get(k));
        }
        return out;
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        if (cards != null) for (CardView c : cards) all.add(c);
        if (all.isEmpty()) return all;
        java.util.Set<Integer> movable = new java.util.HashSet<>();
        if (manipulable != null) for (CardView c : manipulable) movable.add(c.getId());
        List<Messages.Option> opts = new ArrayList<>();
        int i = 0;
        for (CardView c : all) {
            Messages.Option base = options(List.of(c), null).get(0);
            opts.add(new Messages.Option(i++, base.label(), base.card(), null, base.detail(), null, null,
                    movable.contains(c.getId()) ? Boolean.TRUE : null));
        }
        String where = toAnywhere ? "beliebig" : (toTop && toBottom ? "oben oder unten" : toTop ? "nur oben" : "nur unten");
        JsonNode v = broker.ask("cardlist", title, "Verschiebbare Karten: " + where + ". Oberste Karte zuerst.",
                opts, all.size(), all.size(), null);
        List<Integer> idx = indices(v, all.size(), all.size(), all.size());
        if (idx.size() != all.size()) return all; // keine gültige Permutation → unverändert
        List<CardView> out = new ArrayList<>();
        for (int k : idx) out.add(all.get(k));
        return out;
    }
```

Hinweis: `indices(JsonNode, int min, int max, int size)` existiert seit dem M2-Review; sie dedupliziert und füllt bei zu wenigen Einträgen mit den ersten `min` auf – deshalb die explizite Größenprüfung.

- [ ] **Step 4: Tests laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=WebGuiGameTest`
Expected: grün (20 Tests).

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m3: dialoge fuer kampfschaden, mengenverteilung und kartenlisten

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend – Sequenz, Phasenleiste, neue Dialoge, Hover-Panel

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/store.ts`, `web/src/components/{Prompt,CardBox,PlayerZone,ChoiceDialog,Table}.tsx`, `web/src/styles.css`
- Create: `web/src/dialogs.ts`, `web/src/dialogs.test.ts`, `web/src/components/PhaseBar.tsx`, `web/src/components/CardDetail.tsx`

**Interfaces:**
- Consumes: Wire-Format aus Task 1–3.
- Produces: `dialogs.ts` – `isPermutation(idx: number[], n: number): boolean`, `amountsValid(a: number[], total: number, maxPer: (number|undefined)[], atLeastOne: boolean): boolean`, `remaining(a: number[], total: number): number`.

- [ ] **Step 1: Protokoll-Typen**

`web/src/protocol.ts` – ändern/ergänzen:

```ts
export interface PromptSnap {
  message: string;
  card?: number;
  okLabel: string;
  cancelLabel: string;
  okEnabled: boolean;
  cancelEnabled: boolean;
  seq: number;
}
```

`PlayerSnap`: `highlighted?: boolean;` anhängen.

`Snapshot`: vor `prompt`: `stops: { own: string[]; opp: string[] }; fullControl: boolean;`.

`Option`: `detail?: CardSnap; max?: number; lethal?: number; movable?: boolean;` anhängen.

`ChoiceKind`: `| "damage" | "amount" | "cardlist"` anhängen. `Choice`: `amount?: number; atLeastOne?: boolean;` anhängen.

`Outbound` ergänzen:

```ts
  | { type: "selectCard"; id: number; alt?: boolean; seq?: number }
  | { type: "selectPlayer"; id: number; seq?: number }
  | { type: "ok"; seq?: number }
  | { type: "cancel"; seq?: number }
  | { type: "setStops"; own?: string[]; opp?: string[] }
  | { type: "fullControl"; value: boolean }
```

(die alten Varianten von `selectCard`/`selectPlayer`/`ok`/`cancel` ersetzen).

Außerdem eine Konstante für die Phasenleiste:

```ts
export const PHASES: { id: string; short: string }[] = [
  { id: "UNTAP", short: "UT" }, { id: "UPKEEP", short: "UP" }, { id: "DRAW", short: "DR" },
  { id: "MAIN1", short: "M1" }, { id: "COMBAT_BEGIN", short: "BC" }, { id: "COMBAT_DECLARE_ATTACKERS", short: "DA" },
  { id: "COMBAT_DECLARE_BLOCKERS", short: "DB" }, { id: "COMBAT_FIRST_STRIKE_DAMAGE", short: "FS" },
  { id: "COMBAT_DAMAGE", short: "CD" }, { id: "COMBAT_END", short: "EC" }, { id: "MAIN2", short: "M2" },
  { id: "END_OF_TURN", short: "END" }, { id: "CLEANUP", short: "CL" },
];
```

- [ ] **Step 2: Failing Test für dialogs.ts**

`web/src/dialogs.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { amountsValid, isPermutation, remaining } from "./dialogs";

describe("isPermutation", () => {
  it("akzeptiert nur vollstaendige permutationen", () => {
    expect(isPermutation([2, 0, 1], 3)).toBe(true);
    expect(isPermutation([0, 0, 1], 3)).toBe(false);
    expect(isPermutation([0, 1], 3)).toBe(false);
    expect(isPermutation([0, 1, 3], 3)).toBe(false);
  });
});

describe("amountsValid", () => {
  it("summe muss stimmen", () => {
    expect(amountsValid([1, 2], 3, [undefined, undefined], false)).toBe(true);
    expect(amountsValid([1, 1], 3, [undefined, undefined], false)).toBe(false);
  });
  it("max je eintrag und atLeastOne", () => {
    expect(amountsValid([3, 0], 3, [2, 5], false)).toBe(false);
    expect(amountsValid([3, 0], 3, [undefined, undefined], true)).toBe(false);
    expect(amountsValid([2, 1], 3, [2, 5], true)).toBe(true);
  });
  it("negatives ist ungueltig", () => {
    expect(amountsValid([4, -1], 3, [undefined, undefined], false)).toBe(false);
  });
});

describe("remaining", () => {
  it("rest bis zur summe", () => {
    expect(remaining([1, 1], 5)).toBe(3);
  });
});
```

Run: `cd /home/kevin/projects/MTG-Player/web && npm test 2>&1 | tail -4`
Expected: Fehler, `./dialogs` nicht gefunden.

- [ ] **Step 3: dialogs.ts**

```ts
export function isPermutation(idx: number[], n: number): boolean {
  if (idx.length !== n) return false;
  const seen = new Set<number>();
  for (const i of idx) {
    if (!Number.isInteger(i) || i < 0 || i >= n || seen.has(i)) return false;
    seen.add(i);
  }
  return true;
}

export function amountsValid(a: number[], total: number, maxPer: (number | undefined)[], atLeastOne: boolean): boolean {
  if (a.length !== maxPer.length) return false;
  let sum = 0;
  for (let i = 0; i < a.length; i++) {
    const v = a[i];
    if (!Number.isInteger(v) || v < 0) return false;
    if (atLeastOne && v < 1) return false;
    const m = maxPer[i];
    if (m !== undefined && m > 0 && v > m) return false;
    sum += v;
  }
  return sum === total;
}

export function remaining(a: number[], total: number): number {
  return total - a.reduce((s, v) => s + (Number.isFinite(v) ? v : 0), 0);
}
```

Run: `npm test 2>&1 | tail -4` → alle grün (9 + 5).

- [ ] **Step 4: Store – Hover-Karte**

`web/src/store.ts`: in `AppState` `hover?: number;` ergänzen (nicht Teil von `reduce`), im Store `setHover: (id?: number) => void;` mit `setHover: (id) => set({ hover: id })`. `initialState` unverändert.

- [ ] **Step 5: seq mitsenden**

`Prompt.tsx`: beide `send({ type: "ok" })`/`send({ type: "cancel" })` (Tastatur und Buttons) zu `send({ type: "ok", seq: p.seq })` bzw. `send({ type: "cancel", seq: p.seq })`.

`CardBox.tsx`: `import { useStore } from "../store";` ergänzen; `const seq = useStore((s) => s.state?.prompt.seq);` und `const setHover = useStore((s) => s.setHover);`. `send({ type: "selectCard", id: card.id, alt: e.button === 2, seq })`. Zusätzlich `onMouseEnter={() => setHover(card.id)}` und `onMouseLeave={() => setHover(undefined)}` am Karten-Div (nicht bei `faceDown`).

`PlayerZone.tsx`: `import { useStore } from "../store";` ergänzen; `const seq = useStore((s) => s.state?.prompt.seq);` und `send({ type: "selectPlayer", id: p.id, seq })`. Header-Klasse: `"header" + (p.highlighted ? " highlighted" : "")`.

- [ ] **Step 6: PhaseBar**

`web/src/components/PhaseBar.tsx`:

```tsx
import { PHASES, type Snapshot } from "../protocol";
import { send } from "../ws";

export default function PhaseBar({ state }: { state: Snapshot }) {
  const ownTurn = state.activePlayer === state.me;
  const side: "own" | "opp" = ownTurn ? "own" : "opp";
  const stops = new Set(state.stops?.[side] ?? []);
  const toggle = (id: string) => {
    const next = new Set(stops);
    if (next.has(id)) next.delete(id); else next.add(id);
    send({ type: "setStops", [side]: [...next] } as { type: "setStops"; own?: string[]; opp?: string[] });
  };
  return (
    <div className="phasebar">
      <span className="side">{ownTurn ? "Eigener Zug" : "Gegnerzug"}</span>
      {PHASES.map((ph) => (
        <button
          key={ph.id}
          title={ph.id + (stops.has(ph.id) ? " – Stop" : " – wird übersprungen")}
          className={"phase" + (state.phase === ph.id ? " current" : "") + (stops.has(ph.id) ? " stop" : "")}
          onClick={() => toggle(ph.id)}
        >{ph.short}</button>
      ))}
      <label className="fullcontrol">
        <input type="checkbox" checked={state.fullControl} onChange={(e) => send({ type: "fullControl", value: e.target.checked })} />
        Volle Kontrolle
      </label>
    </div>
  );
}
```

- [ ] **Step 7: CardDetail**

`web/src/components/CardDetail.tsx`:

```tsx
import { useStore } from "../store";

export default function CardDetail() {
  const card = useStore((s) => (s.hover !== undefined ? s.state?.cards[String(s.hover)] : undefined));
  if (!card || card.faceDown) return <div className="detail empty">Karte anfahren für Details</div>;
  return (
    <div className="detail">
      <div className="name">{card.name} <span className="cost">{card.manaCost ?? ""}</span></div>
      <div className="type">{card.typeLine ?? ""}</div>
      {card.power !== undefined && <div className="pt">{card.power}/{card.toughness}</div>}
      <div className="text">{card.text ?? ""}</div>
      {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k} ×${v}`).join(", ")}</div>}
    </div>
  );
}
```

- [ ] **Step 8: ChoiceDialog – neue Kinds und Details**

In `ChoiceDialog.tsx`:

- Import `amountsValid, isPermutation, remaining` aus `../dialogs`.
- State ergänzen: `const [amounts, setAmounts] = useState<number[]>(() => choice.options.map(() => 0));` und `const [order, setOrder] = useState<number[]>(() => choice.options.map((o) => o.index));`.
- Optionsdarstellung: eine Hilfsfunktion, die `label(o)` ersetzt und Details zeigt:

```tsx
  const optionView = (o: Option) => (
    <>
      <div className="opt-label">{label(o)}</div>
      {o.detail && (
        <div className="opt-detail">
          <span className="type">{o.detail.typeLine}</span>
          {o.detail.power !== undefined && <span className="pt"> {o.detail.power}/{o.detail.toughness}</span>}
          <div className="text">{o.detail.text}</div>
        </div>
      )}
    </>
  );
```

(`Option` aus `../protocol` importieren.) In der bestehenden Listen-Darstellung `{label(o)}` durch `{optionView(o)}` ersetzen.

- Neue Fälle im `switch`:

```tsx
      case "damage":
      case "amount": {
        const total = choice.amount ?? 0;
        const maxPer = choice.options.map((o) => o.max);
        const ok = amountsValid(amounts, total, maxPer, choice.atLeastOne ?? false);
        const set = (i: number, v: number) => setAmounts(amounts.map((a, j) => (j === i ? v : a)));
        return (
          <>
            <ul className="options">
              {choice.options.map((o, i) => (
                <li key={o.index} className="amount-row">
                  <div className="grow">{optionView(o)}{o.lethal !== undefined && <span className="hint"> tödlich: {o.lethal}</span>}{o.max !== undefined && <span className="hint"> max {o.max}</span>}</div>
                  <input type="number" min={0} max={o.max ?? total} value={amounts[i]} onChange={(e) => set(i, Number(e.target.value))} />
                  {o.lethal !== undefined && <button onClick={() => set(i, Math.min(o.lethal!, remaining(amounts, total) + amounts[i]))}>tödlich</button>}
                </li>
              ))}
            </ul>
            <p className="hint">Rest: {remaining(amounts, total)}</p>
            <div className="buttons">
              <button className="primary" disabled={!ok} onClick={() => answer(amounts)}>OK</button>
              <button onClick={() => answer(null)}>Automatisch</button>
            </div>
          </>
        );
      }
      case "cardlist": {
        const move = (from: number, to: number) => {
          if (to < 0 || to >= order.length) return;
          const next = [...order];
          const [x] = next.splice(from, 1);
          next.splice(to, 0, x);
          setOrder(next);
        };
        return (
          <>
            <ul className="options">
              {order.map((idx, pos) => {
                const o = choice.options[idx];
                return (
                  <li key={o.index} className={"cardlist-row" + (o.movable ? " movable" : "")}>
                    <span className="pos">{pos + 1}.</span>
                    <div className="grow">{optionView(o)}</div>
                    {o.movable && <>
                      <button onClick={() => move(pos, 0)}>⤒</button>
                      <button onClick={() => move(pos, pos - 1)}>↑</button>
                      <button onClick={() => move(pos, pos + 1)}>↓</button>
                      <button onClick={() => move(pos, order.length - 1)}>⤓</button>
                    </>}
                  </li>
                );
              })}
            </ul>
            <div className="buttons">
              <button className="primary" disabled={!isPermutation(order, choice.options.length)} onClick={() => answer(order)}>OK</button>
            </div>
          </>
        );
      }
```

- [ ] **Step 9: Table und Styles**

`Table.tsx`: in `.side` über dem Stack `<CardDetail />` rendern; in `.mine` über dem `Prompt` eine `<PhaseBar state={state} />`. Imports ergänzen.

`styles.css` ergänzen:

```css
.phasebar { display: flex; gap: 4px; align-items: center; padding: 4px 6px; background: #14171b; border-radius: 8px; font-size: 12px; }
.phasebar .side { color: #9aa; margin-right: 6px; white-space: nowrap; }
.phasebar .phase { padding: 2px 6px; font-size: 11px; opacity: 0.5; }
.phasebar .phase.stop { opacity: 1; border-color: #d6a72f; }
.phasebar .phase.current { background: #2f6fd6; opacity: 1; }
.phasebar .fullcontrol { margin-left: auto; display: flex; gap: 4px; align-items: center; }
.detail { border: 1px solid #333; border-radius: 8px; padding: 8px; font-size: 12px; min-height: 120px; }
.detail.empty { color: #666; }
.detail .name { font-weight: 600; font-size: 14px; }
.detail .cost, .detail .type { color: #9aa; }
.detail .text { white-space: pre-wrap; margin-top: 6px; }
.player .header.highlighted { outline: 2px solid #ffd23f; border-radius: 4px; }
.opt-detail { font-size: 11px; color: #9aa; }
.opt-detail .text { white-space: pre-wrap; color: #ccc; }
.amount-row, .cardlist-row { display: flex; gap: 6px; align-items: center; }
.amount-row .grow, .cardlist-row .grow { flex: 1; }
.amount-row input { width: 70px; }
.cardlist-row .pos { width: 24px; color: #9aa; }
.cardlist-row.movable { border-color: #2f6fd6; }
```

- [ ] **Step 10: Build und Tests**

Run: `cd /home/kevin/projects/MTG-Player/web && npm test 2>&1 | tail -4 && npm run build 2>&1 | tail -3`
Expected: 14 Tests grün, Build sauber.

- [ ] **Step 11: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add web/src
git commit -m "m3: frontend – sequenz bei eingaben, phasenleiste mit stops, dialoge fuer schaden/mengen/kartenlisten, hover-details

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Rauchtest, README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Gesamtsuite**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test 2>&1 | tail -3 && cd ../web && npm test 2>&1 | tail -3 && npm run build 2>&1 | tail -1`
Expected: bridge `Tests run: 43, Failures: 0, Errors: 0` (33 + 3 StopsTest + 7 neue WebGuiGame-Tests); web `14 passed`; Build sauber.

- [ ] **Step 2: README**

Im Abschnitt "Spielen" nach dem Steuerungs-Absatz ergänzen:

```markdown
Phasenleiste über dem Prompt: Klick auf eine Phase setzt/entfernt einen Stop (getrennt für eigene und
gegnerische Züge, je nachdem wessen Zug gerade ist). "Volle Kontrolle" hält in jeder Phase an und schaltet
das automatische Passen ab. Tutor-Effekte (Bibliothek durchsuchen) öffnen einen Listen-Dialog mit Kartendetails.
Veraltete Klicks (Prompt hat inzwischen gewechselt) werden von der Bridge ignoriert.
```

Und "Was noch fehlt" anpassen:

```markdown
## Was noch fehlt (M4+)

Textlisten-Import und Archidekt, Kartenbilder, Spiel-Log im Browser, Spieler-Markierung als Ziel.
```

- [ ] **Step 3: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add README.md
git commit -m "m3: readme – stops, volle kontrolle, tutor-dialog

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Nach diesem Plan

Kevin spielt ein Spiel mit Tutor, Kampf mit mehreren Blockern und einem Scry/Brainstorm-Effekt. Was danach noch stört, geht in M4 (Textlisten-Import) bzw. M5 (Bilder, Log).
