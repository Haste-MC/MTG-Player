# M2: WebSocket-Bridge, menschlicher Sitz, Minimal-UI – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Mensch spielt im Browser ein Commander-Spiel gegen 1–5 Forge-KIs: Prio passen, Land legen, Zauber mit Auto-Mana, Ziele wählen, Angreifer/Blocker deklarieren, Dialoge beantworten. Hässlich, aber spielbar.

**Architecture:** `WebGuiGame` erbt von Forges `AbstractGuiGame` (das bereits Sichtbarkeit, Auswahl-Tracking, `one`/`many`/`getInteger` auf Basis von `getChoices` und `concede` mitbringt) und übersetzt jeden Forge-Aufruf in JSON über eine `Transport`-Schnittstelle. Zustandsupdates werden gebündelt als vollständiger Snapshot (`state`) gepusht; synchrone Dialoge blockieren den Game-Thread über einen `ChoiceBroker` auf eine `answer`-Nachricht. Das Spiel läuft über Forges `HostedMatch` (nicht über `AiMatch`, das bleibt der headless Test-Harness). Server: Java-WebSocket auf 8081, JDK-`HttpServer` für das gebaute Frontend auf 8080. Frontend: React + TypeScript + Vite + Zustand, ohne Kartenbilder (M5).

**Tech Stack:** Java 17, Forge 2.0.14 (`forge-gui`), Jackson 2.17, Java-WebSocket 1.5.7, JUnit 5; Node ≥ 20, Vite 5, React 18, TypeScript 5, Zustand 4, Vitest.

## Global Constraints

- Java 17, Maven ≥ 3.8.1; Node ≥ 20 (installiert: 26).
- Forge-Submodule bleibt unverändert; kein Patch, kein Fork.
- Forges Nutzer-/Cache-Daten liegen unter `~/.mtg-player/`, nie unter `~/.forge`.
- Bridge-Package `mtgplayer`; bestehende Klassen `mtgplayer.forge.ForgeBoot`, `mtgplayer.forge.Precons`, `mtgplayer.match.AiMatch` bleiben in Signatur unverändert.
- Ports: WebSocket **8081**, HTTP **8080** (konfigurierbar per `-Dmtgplayer.wsPort` / `-Dmtgplayer.httpPort`). Tests benutzen **18081**.
- Protokoll: JSON, Feld `type` bestimmt die Nachricht. Bridge → Browser: `lobby`, `state`, `choice`, `log`, `gameOver`, `error`. Browser → Bridge: `startGame`, `selectCard`, `selectPlayer`, `ok`, `cancel`, `answer`, `concede`, `requestState`. **Abweichung von der Spec:** Die Prompt-Informationen (`input` in der Spec) sind Teil von `state` (Feld `prompt`), nicht eine eigene Nachricht – ein Snapshot ist die einzige Wahrheit, das erspart Reihenfolgeprobleme.
- Informationsverbergung ausschließlich über `AbstractGuiGame.mayView(CardView)` im Serializer. Nicht sichtbare Karten gehen als `{id, faceDown: true}` raus, alle anderen Felder `null`.
- Threading: Forge-Aufrufe an die GUI kommen auf dem Bridge-UI-Thread (`WebGuiBase`-Executor) oder dem Game-Thread. Eingaben aus dem Browser (`selectCard`, `ok`, …) werden mit `GuiBase.getInterface().invokeInEdtLater` auf den UI-Thread gelegt. `answer` wird direkt auf dem WebSocket-Thread am `ChoiceBroker` abgegeben.
- Forge-Prefs für den menschlichen Sitz (in `ForgeBoot.init`): `YIELD_AUTO_PASS_NO_ACTIONS=true`, `UI_SHOW_ACTIONABLE_HIGHLIGHTS=true`, `UI_ENABLE_MUSIC=false`, `UI_ENABLE_SOUNDS=false`, `PLAYER_NAME=Du`.
- Commit-Messages: deutsch, Kleinschreibung, Präfix `m2:`, Abschluss mit `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Maven-Befehle immer aus `bridge/` mit Bash-Timeout 600000 ms; nie zwei Maven-Prozesse gleichzeitig (`pgrep -f surefire`).

---

## Dateistruktur

```
bridge/
├── pom.xml                                   + jackson-databind, Java-WebSocket
└── src/main/java/mtgplayer/
    ├── Main.java                             MODIFY: startet Bridge-Server; `--ai-demo` behält das alte Verhalten
    ├── forge/ForgeBoot.java                  MODIFY: Prefs für den menschlichen Sitz
    ├── protocol/Json.java                    Jackson-ObjectMapper, toJson/parse
    ├── protocol/Snapshot.java                Records: Snapshot, PlayerSnap, CardSnap, StackSnap, PromptSnap
    ├── protocol/ViewContext.java             Record: me, mayView, selectable, weak, highlighted, prompt
    ├── protocol/StateSerializer.java         GameView + ViewContext → Snapshot (reine Funktion)
    ├── protocol/Messages.java                Records: Lobby, Choice, Option, LogLine, GameOver, ErrorMsg
    ├── gui/Transport.java                    interface { void send(Object msg); }
    ├── gui/ChoiceBroker.java                 pending Futures, ask()/answer()
    ├── gui/WebGuiGame.java                   IGuiGame über AbstractGuiGame
    ├── match/HumanMatch.java                 HostedMatch mit einem menschlichen Sitz + KIs
    └── server/
        ├── WsServer.java                     Java-WebSocket, ein Client, Transport-Implementierung
        ├── HttpStatic.java                   JDK HttpServer für web/dist
        └── Bridge.java                       Verdrahtung: Nachrichten → HumanMatch / WebGuiGame
web/
├── package.json, vite.config.ts, tsconfig.json, index.html
└── src/
    ├── main.tsx, App.tsx, styles.css
    ├── protocol.ts                           TS-Typen, spiegelt Snapshot/Messages
    ├── ws.ts                                 WebSocket-Verbindung mit Reconnect
    ├── store.ts                              Zustand-Store + reduce(msg)
    ├── store.test.ts
    └── components/ Lobby.tsx, Table.tsx, PlayerZone.tsx, CardBox.tsx, Hand.tsx, Prompt.tsx, ChoiceDialog.tsx, Log.tsx
```

Verantwortungen: `StateSerializer` weiß, wie Forge-Views nach JSON kommen und ist ohne laufendes Spiel testbar. `ChoiceBroker` weiß nur, wie man auf eine Antwort wartet. `WebGuiGame` übersetzt Forge-Aufrufe und kennt weder Sockets noch HTTP. `Bridge` kennt Sockets und ruft `WebGuiGame`/`HumanMatch`. Das Frontend kennt keine Regeln.

---

### Task 1: Protokoll-Records und StateSerializer

**Files:**
- Modify: `bridge/pom.xml` (Jackson-Dependency)
- Create: `bridge/src/main/java/mtgplayer/protocol/Json.java`
- Create: `bridge/src/main/java/mtgplayer/protocol/Snapshot.java`
- Create: `bridge/src/main/java/mtgplayer/protocol/ViewContext.java`
- Create: `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java`
- Test: `bridge/src/test/java/mtgplayer/protocol/StateSerializerTest.java`

**Interfaces:**
- Consumes: `ForgeBoot.init()`, `Precons.load(String)`.
- Produces:
  - `Json.toJson(Object) : String`, `Json.parse(String) : JsonNode`, `Json.mapper() : ObjectMapper`.
  - `Snapshot`, `Snapshot.PlayerSnap`, `Snapshot.CardSnap`, `Snapshot.StackSnap`, `Snapshot.PromptSnap` (Records, exakt wie unten).
  - `ViewContext(PlayerView me, Predicate<CardView> mayView, Predicate<CardView> selectable, Predicate<CardView> weaklySelectable, Predicate<GameEntityView> highlighted, Snapshot.PromptSnap prompt)`.
  - `StateSerializer.snapshot(GameView gv, ViewContext ctx) : Snapshot`.

- [ ] **Step 1: Jackson in die pom.xml**

In `bridge/pom.xml` innerhalb `<dependencies>` vor der JUnit-Dependency einfügen:

```xml
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.2</version>
        </dependency>
```

- [ ] **Step 2: Records schreiben**

`bridge/src/main/java/mtgplayer/protocol/Snapshot.java`:

```java
package mtgplayer.protocol;

import java.util.List;
import java.util.Map;

/**
 * Vollständiger Spielzustand aus Sicht eines Sitzes. Zonen halten nur Karten-IDs,
 * {@code cards} ist das flache Wörterbuch dazu. Nicht sichtbare Karten haben
 * {@code faceDown = true} und sonst nur {@code null}-Felder.
 */
public record Snapshot(
        String type,
        int turn,
        String phase,
        Integer activePlayer,
        Integer priorityPlayer,
        Integer me,
        boolean gameOver,
        List<PlayerSnap> players,
        List<StackSnap> stack,
        Map<Integer, CardSnap> cards,
        PromptSnap prompt) {

    public static final String TYPE = "state";

    public record PlayerSnap(
            int id,
            String name,
            boolean isAi,
            int life,
            Map<String, Integer> counters,
            Map<Integer, Integer> commanderDamage,
            List<Integer> hand,
            int librarySize,
            List<Integer> graveyard,
            List<Integer> exile,
            List<Integer> command,
            List<Integer> battlefield,
            Map<String, Integer> manaPool,
            boolean hasPriority) { }

    public record CardSnap(
            int id,
            boolean faceDown,
            String name,
            String imageKey,
            Integer controller,
            Integer owner,
            String zone,
            Boolean tapped,
            Boolean sick,
            Integer power,
            Integer toughness,
            Integer damage,
            Map<String, Integer> counters,
            Integer attachedTo,
            List<Integer> attachments,
            String text,
            String typeLine,
            String manaCost,
            Boolean attacking,
            Boolean blocking,
            Boolean token,
            Boolean selectable,
            Boolean actionable,
            Boolean highlighted) {

        public static CardSnap hidden(int id) {
            return new CardSnap(id, true, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record StackSnap(
            int index,
            String text,
            Integer sourceCard,
            Integer controller,
            List<Integer> targetCards,
            List<Integer> targetPlayers) { }

    public record PromptSnap(
            String message,
            Integer card,
            String okLabel,
            String cancelLabel,
            boolean okEnabled,
            boolean cancelEnabled) {

        public static final PromptSnap EMPTY = new PromptSnap("", null, "OK", "Cancel", false, false);
    }
}
```

`bridge/src/main/java/mtgplayer/protocol/ViewContext.java`:

```java
package mtgplayer.protocol;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;

import java.util.function.Predicate;

/** Alles, was der Serializer über den betrachtenden Sitz wissen muss. */
public record ViewContext(
        PlayerView me,
        Predicate<CardView> mayView,
        Predicate<CardView> selectable,
        Predicate<CardView> weaklySelectable,
        Predicate<GameEntityView> highlighted,
        Snapshot.PromptSnap prompt) {

    /** Sicht eines Spielers ohne UI-Zustand – für Tests und den Lobby-Fall. */
    public static ViewContext plain(PlayerView me) {
        return new ViewContext(me, c -> c.canBeShownTo(me), c -> false, c -> false, e -> false, Snapshot.PromptSnap.EMPTY);
    }
}
```

`bridge/src/main/java/mtgplayer/protocol/Json.java`:

```java
package mtgplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() { }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("nicht serialisierbar: " + value.getClass(), e);
        }
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("kein JSON: " + text, e);
        }
    }
}
```

- [ ] **Step 3: Failing Test schreiben**

`bridge/src/test/java/mtgplayer/protocol/StateSerializerTest.java`:

```java
package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class StateSerializerTest {

    private static Game game;
    private static Player me;
    private static Player foe;
    private static Card myHandCard;
    private static Card foeHandCard;
    private static Card myPermanent;

    @BeforeAll
    static void boardAufbauen() {
        ForgeBoot.init();
        Deck a = Precons.load("Abzan Armor [TDC] [2025]");
        Deck b = Precons.load("Adaptive Enchantment [C18] [2018]");
        RegisteredPlayer ra = RegisteredPlayer.forCommander(a);
        ra.setPlayer(new LobbyPlayerAi("Ich", null));
        RegisteredPlayer rb = RegisteredPlayer.forCommander(b);
        rb.setPlayer(new LobbyPlayerAi("Gegner", null));
        game = new Match(new GameRules(GameType.Commander), List.of(ra, rb), "test").createGame();
        me = game.getPlayers().get(0);
        foe = game.getPlayers().get(1);

        List<PaperCard> aCards = a.get(DeckSection.Main).toFlatList();
        List<PaperCard> bCards = b.get(DeckSection.Main).toFlatList();
        myHandCard = Card.fromPaperCard(aCards.get(0), me);
        me.getZone(ZoneType.Hand).add(myHandCard);
        myPermanent = Card.fromPaperCard(aCards.get(1), me);
        me.getZone(ZoneType.Battlefield).add(myPermanent);
        foeHandCard = Card.fromPaperCard(bCards.get(0), foe);
        foe.getZone(ZoneType.Hand).add(foeHandCard);
    }

    @Test
    void eigeneHandkarteIstSichtbar() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap c = s.cards().get(myHandCard.getId());
        assertNotNull(c);
        assertFalse(c.faceDown());
        assertEquals(myHandCard.getName(), c.name());
        assertEquals("Hand", c.zone());
        assertTrue(s.players().get(0).hand().contains(myHandCard.getId()));
    }

    @Test
    void gegnerischeHandkarteIstVerdeckt() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap c = s.cards().get(foeHandCard.getId());
        assertNotNull(c);
        assertTrue(c.faceDown());
        assertNull(c.name());
        assertNull(c.text());
        assertTrue(s.players().get(1).hand().contains(foeHandCard.getId()), "verdeckte ID bleibt in der Hand-Liste");
    }

    @Test
    void battlefieldKarteHatKontrolleurUndTyp() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap c = s.cards().get(myPermanent.getId());
        assertEquals(me.getView().getId(), c.controller());
        assertEquals("Battlefield", c.zone());
        assertNotNull(c.typeLine());
        assertTrue(s.players().get(0).battlefield().contains(myPermanent.getId()));
    }

    @Test
    void grunddatenUndJson() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        assertEquals("state", s.type());
        assertEquals(me.getView().getId(), s.me());
        assertEquals(2, s.players().size());
        assertEquals("Ich", s.players().get(0).name());
        assertEquals(40, s.players().get(0).life());
        JsonNode json = Json.parse(Json.toJson(s));
        assertEquals("state", json.get("type").asText());
        assertTrue(json.get("cards").has(String.valueOf(myHandCard.getId())));
        assertFalse(json.get("cards").get(String.valueOf(foeHandCard.getId())).has("name"), "null-Felder werden weggelassen");
    }

    @Test
    void promptUndSelectableWerdenDurchgereicht() {
        Snapshot.PromptSnap p = new Snapshot.PromptSnap("Wähle", null, "Keep", "Mulligan", true, true);
        ViewContext ctx = new ViewContext(me.getView(), c -> c.canBeShownTo(me.getView()),
                c -> c.getId() == myHandCard.getId(), c -> false, e -> false, p);
        Snapshot s = StateSerializer.snapshot(game.getView(), ctx);
        assertEquals("Keep", s.prompt().okLabel());
        assertEquals(Boolean.TRUE, s.cards().get(myHandCard.getId()).selectable());
        assertNull(s.cards().get(myPermanent.getId()).selectable());
    }
}
```

- [ ] **Step 4: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=StateSerializerTest`
Expected: COMPILATION ERROR, `StateSerializer` nicht gefunden.

- [ ] **Step 5: StateSerializer schreiben**

`bridge/src/main/java/mtgplayer/protocol/StateSerializer.java`:

```java
package mtgplayer.protocol;

import com.google.common.collect.Multiset;
import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Übersetzt Forges Trackable-Views in einen {@link Snapshot}. Reine Funktion, kein Zustand. */
public final class StateSerializer {

    private StateSerializer() { }

    public static Snapshot snapshot(GameView gv, ViewContext ctx) {
        Map<Integer, Snapshot.CardSnap> cards = new LinkedHashMap<>();
        List<Snapshot.PlayerSnap> players = new ArrayList<>();
        FCollectionView<PlayerView> pvs = gv.getPlayers();
        Integer priority = null;
        if (pvs != null) {
            for (PlayerView p : pvs) {
                players.add(player(p, pvs, ctx, cards));
                if (p.getHasPriority()) {
                    priority = p.getId();
                }
            }
        }
        List<Snapshot.StackSnap> stack = new ArrayList<>();
        FCollectionView<StackItemView> items = gv.getStack();
        if (items != null) {
            int i = 0;
            for (StackItemView si : items) {
                stack.add(stackItem(i++, si, ctx, cards));
            }
        }
        PlayerView turn = gv.getPlayerTurn();
        return new Snapshot(
                Snapshot.TYPE,
                gv.getTurn(),
                gv.getPhase() == null ? null : gv.getPhase().name(),
                turn == null ? null : turn.getId(),
                priority,
                ctx.me() == null ? null : ctx.me().getId(),
                gv.isGameOver(),
                players,
                stack,
                cards,
                ctx.prompt());
    }

    private static Snapshot.PlayerSnap player(PlayerView p, FCollectionView<PlayerView> all, ViewContext ctx,
                                              Map<Integer, Snapshot.CardSnap> cards) {
        Map<Integer, Integer> cmdDamage = new LinkedHashMap<>();
        for (PlayerView other : all) {
            List<CardView> commanders = other.getCommanders();
            if (commanders == null) continue;
            for (CardView cmd : commanders) {
                int dmg = p.getCommanderDamage(cmd);
                if (dmg > 0) {
                    cmdDamage.put(cmd.getId(), dmg);
                }
            }
        }
        Map<String, Integer> mana = new LinkedHashMap<>();
        mana.put("W", p.getMana(MagicColor.WHITE));
        mana.put("U", p.getMana(MagicColor.BLUE));
        mana.put("B", p.getMana(MagicColor.BLACK));
        mana.put("R", p.getMana(MagicColor.RED));
        mana.put("G", p.getMana(MagicColor.GREEN));
        mana.put("C", p.getMana(MagicColor.COLORLESS));

        FCollectionView<CardView> library = p.getLibrary();
        return new Snapshot.PlayerSnap(
                p.getId(),
                p.getName(),
                p.isAI(),
                p.getLife(),
                counters(p.getCounters()),
                cmdDamage,
                ids(p.getHand(), ctx, cards),
                library == null ? 0 : library.size(),
                ids(p.getGraveyard(), ctx, cards),
                ids(p.getExile(), ctx, cards),
                ids(p.getCommand(), ctx, cards),
                ids(p.getBattlefield(), ctx, cards),
                mana,
                p.getHasPriority());
    }

    /** Sammelt IDs einer Zone und legt jede Karte einmal im Wörterbuch ab. */
    private static List<Integer> ids(Iterable<CardView> zone, ViewContext ctx, Map<Integer, Snapshot.CardSnap> cards) {
        List<Integer> out = new ArrayList<>();
        if (zone == null) return out;
        for (CardView cv : zone) {
            out.add(cv.getId());
            cards.computeIfAbsent(cv.getId(), id -> card(cv, ctx));
        }
        return out;
    }

    private static Snapshot.CardSnap card(CardView cv, ViewContext ctx) {
        if (!ctx.mayView().test(cv)) {
            return Snapshot.CardSnap.hidden(cv.getId());
        }
        CardStateView st = cv.getCurrentState();
        boolean creature = st.isCreature();
        List<Integer> attachments = new ArrayList<>();
        if (cv.hasCardAttachments()) {
            for (CardView a : cv.getAttachedCards()) {
                attachments.add(a.getId());
            }
        }
        CardView attachedTo = cv.getAttachedTo();
        boolean onBattlefield = cv.getZone() != null && cv.getZone().name().equals("Battlefield");
        return new Snapshot.CardSnap(
                cv.getId(),
                false,
                st.getName(),
                st.getImageKey(),
                cv.getController() == null ? null : cv.getController().getId(),
                cv.getOwner() == null ? null : cv.getOwner().getId(),
                cv.getZone() == null ? null : cv.getZone().name(),
                onBattlefield ? cv.isTapped() : null,
                onBattlefield && creature ? cv.isSick() : null,
                creature ? st.getPower() : null,
                creature ? st.getToughness() : null,
                onBattlefield ? cv.getDamage() : null,
                counters(cv.getCounters()),
                attachedTo == null ? null : attachedTo.getId(),
                attachments.isEmpty() ? null : attachments,
                cv.getText(),
                st.getType() == null ? null : st.getType().toString(),
                st.getManaCost() == null || st.getManaCost().isNoCost() ? null : st.getManaCost().getShortString(),
                cv.isAttacking() ? Boolean.TRUE : null,
                cv.isBlocking() ? Boolean.TRUE : null,
                cv.isToken() ? Boolean.TRUE : null,
                ctx.selectable().test(cv) ? Boolean.TRUE : null,
                ctx.weaklySelectable().test(cv) ? Boolean.TRUE : null,
                ctx.highlighted().test(cv) ? Boolean.TRUE : null);
    }

    private static Map<String, Integer> counters(Multiset<CounterType> counters) {
        if (counters == null || counters.isEmpty()) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Multiset.Entry<CounterType> e : counters.entrySet()) {
            if (e.getCount() > 0) {
                out.put(e.getElement().getName(), e.getCount());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Snapshot.StackSnap stackItem(int index, StackItemView si, ViewContext ctx,
                                                Map<Integer, Snapshot.CardSnap> cards) {
        CardView src = si.getSourceCard();
        if (src != null) {
            cards.computeIfAbsent(src.getId(), id -> card(src, ctx));
        }
        List<Integer> tc = new ArrayList<>();
        if (si.getTargetCards() != null) {
            for (CardView c : si.getTargetCards()) {
                tc.add(c.getId());
                cards.computeIfAbsent(c.getId(), id -> card(c, ctx));
            }
        }
        List<Integer> tp = new ArrayList<>();
        if (si.getTargetPlayers() != null) {
            for (PlayerView p : si.getTargetPlayers()) {
                tp.add(p.getId());
            }
        }
        return new Snapshot.StackSnap(
                index,
                si.getText(),
                src == null ? null : src.getId(),
                si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getId(),
                tc,
                tp);
    }
}
```

Hinweis für den Implementierer: `PlayerView.getCounters()`, `CardView.getCounters()`, `getAttachedCards()`, `hasCardAttachments()` kommen aus `forge.game.GameEntityView`; `CardView.getText()` liefert den Regeltext inkl. Änderungen; `CardStateView.getType()` ist ein `CardTypeView` mit sinnvollem `toString()`. Sollte eine dieser Methoden im Compiler fehlen, im Submodule nachsehen (`forge/forge-game/src/main/java/forge/game/card/CardView.java`, `.../GameEntityView.java`) und den nächstliegenden Getter nehmen – den Unterschied im Report notieren.

- [ ] **Step 6: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=StateSerializerTest`
Expected: `Tests run: 5, Failures: 0`. Wenn `canBeShownTo` für die gegnerische Handkarte `true` liefert (Test 2 rot): prüfen, ob `Card.fromPaperCard` die Zone gesetzt hat (`cv.getZone()` muss `Hand` sein) – sonst `foe.getZone(ZoneType.Hand).add(...)` vor dem Snapshot erneut aufrufen.

- [ ] **Step 7: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/pom.xml bridge/src
git commit -m "m2: protokoll-records und stateserializer mit sichtbarkeitsfilter

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: ChoiceBroker und WebGuiGame

**Files:**
- Create: `bridge/src/main/java/mtgplayer/protocol/Messages.java`
- Create: `bridge/src/main/java/mtgplayer/gui/Transport.java`
- Create: `bridge/src/main/java/mtgplayer/gui/ChoiceBroker.java`
- Create: `bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`
- Test: `bridge/src/test/java/mtgplayer/gui/ChoiceBrokerTest.java`
- Test: `bridge/src/test/java/mtgplayer/gui/WebGuiGameTest.java`

**Interfaces:**
- Consumes: `Snapshot`, `ViewContext`, `StateSerializer.snapshot(...)`, `Json`.
- Produces:
  - `Transport { void send(Object message); }` – `message` wird mit `Json.toJson` serialisiert.
  - `Messages.Lobby(String type="lobby", List<String> precons)`, `Messages.Choice(...)`, `Messages.Option(int index, String label, Integer card, Integer player)`, `Messages.LogLine(String type="log", String text)`, `Messages.GameOver(String type="gameOver", String winner)`, `Messages.ErrorMsg(String type="error", String text)`.
  - `ChoiceBroker(Transport out)`; `JsonNode ask(String kind, String title, String message, List<Option> options, int min, int max, Integer card)` blockiert; `boolean answer(int id, JsonNode value)`; `Optional<Messages.Choice> pending()`; `void cancelAll()`.
  - `WebGuiGame(Transport out)` extends `AbstractGuiGame`; Eingaben: `void onSelectCard(int cardId, boolean alt)`, `void onSelectPlayer(int playerId)`, `void onOk()`, `void onCancel()`, `void onConcede()`, `void pushState()`; `ChoiceBroker broker()`.

- [ ] **Step 1: Messages und Transport schreiben**

`bridge/src/main/java/mtgplayer/protocol/Messages.java`:

```java
package mtgplayer.protocol;

import java.util.List;

/** Alle Nachrichten außer {@link Snapshot}. Jedes Record trägt sein {@code type} selbst. */
public final class Messages {

    private Messages() { }

    public record Lobby(String type, List<String> precons) {
        public Lobby(List<String> precons) { this("lobby", precons); }
    }

    public record Option(int index, String label, Integer card, Integer player) { }

    /**
     * kind: one | many | order | confirm | number | text | ability | entities
     * options: Auswahl mit Index; value der Antwort ist je nach kind
     * Index (one/ability), Index-Liste (many/entities/order), bool (confirm), Zahl (number), String (text).
     */
    public record Choice(String type, int id, String kind, String title, String message,
                         List<Option> options, int min, int max, Integer card) {
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max, Integer card) {
            this("choice", id, kind, title, message, options, min, max, card);
        }
    }

    public record LogLine(String type, String text) {
        public LogLine(String text) { this("log", text); }
    }

    public record GameOver(String type, String winner) {
        public GameOver(String winner) { this("gameOver", winner); }
    }

    public record ErrorMsg(String type, String text) {
        public ErrorMsg(String text) { this("error", text); }
    }
}
```

`bridge/src/main/java/mtgplayer/gui/Transport.java`:

```java
package mtgplayer.gui;

/** Ausgang zum Browser. Implementierungen müssen thread-sicher sein (Game-, UI- und Socket-Thread senden). */
public interface Transport {
    void send(Object message);
}
```

- [ ] **Step 2: Failing Test für den ChoiceBroker**

`bridge/src/test/java/mtgplayer/gui/ChoiceBrokerTest.java`:

```java
package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class ChoiceBrokerTest {

    private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final ChoiceBroker broker = new ChoiceBroker(m -> sent.add(Json.toJson(m)));

    @Test
    void askSendetChoiceUndBlockiertBisAnswer() throws Exception {
        List<Messages.Option> opts = List.of(new Messages.Option(0, "A", null, null), new Messages.Option(1, "B", null, null));
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "Titel", "Frage", opts, 1, 1, null));

        JsonNode msg = Json.parse(sent.poll(5, TimeUnit.SECONDS));
        assertEquals("choice", msg.get("type").asText());
        assertEquals("one", msg.get("kind").asText());
        assertEquals(2, msg.get("options").size());
        int id = msg.get("id").asInt();
        assertFalse(result.isDone(), "muss auf die Antwort warten");
        assertTrue(broker.pending().isPresent());

        assertTrue(broker.answer(id, Json.parse("1")));
        assertEquals(1, result.get(5, TimeUnit.SECONDS).asInt());
        assertTrue(broker.pending().isEmpty());
    }

    @Test
    void doppelteOderUnbekannteAntwortWirdIgnoriert() throws Exception {
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("confirm", "T", "?", List.of(), 0, 0, null));
        int id = Json.parse(sent.poll(5, TimeUnit.SECONDS)).get("id").asInt();
        assertFalse(broker.answer(id + 99, Json.parse("true")));
        assertTrue(broker.answer(id, Json.parse("true")));
        assertFalse(broker.answer(id, Json.parse("false")), "zweite Antwort auf dieselbe id");
        assertTrue(result.get(5, TimeUnit.SECONDS).asBoolean());
    }

    @Test
    void cancelAllLoestOffeneFragenMitNullAuf() throws Exception {
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "T", "?", List.of(), 1, 1, null));
        sent.poll(5, TimeUnit.SECONDS);
        broker.cancelAll();
        assertTrue(result.get(5, TimeUnit.SECONDS).isNull());
    }
}
```

- [ ] **Step 3: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=ChoiceBrokerTest`
Expected: COMPILATION ERROR, `ChoiceBroker` nicht gefunden.

- [ ] **Step 4: ChoiceBroker schreiben**

`bridge/src/main/java/mtgplayer/gui/ChoiceBroker.java`:

```java
package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import mtgplayer.protocol.Messages;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchrone Forge-Dialoge auf dem Game-Thread werden hier zu einer {@code choice}-Nachricht
 * und blockieren, bis {@link #answer} mit derselben id kommt. Antworten sind idempotent.
 */
public final class ChoiceBroker {

    private final Transport out;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Messages.Choice current;

    public ChoiceBroker(Transport out) {
        this.out = out;
    }

    public JsonNode ask(String kind, String title, String message, List<Messages.Option> options, int min, int max, Integer card) {
        int id = seq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        Messages.Choice c = new Messages.Choice(id, kind, title, message, options, min, max, card);
        current = c;
        out.send(c);
        try {
            return f.join();
        } finally {
            pending.remove(id);
            if (current != null && current.id() == id) {
                current = null;
            }
        }
    }

    /** @return true, wenn eine offene Frage mit dieser id beantwortet wurde. */
    public boolean answer(int id, JsonNode value) {
        CompletableFuture<JsonNode> f = pending.get(id);
        if (f == null) return false;
        return f.complete(value == null ? NullNode.getInstance() : value);
    }

    /** Die aktuell offene Frage, damit ein neu verbundener Browser sie erneut bekommt. */
    public Optional<Messages.Choice> pending() {
        return Optional.ofNullable(current);
    }

    /** Beendet alle offenen Fragen mit {@code null} – z. B. bei Spielende. */
    public void cancelAll() {
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.complete(NullNode.getInstance());
        }
    }
}
```

- [ ] **Step 5: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=ChoiceBrokerTest`
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 6: Failing Test für WebGuiGame**

Diese Tests brauchen kein laufendes Spiel: sie prüfen die Übersetzung der Dialogaufrufe in Nachrichten und die Auto-Schadensverteilung. `CardView`-Parameter sind `null`, wo Forge das erlaubt.

`bridge/src/test/java/mtgplayer/gui/WebGuiGameTest.java`:

```java
package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class WebGuiGameTest {

    private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final WebGuiGame gui = new WebGuiGame(m -> sent.add(Json.toJson(m)));

    @BeforeAll
    static void boot() {
        ForgeBoot.init(); // Localizer für Button-Labels
    }

    private JsonNode nextChoice() throws InterruptedException {
        while (true) {
            String s = sent.poll(5, TimeUnit.SECONDS);
            if (s == null) throw new AssertionError("keine choice-Nachricht");
            JsonNode n = Json.parse(s);
            if ("choice".equals(n.get("type").asText())) return n;
        }
    }

    @Test
    void confirmWirdZuConfirmChoice() throws Exception {
        CompletableFuture<Boolean> r = CompletableFuture.supplyAsync(
                () -> gui.showConfirmDialog("Aufgeben?", "Concede", "Ja", "Nein", false));
        JsonNode c = nextChoice();
        assertEquals("confirm", c.get("kind").asText());
        assertEquals("Aufgeben?", c.get("message").asText());
        assertEquals("Ja", c.get("options").get(0).get("label").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("true"));
        assertTrue(r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void getChoicesLiefertGewaehlteObjekteInAntwortReihenfolge() throws Exception {
        List<String> opts = List.of("Rot", "Grün", "Blau");
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Farbe", 1, 2, opts, null, null));
        JsonNode c = nextChoice();
        assertEquals("many", c.get("kind").asText());
        assertEquals(1, c.get("min").asInt());
        assertEquals(2, c.get("max").asInt());
        assertEquals("Grün", c.get("options").get(1).get("label").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[2,0]"));
        assertEquals(List.of("Blau", "Rot"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void getChoicesMitMaxEinsIstKindOne() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Eins", 1, 1, List.of("a", "b"), null, null));
        JsonNode c = nextChoice();
        assertEquals("one", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("1"));
        assertEquals(List.of("b"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void nullAntwortLiefertMinimalauswahl() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Zwang", 1, 1, List.of("x", "y"), null, null));
        JsonNode c = nextChoice();
        gui.broker().answer(c.get("id").asInt(), null);
        assertEquals(List.of("x"), r.get(5, TimeUnit.SECONDS), "bei null: die ersten min Einträge");
    }

    @Test
    void orderLiefertNeueReihenfolge() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.order("Sortieren", "oben", 0, 0, List.of("a", "b", "c"), null, null, false, false).ordered());
        JsonNode c = nextChoice();
        assertEquals("order", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[2,1,0]"));
        assertEquals(List.of("c", "b", "a"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void showOptionDialogLiefertIndex() throws Exception {
        CompletableFuture<Integer> r = CompletableFuture.supplyAsync(
                () -> gui.showOptionDialog("Frage", "Titel", null, List.of("A", "B", "C"), 0));
        JsonNode c = nextChoice();
        assertEquals("one", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("2"));
        assertEquals(2, r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void showInputDialogNumerisch() throws Exception {
        CompletableFuture<String> r = CompletableFuture.supplyAsync(
                () -> gui.showInputDialog("X?", "Titel", null, "0", null, true));
        JsonNode c = nextChoice();
        assertEquals("number", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("7"));
        assertEquals("7", r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void isUiSetToSkipPhaseIstInM2Falsch() {
        assertFalse(gui.isUiSetToSkipPhase(null, null));
    }
}
```

- [ ] **Step 7: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=WebGuiGameTest`
Expected: COMPILATION ERROR, `WebGuiGame` nicht gefunden.

- [ ] **Step 8: WebGuiGame schreiben**

`bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`:

```java
package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gui.GuiBase;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableTypes;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import mtgplayer.protocol.Messages;
import mtgplayer.protocol.Snapshot;
import mtgplayer.protocol.StateSerializer;
import mtgplayer.protocol.ViewContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forges GUI-Schnittstelle für einen Browser-Sitz. Zustandsänderungen werden gebündelt als
 * {@link Snapshot} gepusht, synchrone Dialoge laufen über den {@link ChoiceBroker}.
 * Eingaben aus dem Browser kommen über die on*-Methoden – die müssen auf dem UI-Thread
 * aufgerufen werden ({@code GuiBase.getInterface().invokeInEdtLater}).
 */
public class WebGuiGame extends AbstractGuiGame {

    private final Transport out;
    private final ChoiceBroker broker;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile Snapshot.PromptSnap prompt = Snapshot.PromptSnap.EMPTY;

    public WebGuiGame(Transport out) {
        this.out = out;
        this.broker = new ChoiceBroker(out);
    }

    public ChoiceBroker broker() {
        return broker;
    }

    // ---- Zustand raus -------------------------------------------------------------

    /** Bündelt beliebig viele Updates zu einem Push auf dem UI-Thread. */
    private void push() {
        if (getGameView() == null) return;
        if (dirty.compareAndSet(false, true)) {
            GuiBase.getInterface().invokeInEdtLater(() -> {
                dirty.set(false);
                pushState();
            });
        }
    }

    /** Sofort einen Snapshot senden (z. B. nach Reconnect). Auf dem UI-Thread aufrufen. */
    public void pushState() {
        GameView gv = getGameView();
        if (gv == null) return;
        PlayerView me = getLocalPlayers().isEmpty() ? null : getLocalPlayers().iterator().next();
        ViewContext ctx = new ViewContext(me, this::mayView, this::isSelectable, this::isWeaklySelectable,
                this::isHighlighted, prompt);
        out.send(StateSerializer.snapshot(gv, ctx));
    }

    @Override public void setGameView(GameView gameView0) { super.setGameView(gameView0); push(); }
    @Override protected void updateCurrentPlayer(PlayerView player) { push(); }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { push(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { push(); }
    @Override public void updateCards(Iterable<CardView> cards) { push(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { push(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { push(); }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { push(); }
    @Override public void updateStack() { push(); }
    @Override public void updatePhase(boolean saveState) { push(); }
    @Override public void updateTurn(PlayerView player) { push(); }
    @Override public void updatePlayerControl() { push(); }
    @Override public void refreshField() { push(); }
    @Override public void showCombat() { push(); }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void flashIncorrectAction() { out.send(new Messages.ErrorMsg("Das geht gerade nicht.")); }
    @Override public void alertUser() { }
    @Override public void showManaPool(PlayerView player) { push(); }
    @Override public void hideManaPool(PlayerView player) { push(); }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) { super.setSelectables(cards, min, max); push(); }
    @Override public void clearSelectables() { super.clearSelectables(); push(); }
    @Override public void setWeaklySelectable(Iterable<CardView> cards) { super.setWeaklySelectable(cards); push(); }
    @Override public void clearWeaklySelectable() { super.clearWeaklySelectable(); push(); }
    @Override public void setHighlighted(Iterable<GameEntityView> entities, boolean b) { super.setHighlighted(entities, b); push(); }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(message == null ? "" : message, card == null ? null : card.getId(),
                p.okLabel(), p.cancelLabel(), p.okEnabled(), p.cancelEnabled());
        push();
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(p.message(), p.card(), label1, label2, enable1, enable2);
        push();
    }

    @Override
    public void finishGame() {
        GameView gv = getGameView();
        String winner = gv == null ? null : gv.getWinningPlayerName();
        broker.cancelAll();
        push();
        out.send(new Messages.GameOver(winner));
    }

    @Override public void afterGameEnd() { }

    // ---- Eingaben rein (UI-Thread) ------------------------------------------------

    private CardView cardById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.CardViewType, id);
    }

    private PlayerView playerById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.PlayerViewType, id);
    }

    private static ITriggerEvent trigger(boolean alt) {
        return new ITriggerEvent() {
            @Override public int getButton() { return alt ? 3 : 1; }
            @Override public int getX() { return 0; }
            @Override public int getY() { return 0; }
        };
    }

    public void onSelectCard(int cardId, boolean alt) {
        CardView cv = cardById(cardId);
        IGameController c = getGameController();
        if (cv == null || c == null) return;
        if (!c.selectCard(cv, null, trigger(alt))) {
            flashIncorrectAction();
        }
    }

    public void onSelectPlayer(int playerId) {
        PlayerView pv = playerById(playerId);
        IGameController c = getGameController();
        if (pv == null || c == null) return;
        c.selectPlayer(pv, trigger(false));
    }

    public void onOk() {
        IGameController c = getGameController();
        if (c != null) c.selectButtonOk();
    }

    public void onCancel() {
        IGameController c = getGameController();
        if (c != null) c.selectButtonCancel();
    }

    public void onConcede() {
        concede();
    }

    // ---- Synchrone Dialoge (Game-Thread) -------------------------------------------

    private static <T> List<Messages.Option> options(List<T> choices, FSerializableFunction<T, String> display) {
        List<Messages.Option> out = new ArrayList<>();
        if (choices == null) return out;
        int i = 0;
        for (T t : choices) {
            String label = display != null ? display.apply(t) : String.valueOf(t);
            Integer card = t instanceof CardView cv ? cv.getId() : null;
            Integer player = t instanceof PlayerView pv ? pv.getId() : null;
            if (t instanceof SpellAbilityView sav && sav.getHostCard() != null) {
                card = sav.getHostCard().getId();
            }
            out.add(new Messages.Option(i++, label, card, player));
        }
        return out;
    }

    /** Index-Liste aus der Antwort; bei null/ungültig die ersten {@code min} Einträge. */
    private static List<Integer> indices(JsonNode value, int min, int size) {
        List<Integer> out = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode n : value) {
                int i = n.asInt(-1);
                if (i >= 0 && i < size && !out.contains(i)) out.add(i);
            }
        } else if (value != null && value.isInt()) {
            int i = value.asInt();
            if (i >= 0 && i < size) out.add(i);
        }
        if (out.size() < Math.max(min, 0)) {
            out.clear();
            for (int i = 0; i < Math.min(Math.max(min, 0), size); i++) out.add(i);
        }
        return out;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected,
                                  FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        // Forge nutzt min == max == -1 für "nur anzeigen" (reveal)
        if (min < 0 && max < 0) {
            broker.ask("many", message, message, options(choices, display), 0, 0, null);
            return new ArrayList<>();
        }
        String kind = max == 1 ? "one" : "many";
        JsonNode v = broker.ask(kind, message, message, options(choices, display), min, max, null);
        List<T> out = new ArrayList<>();
        for (int i : indices(v, min, choices.size())) out.add(choices.get(i));
        return out;
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> src = sourceChoices == null ? new ArrayList<>() : sourceChoices;
        if (src.isEmpty()) return new OrderResult<>(new ArrayList<>(src), false);
        JsonNode v = broker.ask("order", title, top, options(src, null), src.size(), src.size(),
                referenceCard == null ? null : referenceCard.getId());
        List<Integer> idx = indices(v, src.size(), src.size());
        List<T> ordered = new ArrayList<>();
        for (int i : idx) ordered.add(src.get(i));
        for (T t : src) if (!ordered.contains(t)) ordered.add(t); // Sicherheitsnetz bei unvollständiger Antwort
        return new OrderResult<>(ordered, false);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        List<Messages.Option> opts = List.of(new Messages.Option(0, yesButtonText, null, null),
                new Messages.Option(1, noButtonText, null, null));
        JsonNode v = broker.ask("confirm", title, message, opts, 1, 1, null);
        return v == null || v.isNull() ? defaultYes : v.asBoolean(defaultYes);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        String yes = options != null && options.size() > 0 ? options.get(0) : "Yes";
        String no = options != null && options.size() > 1 ? options.get(1) : "No";
        List<Messages.Option> opts = List.of(new Messages.Option(0, yes, null, null), new Messages.Option(1, no, null, null));
        JsonNode v = broker.ask("confirm", c == null ? "" : c.getCurrentState().getName(), question, opts, 1, 1,
                c == null ? null : c.getId());
        return v == null || v.isNull() ? defaultIsYes : v.asBoolean(defaultIsYes);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        JsonNode v = broker.ask("one", title, message, options(options, null), 1, 1, null);
        List<Integer> idx = indices(v, 0, options.size());
        return idx.isEmpty() ? Math.max(0, defaultOption) : idx.get(0);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput,
                                  List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            JsonNode v = broker.ask("one", title, message, options(inputOptions, null), 1, 1, null);
            List<Integer> idx = indices(v, 0, inputOptions.size());
            return idx.isEmpty() ? (initialInput == null ? inputOptions.get(0) : initialInput) : inputOptions.get(idx.get(0));
        }
        JsonNode v = broker.ask(isNumeric ? "number" : "text", title, message, List.of(), 0, 0, null);
        if (v == null || v.isNull()) return initialInput == null ? (isNumeric ? "0" : "") : initialInput;
        return v.isNumber() ? String.valueOf(v.asInt()) : v.asText();
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        JsonNode v = broker.ask("ability", hostCard == null ? "" : hostCard.getCurrentState().getName(),
                "Welche Fähigkeit?", options(abilities, null), 0, 1, hostCard == null ? null : hostCard.getId());
        List<Integer> idx = indices(v, 0, abilities.size());
        return idx.isEmpty() ? null : abilities.get(idx.get(0));
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                                                      DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), isOptional ? 0 : 1, 1, null);
        List<Integer> idx = indices(v, isOptional ? 0 : 1, list.size());
        return idx.isEmpty() ? null : list.get(idx.get(0));
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                                                        int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) return new ArrayList<>();
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), min, max, null);
        List<GameEntityView> out = new ArrayList<>();
        for (int i : indices(v, min, list.size())) out.add(list.get(i));
        return out;
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> out = new ArrayList<>();
        if (cards != null) for (CardView c : cards) out.add(c);
        return out; // M2: unverändert lassen; Dialog kommt in M3
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return new ArrayList<>(); // kein Sideboarding
    }

    /**
     * M2: automatische Verteilung – jedem Blocker in Reihenfolge tödlichen Schaden, Rest auf den letzten
     * (bzw. bei Trample auf den Verteidiger, Schlüssel null). Dialog kommt in M3.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        int rest = damage;
        if (blockers != null) {
            for (CardView b : blockers) {
                int lethal = Math.max(1, b.getLethalDamage());
                int give = Math.min(lethal, rest);
                out.put(b, give);
                rest -= give;
                if (rest <= 0) break;
            }
        }
        if (rest > 0) {
            boolean trample = attacker != null && attacker.getCurrentState().hasTrample();
            if (trample && defender != null) {
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
        Object first = target.keySet().iterator().next();
        for (Object k : target.keySet()) out.put(k, 0);
        out.put(first, amount);
        return out; // M2: alles auf den ersten Eintrag; Dialog kommt in M3
    }

    // ---- Sonstiges ------------------------------------------------------------------

    @Override public void message(String message, String title) { out.send(new Messages.LogLine(title + ": " + message)); }
    @Override public void showErrorDialog(String message, String title) { out.send(new Messages.ErrorMsg(title + ": " + message)); }

    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones, Map<PlayerView, Object> players, boolean backupLastZones) {
        return null;
    }
    @Override public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) { }
    @Override public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { return zonesToUpdate; }
    @Override public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public GameState getGamestate() { return null; }

    /** M2: keine Stops – Forge passt per YIELD_AUTO_PASS_NO_ACTIONS selbst, wenn nichts spielbar ist. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        return false;
    }
}
```

Hinweis: `AbstractGuiGame` implementiert bereits `one`, `oneOrNone`, `many`, `getInteger`, `insertInList`, `reveal`, `concede`, `awaitNextInput`, `applyDelta`, `handleGameEvent`, `notifyStackAddition/Removal`, `handleLandPlayed`, `isNetGame/setNetGame`, `setGameSpeed`, `showWaitingTimer`. Meldet der Compiler eine weitere fehlende abstrakte Methode, im Submodule `forge/forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java` nachsehen und sie als No-Op/Default ergänzen – im Report auflisten.

- [ ] **Step 9: Tests laufen lassen, müssen bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='ChoiceBrokerTest,WebGuiGameTest'`
Expected: `Tests run: 11, Failures: 0`.

- [ ] **Step 10: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m2: webguigame auf abstractguigame, choicebroker fuer synchrone dialoge

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: HumanMatch, WebSocket-Server, Bridge, Main – End-to-End ohne Browser

**Files:**
- Modify: `bridge/pom.xml` (Java-WebSocket)
- Modify: `bridge/src/main/java/mtgplayer/forge/ForgeBoot.java` (Prefs)
- Create: `bridge/src/main/java/mtgplayer/match/HumanMatch.java`
- Create: `bridge/src/main/java/mtgplayer/server/WsServer.java`
- Create: `bridge/src/main/java/mtgplayer/server/HttpStatic.java`
- Create: `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Modify: `bridge/src/main/java/mtgplayer/Main.java`
- Test: `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`

**Interfaces:**
- Consumes: `WebGuiGame`, `ChoiceBroker`, `Messages`, `Json`, `Precons`, `ForgeBoot`.
- Produces:
  - `HumanMatch.start(String humanName, Deck humanDeck, List<Deck> aiDecks, List<String> aiNames, WebGuiGame gui)`; `HumanMatch.end()`.
  - `Bridge(int wsPort)`; `start()`, `stop()`; `Bridge.handle(JsonNode msg)` (paketprivat für Tests).
  - `WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen)` implements `Transport`.
  - `HttpStatic(int port, Path dir)`; `start()`, `stop()`.

- [ ] **Step 1: Java-WebSocket in die pom.xml**

In `bridge/pom.xml` nach der Jackson-Dependency:

```xml
        <dependency>
            <groupId>org.java-websocket</groupId>
            <artifactId>Java-WebSocket</artifactId>
            <version>1.5.7</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-nop</artifactId>
            <version>2.0.13</version>
        </dependency>
```

(`slf4j-nop` verhindert die "no SLF4J providers"-Warnung von Java-WebSocket. Falls Forge bereits einen SLF4J-Provider mitbringt und Maven einen Konflikt meldet, `slf4j-nop` wieder entfernen.)

- [ ] **Step 2: Prefs in ForgeBoot**

In `bridge/src/main/java/mtgplayer/forge/ForgeBoot.java` das `adjustPrefs`-Lambda in `init()` ersetzen durch:

```java
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            // menschlicher Sitz: Arena-Stil (Forge passt, wenn nichts spielbar ist) + spielbare Karten markieren
            prefs.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
            // headless: keine Musik/Sounds, kein Namensdialog beim Spielstart
            prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
            prefs.setPref(FPref.PLAYER_NAME, "Du");
            return null;
        });
```

- [ ] **Step 3: HumanMatch schreiben**

`bridge/src/main/java/mtgplayer/match/HumanMatch.java`:

```java
package mtgplayer.match;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.LobbyPlayerHuman;
import mtgplayer.gui.WebGuiGame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Commander-Spiel mit genau einem menschlichen Sitz (Browser) und 1–5 KIs über Forges
 * HostedMatch. startMatch kehrt sofort zurück; das Spiel läuft auf Forges Game-Thread und
 * spricht über die WebGuiGame.
 */
public final class HumanMatch {

    private HostedMatch hosted;

    public void start(String humanName, Deck humanDeck, List<Deck> aiDecks, List<String> aiNames, WebGuiGame gui) {
        if (aiDecks.size() != aiNames.size() || aiDecks.isEmpty() || aiDecks.size() > 5) {
            throw new IllegalArgumentException("1–5 KI-Decks mit gleich vielen Namen");
        }
        end();
        List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer human = RegisteredPlayer.forCommander(humanDeck);
        human.setPlayer(new LobbyPlayerHuman(humanName));
        players.add(human);
        for (int i = 0; i < aiDecks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(aiDecks.get(i));
            rp.setPlayer(new LobbyPlayerAi(aiNames.get(i), null));
            players.add(rp);
        }
        Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        guis.put(human, gui);

        GameRules rules = new GameRules(GameType.Commander);
        rules.setGamesPerMatch(1);
        hosted = new HostedMatch();
        hosted.startMatch(rules, null, players, guis, null);
    }

    public boolean isRunning() {
        return hosted != null && hosted.getGame() != null && !hosted.getGame().isGameOver();
    }

    public void end() {
        if (hosted != null) {
            hosted.endCurrentGame();
            hosted = null;
        }
    }
}
```

- [ ] **Step 4: WsServer und HttpStatic schreiben**

`bridge/src/main/java/mtgplayer/server/WsServer.java`:

```java
package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.gui.Transport;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Genau ein Browser-Client. Nachrichten vom Client gehen als JsonNode an {@code inbound};
 * {@link #send} ist thread-sicher (Java-WebSocket serialisiert intern). Ohne Client wird
 * gesendetes still verworfen – der Browser holt sich den Zustand per requestState.
 */
public final class WsServer extends WebSocketServer implements Transport {

    private final Consumer<JsonNode> inbound;
    private final Runnable onOpen;
    private volatile WebSocket client;

    public WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.inbound = inbound;
        this.onOpen = onOpen;
        setReuseAddr(true);
    }

    @Override
    public void send(Object message) {
        WebSocket c = client;
        if (c != null && c.isOpen()) {
            c.send(Json.toJson(message));
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        WebSocket old = client;
        client = conn;
        if (old != null && old.isOpen() && old != conn) {
            old.close(1000, "neuer Client");
        }
        onOpen.run();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (client == conn) client = null;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            inbound.accept(Json.parse(message));
        } catch (RuntimeException e) {
            conn.send(Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            e.printStackTrace();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket auf ws://127.0.0.1:" + getPort());
    }
}
```

`bridge/src/main/java/mtgplayer/server/HttpStatic.java`:

```java
package mtgplayer.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Liefert das gebaute Frontend (web/dist). Unbekannte Pfade fallen auf index.html zurück (SPA). */
public final class HttpStatic {

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript", "css", "text/css",
            "json", "application/json", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    private final HttpServer server;
    private final Path dir;

    public HttpStatic(int port, Path dir) throws IOException {
        this.dir = dir;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            Path f = dir.resolve(p.substring(1)).normalize();
            if (!f.startsWith(dir) || !Files.isRegularFile(f)) {
                f = dir.resolve("index.html");
            }
            if (!Files.isRegularFile(f)) {
                byte[] msg = "web/dist fehlt – erst `npm run build` in web/ oder Vite-Dev-Server auf :5173 nutzen".getBytes();
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            String name = f.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            ex.getResponseHeaders().add("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
            byte[] body = Files.readAllBytes(f);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
    }

    public void start() {
        server.start();
        System.out.println("HTTP auf http://127.0.0.1:" + server.getAddress().getPort() + " (" + dir + ")");
    }

    public void stop() {
        server.stop(0);
    }
}
```

- [ ] **Step 5: Bridge schreiben**

`bridge/src/main/java/mtgplayer/server/Bridge.java`:

```java
package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.gui.GuiBase;
import mtgplayer.forge.Precons;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.HumanMatch;
import mtgplayer.protocol.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Verdrahtet WebSocket ↔ WebGuiGame/HumanMatch. Eingaben, die Forges Input-System berühren,
 * laufen über den UI-Thread; answer geht direkt an den ChoiceBroker (der Game-Thread wartet darauf).
 */
public final class Bridge {

    private final WsServer ws;
    private final WebGuiGame gui;
    private final HumanMatch match = new HumanMatch();

    public Bridge(int wsPort) {
        this.ws = new WsServer(wsPort, this::handle, this::onClientConnected);
        this.gui = new WebGuiGame(ws);
    }

    public void start() {
        ws.start();
    }

    public void stop() throws InterruptedException {
        match.end();
        ws.stop(1000);
    }

    private void onClientConnected() {
        ws.send(new Messages.Lobby(Precons.names()));
        GuiBase.getInterface().invokeInEdtLater(gui::pushState);
        gui.broker().pending().ifPresent(ws::send);
    }

    void handle(JsonNode msg) {
        String type = msg.path("type").asText("");
        switch (type) {
            case "startGame" -> startGame(msg);
            case "selectCard" -> ui(() -> gui.onSelectCard(msg.path("id").asInt(), msg.path("alt").asBoolean(false)));
            case "selectPlayer" -> ui(() -> gui.onSelectPlayer(msg.path("id").asInt()));
            case "ok" -> ui(gui::onOk);
            case "cancel" -> ui(gui::onCancel);
            case "answer" -> gui.broker().answer(msg.path("id").asInt(), msg.get("value"));
            case "concede" -> ui(gui::onConcede);
            case "requestState" -> onClientConnected();
            default -> ws.send(new Messages.ErrorMsg("unbekannter Nachrichtentyp: " + type));
        }
    }

    private static void ui(Runnable r) {
        GuiBase.getInterface().invokeInEdtLater(r);
    }

    /**
     * {"type":"startGame","humanDeck":{"precon":"..."},"opponents":[{"precon":"...","name":"KI 1"}]}
     * M2 kennt nur Precons; Textlisten kommen in M4.
     */
    private void startGame(JsonNode msg) {
        Deck human = deck(msg.path("humanDeck"));
        List<Deck> ai = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int i = 1;
        for (JsonNode o : msg.path("opponents")) {
            ai.add(deck(o));
            names.add(o.path("name").asText("KI " + i++));
        }
        ui(() -> {
            try {
                match.start("Du", human, ai, names, gui);
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Spielstart fehlgeschlagen: " + e));
                e.printStackTrace();
            }
        });
    }

    private static Deck deck(JsonNode node) {
        String precon = node.path("precon").asText(null);
        if (precon == null) {
            throw new IllegalArgumentException("Deck braucht ein Feld 'precon'");
        }
        return Precons.load(precon);
    }
}
```

- [ ] **Step 6: Main umbauen**

`bridge/src/main/java/mtgplayer/Main.java` komplett ersetzen:

```java
package mtgplayer;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import mtgplayer.server.Bridge;
import mtgplayer.server.HttpStatic;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Standard: Bridge-Server für den Browser (WebSocket 8081, HTTP 8080).
 * {@code --ai-demo [seed]}: vier zufällige Precons spielen headless (M1-Verhalten).
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        ForgeBoot.init();
        System.out.printf("%d Karten geladen in %.1f s%n", ForgeBoot.cardCount(), (System.currentTimeMillis() - t0) / 1000.0);

        if (args.length > 0 && args[0].equals("--ai-demo")) {
            aiDemo(args.length > 1 ? Long.parseLong(args[1]) : System.currentTimeMillis());
            return;
        }

        int wsPort = Integer.getInteger("mtgplayer.wsPort", 8081);
        int httpPort = Integer.getInteger("mtgplayer.httpPort", 8080);
        Path web = Paths.get(System.getProperty("mtgplayer.web", "../web/dist")).toAbsolutePath().normalize();

        Bridge bridge = new Bridge(wsPort);
        bridge.start();
        HttpStatic http = new HttpStatic(httpPort, web);
        http.start();
        System.out.println("Bereit. Browser: http://127.0.0.1:" + httpPort + "  (Dev: http://localhost:5173)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            try { bridge.stop(); } catch (InterruptedException ignored) { }
        }));
        Thread.currentThread().join();
    }

    private static void aiDemo(long seed) {
        List<String> names = new ArrayList<>(Precons.names());
        Collections.shuffle(names, new Random(seed));
        List<String> chosen = names.subList(0, 4);
        List<Deck> decks = chosen.stream().map(Precons::load).toList();
        System.out.println("Seed " + seed + ", Decks: " + chosen);
        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"), 60, System.out::println);
        System.out.println("=== Ergebnis: " + (r.winner() == null ? "unentschieden" : r.winner() + " gewinnt")
                + " (" + r.reason() + ") nach " + r.turns() + " Zuegen");
    }
}
```

- [ ] **Step 7: End-to-End-Test schreiben**

`bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`:

```java
package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Spielt die ersten Sekunden eines echten Spiels über das Protokoll: Lobby → Spielstart →
 * Mulligan-Prompt → Keep → Prio-Prompt in Zug 1 → Concede-Dialog → gameOver.
 */
class BridgeEndToEndTest {

    private static final int PORT = 18081;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        bridge = new Bridge(PORT);
        bridge.start();
        client = new WebSocketClient(new URI("ws://127.0.0.1:" + PORT)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { inbox.add(Json.parse(m)); }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { ex.printStackTrace(); }
        };
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "WebSocket-Verbindung");
    }

    @AfterAll
    static void stop() throws Exception {
        client.closeBlocking();
        bridge.stop();
    }

    private static JsonNode await(String type, Predicate<JsonNode> cond, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if ("error".equals(n.path("type").asText())) {
                System.err.println("[bridge error] " + n.path("text").asText());
            }
            if (type.equals(n.path("type").asText()) && cond.test(n)) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    private static void send(String json) {
        client.send(json);
    }

    private static JsonNode myPlayer(JsonNode state) {
        int me = state.get("me").asInt();
        for (JsonNode p : state.get("players")) {
            if (p.get("id").asInt() == me) return p;
        }
        throw new AssertionError("eigener Spieler fehlt im Snapshot");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void lobbyStartKeepPrioConcede() throws Exception {
        JsonNode lobby = await("lobby", n -> true, 10);
        assertTrue(lobby.get("precons").size() > 100);

        send("{\"type\":\"startGame\",\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
                + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\"}]}");

        JsonNode mull = await("state", n -> "Keep".equals(n.path("prompt").path("okLabel").asText()), 90);
        JsonNode me = myPlayer(mull);
        assertEquals(7, me.get("hand").size(), "Starthand");
        assertEquals(false, me.get("isAi").asBoolean());
        for (JsonNode id : me.get("hand")) {
            JsonNode card = mull.get("cards").get(id.asText());
            assertNotNull(card.get("name"), "eigene Handkarte sichtbar");
            assertFalse(card.has("faceDown"));
        }
        JsonNode foe = null;
        for (JsonNode p : mull.get("players")) if (p.get("isAi").asBoolean()) foe = p;
        assertNotNull(foe);
        assertEquals(40, foe.get("life").asInt());
        for (JsonNode id : foe.get("hand")) {
            JsonNode card = mull.get("cards").get(id.asText());
            assertTrue(card.path("faceDown").asBoolean(), "gegnerische Handkarte verdeckt");
            assertFalse(card.has("name"));
        }

        send("{\"type\":\"ok\"}");

        JsonNode prio = await("state", n -> n.path("turn").asInt() >= 1
                && n.path("prompt").path("okEnabled").asBoolean()
                && !"Keep".equals(n.path("prompt").path("okLabel").asText()), 120);
        assertNotNull(prio.get("phase"));
        assertTrue(prio.path("prompt").path("message").asText().length() > 0, "Prio-Prompt hat Text");

        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");

        JsonNode over = await("gameOver", n -> true, 60);
        assertNotNull(over);
    }
}
```

- [ ] **Step 8: Test laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn test -Dtest=BridgeEndToEndTest 2>&1 | tail -40`
Expected: `Tests run: 1, Failures: 0`.

Fehlerbilder und was sie bedeuten:
- Kein `state` mit `Keep`: Forge stellt den Mulligan-Prompt über `InputConfirmMulligan` → `updateButtons(..., "Keep", "Mulligan", ...)`. Prüfen, ob `HumanMatch` die `guis`-Map mit dem **selben** `RegisteredPlayer`-Objekt füllt, das in `players` steckt, und ob `WebGuiGame.updateButtons` wirklich `push()` aufruft. Auf stdout nach Exceptions aus `HostedMatch.startGame` suchen.
- `NullPointerException` in `GamePlayerUtil`/`FSkin`/`SoundSystem`: eine Forge-Stelle erwartet UI – Stacktrace lesen, die Methode in `WebGuiBase` mit einem Default versehen, im Report notieren.
- `state` kommt, aber `hand` ist leer: `mayView` liefert false für den eigenen Sitz → `setOriginalGameController` wurde nicht aufgerufen (dann ist `getLocalPlayers()` leer und `me == null`). Prüfen, ob `hosted.startMatch` die `guis`-Map bekommt.
- Kein `choice` nach `concede`: `AbstractGuiGame.concede()` zeigt bei laufendem Mulligan einen Fehlerdialog statt der Rückfrage – der Test sendet `concede` erst nach dem Prio-Prompt; falls die Bridge ihn früher verarbeitet, `await` für `prio` prüfen.
- Test hängt am Ende (JVM beendet sich nicht): Surefire beendet die JVM selbst; sollte `mvn` trotzdem nicht zurückkehren, `bridge.stop()` prüfen (`ws.stop(1000)`) und ob `match.end()` aufgerufen wurde.

- [ ] **Step 9: Ganze Suite**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test 2>&1 | tail -5`
Expected: `Tests run: 24, Failures: 0, Errors: 0` (7 alt + 5 Serializer + 3 Broker + 8 WebGuiGame + 1 E2E).

- [ ] **Step 10: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/pom.xml bridge/src
git commit -m "m2: humanmatch ueber hostedmatch, websocket-server, bridge, end-to-end-test ohne browser

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend – Lobby, Tisch, Prompt, Dialoge, Log

**Files:**
- Create: `web/package.json`, `web/vite.config.ts`, `web/tsconfig.json`, `web/index.html`
- Create: `web/src/main.tsx`, `web/src/App.tsx`, `web/src/styles.css`
- Create: `web/src/protocol.ts`, `web/src/ws.ts`, `web/src/store.ts`
- Create: `web/src/components/Lobby.tsx`, `Table.tsx`, `PlayerZone.tsx`, `CardBox.tsx`, `Hand.tsx`, `Prompt.tsx`, `ChoiceDialog.tsx`, `Log.tsx`
- Test: `web/src/store.test.ts`
- Modify: `.gitignore` (`web/dist/`)

**Interfaces:**
- Consumes: das JSON-Protokoll aus Task 1–3 (Feldnamen exakt wie in `Snapshot`/`Messages`).
- Produces: `npm run dev` (Vite auf 5173), `npm run build` (→ `web/dist`), `npm test` (Vitest).

- [ ] **Step 1: Projekt anlegen**

```bash
cd /home/kevin/projects/MTG-Player && mkdir -p web/src/components && cd web
```

`web/package.json`:

```json
{
  "name": "mtg-player-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "zustand": "^4.5.5"
  },
  "devDependencies": {
    "@types/react": "^18.3.5",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "typescript": "^5.5.4",
    "vite": "^5.4.2",
    "vitest": "^2.0.5"
  }
}
```

`web/vite.config.ts`:

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
  test: { environment: "node" },
});
```

`web/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "types": ["vite/client"]
  },
  "include": ["src"]
}
```

`web/index.html`:

```html
<!doctype html>
<html lang="de">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>MTG-Player</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

Dann: `npm install` (Bash-Timeout 300000 ms). In `.gitignore` im Repo-Root die Zeile `web/dist/` ergänzen.

- [ ] **Step 2: Protokoll-Typen**

`web/src/protocol.ts`:

```ts
export interface PromptSnap {
  message: string;
  card?: number;
  okLabel: string;
  cancelLabel: string;
  okEnabled: boolean;
  cancelEnabled: boolean;
}

export interface CardSnap {
  id: number;
  faceDown: boolean;
  name?: string;
  imageKey?: string;
  controller?: number;
  owner?: number;
  zone?: string;
  tapped?: boolean;
  sick?: boolean;
  power?: number;
  toughness?: number;
  damage?: number;
  counters?: Record<string, number>;
  attachedTo?: number;
  attachments?: number[];
  text?: string;
  typeLine?: string;
  manaCost?: string;
  attacking?: boolean;
  blocking?: boolean;
  token?: boolean;
  selectable?: boolean;
  actionable?: boolean;
  highlighted?: boolean;
}

export interface PlayerSnap {
  id: number;
  name: string;
  isAi: boolean;
  life: number;
  counters?: Record<string, number>;
  commanderDamage: Record<string, number>;
  hand: number[];
  librarySize: number;
  graveyard: number[];
  exile: number[];
  command: number[];
  battlefield: number[];
  manaPool: Record<string, number>;
  hasPriority: boolean;
}

export interface StackSnap {
  index: number;
  text: string;
  sourceCard?: number;
  controller?: number;
  targetCards: number[];
  targetPlayers: number[];
}

export interface Snapshot {
  type: "state";
  turn: number;
  phase?: string;
  activePlayer?: number;
  priorityPlayer?: number;
  me?: number;
  gameOver: boolean;
  players: PlayerSnap[];
  stack: StackSnap[];
  cards: Record<string, CardSnap>;
  prompt: PromptSnap;
}

export interface Option {
  index: number;
  label: string;
  card?: number;
  player?: number;
}

export type ChoiceKind = "one" | "many" | "order" | "confirm" | "number" | "text" | "ability" | "entities";

export interface Choice {
  type: "choice";
  id: number;
  kind: ChoiceKind;
  title: string;
  message: string;
  options: Option[];
  min: number;
  max: number;
  card?: number;
}

export interface Lobby { type: "lobby"; precons: string[]; }
export interface LogLine { type: "log"; text: string; }
export interface GameOver { type: "gameOver"; winner?: string; }
export interface ErrorMsg { type: "error"; text: string; }

export type Inbound = Snapshot | Choice | Lobby | LogLine | GameOver | ErrorMsg;

export type Outbound =
  | { type: "startGame"; humanDeck: { precon: string }; opponents: { precon: string; name: string }[] }
  | { type: "selectCard"; id: number; alt?: boolean }
  | { type: "selectPlayer"; id: number }
  | { type: "ok" }
  | { type: "cancel" }
  | { type: "answer"; id: number; value: unknown }
  | { type: "concede" }
  | { type: "requestState" };
```

- [ ] **Step 3: Failing Store-Test**

`web/src/store.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { reduce, initialState } from "./store";
import type { Choice, Snapshot } from "./protocol";

const snap = (over: Partial<Snapshot> = {}): Snapshot => ({
  type: "state",
  turn: 1,
  phase: "MAIN1",
  me: 1,
  gameOver: false,
  players: [],
  stack: [],
  cards: {},
  prompt: { message: "", okLabel: "OK", cancelLabel: "Cancel", okEnabled: false, cancelEnabled: false },
  ...over,
});

describe("reduce", () => {
  it("lobby setzt precons und screen", () => {
    const s = reduce(initialState, { type: "lobby", precons: ["A", "B"] });
    expect(s.precons).toEqual(["A", "B"]);
    expect(s.screen).toBe("lobby");
  });

  it("state wechselt auf den tisch und ersetzt den snapshot", () => {
    const s = reduce(initialState, snap({ turn: 3 }));
    expect(s.screen).toBe("table");
    expect(s.state?.turn).toBe(3);
  });

  it("choice ueberlebt nachfolgende state-updates", () => {
    const c: Choice = { type: "choice", id: 7, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const s1 = reduce(initialState, c);
    expect(s1.choice?.id).toBe(7);
    const s2 = reduce(s1, snap({ turn: 2 }));
    expect(s2.choice?.id).toBe(7);
    expect(s2.state?.turn).toBe(2);
  });

  it("log haengt an und ist auf 500 zeilen begrenzt", () => {
    let s = initialState;
    for (let i = 0; i < 600; i++) s = reduce(s, { type: "log", text: "z" + i });
    expect(s.log.length).toBe(500);
    expect(s.log[499]).toBe("z599");
  });

  it("gameOver setzt winner, state bleibt", () => {
    const s = reduce(reduce(initialState, snap()), { type: "gameOver", winner: "KI 1" });
    expect(s.winner).toBe("KI 1");
    expect(s.state).toBeDefined();
  });

  it("error landet im log", () => {
    const s = reduce(initialState, { type: "error", text: "kaputt" });
    expect(s.log[0]).toContain("kaputt");
  });
});
```

- [ ] **Step 4: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/web && npm test 2>&1 | tail -5`
Expected: Fehler, `./store` nicht gefunden.

- [ ] **Step 5: ws.ts und store.ts**

`web/src/ws.ts`:

```ts
import type { Inbound, Outbound } from "./protocol";

const url = `ws://${location.hostname}:8081`;
let socket: WebSocket | undefined;
let listener: ((m: Inbound) => void) | undefined;

export function connect(onMessage: (m: Inbound) => void): void {
  listener = onMessage;
  open();
}

function open(): void {
  socket = new WebSocket(url);
  socket.onmessage = (ev) => listener?.(JSON.parse(ev.data) as Inbound);
  socket.onclose = () => setTimeout(open, 1000);
}

export function send(msg: Outbound): void {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}
```

`web/src/store.ts`:

```ts
import { create } from "zustand";
import type { Choice, Inbound, Snapshot } from "./protocol";

export interface AppState {
  screen: "lobby" | "table";
  precons: string[];
  state?: Snapshot;
  choice?: Choice;
  log: string[];
  winner?: string | null;
}

export const initialState: AppState = { screen: "lobby", precons: [], log: [] };

const LOG_MAX = 500;

/** Reine Übergangsfunktion – testbar ohne Socket oder React. */
export function reduce(s: AppState, m: Inbound): AppState {
  switch (m.type) {
    case "lobby":
      return { ...s, precons: m.precons, screen: s.state ? s.screen : "lobby" };
    case "state":
      return { ...s, state: m, screen: "table" };
    case "choice":
      return { ...s, choice: m };
    case "log":
      return { ...s, log: [...s.log, m.text].slice(-LOG_MAX) };
    case "gameOver":
      return { ...s, winner: m.winner ?? null };
    case "error":
      return { ...s, log: [...s.log, "⚠ " + m.text].slice(-LOG_MAX) };
    default:
      return s;
  }
}

interface Store extends AppState {
  apply: (m: Inbound) => void;
  clearChoice: () => void;
  backToLobby: () => void;
}

export const useStore = create<Store>((set) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: () => set({ choice: undefined }),
  backToLobby: () => set({ screen: "lobby", state: undefined, winner: undefined, choice: undefined }),
}));
```

- [ ] **Step 6: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/web && npm test 2>&1 | tail -5`
Expected: `6 passed`.

- [ ] **Step 7: Komponenten**

`web/src/main.tsx`:

```tsx
import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { connect } from "./ws";
import { useStore } from "./store";
import "./styles.css";

connect((m) => useStore.getState().apply(m));
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
```

`web/src/App.tsx`:

```tsx
import { useStore } from "./store";
import Lobby from "./components/Lobby";
import Table from "./components/Table";
import ChoiceDialog from "./components/ChoiceDialog";

export default function App() {
  const screen = useStore((s) => s.screen);
  const choice = useStore((s) => s.choice);
  return (
    <>
      {screen === "lobby" ? <Lobby /> : <Table />}
      {choice && <ChoiceDialog choice={choice} />}
    </>
  );
}
```

`web/src/components/Lobby.tsx`:

```tsx
import { useState } from "react";
import { useStore } from "../store";
import { send } from "../ws";

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const [human, setHuman] = useState("");
  const [ais, setAis] = useState<string[]>([""]);

  const ready = human !== "" && ais.every((a) => a !== "");

  const start = () => {
    send({
      type: "startGame",
      humanDeck: { precon: human },
      opponents: ais.map((precon, i) => ({ precon, name: `KI ${i + 1}` })),
    });
  };

  const select = (value: string, onChange: (v: string) => void) => (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">– Precon wählen –</option>
      {precons.map((p) => (
        <option key={p} value={p}>{p}</option>
      ))}
    </select>
  );

  return (
    <div className="lobby">
      <h1>MTG-Player</h1>
      {precons.length === 0 && <p>Verbinde mit der Bridge …</p>}
      <label>Dein Deck {select(human, setHuman)}</label>
      {ais.map((a, i) => (
        <label key={i}>
          KI {i + 1} {select(a, (v) => setAis(ais.map((x, j) => (j === i ? v : x))))}
          {ais.length > 1 && <button onClick={() => setAis(ais.filter((_, j) => j !== i))}>–</button>}
        </label>
      ))}
      {ais.length < 5 && <button onClick={() => setAis([...ais, ""])}>+ KI</button>}
      <button className="primary" disabled={!ready} onClick={start}>Spiel starten</button>
    </div>
  );
}
```

`web/src/components/CardBox.tsx`:

```tsx
import type { MouseEvent } from "react";
import type { CardSnap } from "../protocol";
import { send } from "../ws";

export default function CardBox({ card }: { card: CardSnap }) {
  if (card.faceDown) return <div className="card back" />;
  const cls = ["card", card.tapped ? "tapped" : "", card.selectable ? "selectable" : "",
    card.actionable ? "actionable" : "", card.attacking ? "attacking" : "", card.blocking ? "blocking" : ""]
    .filter(Boolean).join(" ");
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2 });
  };
  return (
    <div className={cls} title={card.text ?? ""} onClick={click} onContextMenu={click}>
      <div className="name">{card.name}</div>
      <div className="meta">{card.manaCost ?? ""}</div>
      <div className="type">{card.typeLine ?? ""}</div>
      {card.power !== undefined && (
        <div className="pt">{card.power}/{card.toughness}{card.damage ? ` (${card.damage} dmg)` : ""}</div>
      )}
      {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k}×${v}`).join(" ")}</div>}
    </div>
  );
}
```

`web/src/components/PlayerZone.tsx`:

```tsx
import type { PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import { send } from "../ws";

export default function PlayerZone({ p, state, compact }: { p: PlayerSnap; state: Snapshot; compact: boolean }) {
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  const bf = cards(p.battlefield);
  const active = state.activePlayer === p.id;
  const mana = Object.entries(p.manaPool).filter(([, v]) => v > 0).map(([k, v]) => `${k}${v}`).join(" ");
  const cmdDmg = Object.entries(p.commanderDamage).map(([id, v]) => `${state.cards[id]?.name ?? id}: ${v}`).join(", ");
  return (
    <div className={"player" + (active ? " active" : "") + (compact ? " compact" : "")}>
      <div className="header" onClick={() => send({ type: "selectPlayer", id: p.id })}>
        <b>{p.name}</b> {p.isAi ? "(KI)" : ""} · Leben {p.life}
        {p.counters?.POISON ? ` · Gift ${p.counters.POISON}` : ""}
        {" · Hand "}{p.hand.length}{" · Bib "}{p.librarySize}{" · Grab "}{p.graveyard.length}{" · Exil "}{p.exile.length}
        {mana ? ` · Mana ${mana}` : ""}
        {cmdDmg ? ` · CMD-Schaden ${cmdDmg}` : ""}
        {p.hasPriority ? " · ⏵ Prio" : ""}
      </div>
      <div className="zone command">{cards(p.command).map((c) => <CardBox key={c.id} card={c} />)}</div>
      <div className="zone battlefield">
        {bf.filter((c) => !c.typeLine?.includes("Land")).map((c) => <CardBox key={c.id} card={c} />)}
      </div>
      <div className="zone lands">
        {bf.filter((c) => c.typeLine?.includes("Land")).map((c) => <CardBox key={c.id} card={c} />)}
      </div>
      {!compact && (
        <details><summary>Friedhof ({p.graveyard.length}) / Exil ({p.exile.length})</summary>
          <div className="zone">{cards(p.graveyard).map((c) => <CardBox key={c.id} card={c} />)}</div>
          <div className="zone">{cards(p.exile).map((c) => <CardBox key={c.id} card={c} />)}</div>
        </details>
      )}
    </div>
  );
}
```

`web/src/components/Hand.tsx`:

```tsx
import type { Snapshot } from "../protocol";
import CardBox from "./CardBox";

export default function Hand({ state }: { state: Snapshot }) {
  const me = state.players.find((p) => p.id === state.me);
  if (!me) return null;
  return (
    <div className="hand">
      {me.hand.map((id) => state.cards[String(id)]).filter(Boolean).map((c) => <CardBox key={c.id} card={c} />)}
    </div>
  );
}
```

`web/src/components/Prompt.tsx`:

```tsx
import { useEffect } from "react";
import type { Snapshot } from "../protocol";
import { send } from "../ws";

export default function Prompt({ state }: { state: Snapshot }) {
  const p = state.prompt;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement) return;
      if ((e.key === "Enter" || e.key === " ") && p.okEnabled) { e.preventDefault(); send({ type: "ok" }); }
      if (e.key === "Escape" && p.cancelEnabled) { e.preventDefault(); send({ type: "cancel" }); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [p.okEnabled, p.cancelEnabled]);
  return (
    <div className="prompt">
      <span className="phase">Zug {state.turn} · {state.phase ?? ""}</span>
      <span className="message">{p.message}</span>
      <button disabled={!p.okEnabled} onClick={() => send({ type: "ok" })}>{p.okLabel}</button>
      <button disabled={!p.cancelEnabled} onClick={() => send({ type: "cancel" })}>{p.cancelLabel}</button>
      <button className="danger" onClick={() => send({ type: "concede" })}>Aufgeben</button>
    </div>
  );
}
```

`web/src/components/ChoiceDialog.tsx`:

```tsx
import { useState } from "react";
import type { Choice } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

export default function ChoiceDialog({ choice }: { choice: Choice }) {
  const clear = useStore((s) => s.clearChoice);
  const cards = useStore((s) => s.state?.cards ?? {});
  const [picked, setPicked] = useState<number[]>([]);
  const [text, setText] = useState("");

  const answer = (value: unknown) => {
    send({ type: "answer", id: choice.id, value });
    clear();
  };

  const label = (o: { label: string; card?: number }) =>
    o.card !== undefined && cards[String(o.card)]?.name && !o.label.includes(cards[String(o.card)].name!)
      ? `${o.label} [${cards[String(o.card)].name}]` : o.label;

  const body = () => {
    switch (choice.kind) {
      case "confirm":
        return (
          <div className="buttons">
            <button className="primary" onClick={() => answer(true)}>{choice.options[0]?.label ?? "Ja"}</button>
            <button onClick={() => answer(false)}>{choice.options[1]?.label ?? "Nein"}</button>
          </div>
        );
      case "number":
      case "text":
        return (
          <form onSubmit={(e) => { e.preventDefault(); answer(choice.kind === "number" ? Number(text || 0) : text); }}>
            <input autoFocus type={choice.kind === "number" ? "number" : "text"} value={text} onChange={(e) => setText(e.target.value)} />
            <button type="submit" className="primary">OK</button>
          </form>
        );
      case "one":
      case "ability":
      case "entities":
      case "many":
      case "order": {
        const single = choice.kind === "one" || choice.kind === "ability" || (choice.max === 1 && choice.kind !== "order");
        const toggle = (i: number) => {
          if (single) { answer(i); return; }
          setPicked(picked.includes(i) ? picked.filter((x) => x !== i) : [...picked, i]);
        };
        const ok = choice.kind === "order" ? picked.length === choice.options.length
          : picked.length >= choice.min && (choice.max <= 0 || picked.length <= choice.max);
        return (
          <>
            <ul className="options">
              {choice.options.map((o) => (
                <li key={o.index} className={picked.includes(o.index) ? "picked" : ""} onClick={() => toggle(o.index)}>
                  {choice.kind === "order" && picked.includes(o.index) ? `${picked.indexOf(o.index) + 1}. ` : ""}{label(o)}
                </li>
              ))}
            </ul>
            {!single && (
              <div className="buttons">
                <button className="primary" disabled={!ok} onClick={() => answer(picked)}>OK</button>
                {choice.min === 0 && <button onClick={() => answer([])}>Keine</button>}
              </div>
            )}
            {single && choice.min === 0 && <div className="buttons"><button onClick={() => answer(null)}>Keine</button></div>}
          </>
        );
      }
    }
  };

  return (
    <div className="overlay">
      <div className="dialog">
        <h3>{choice.title}</h3>
        {choice.message !== choice.title && <p>{choice.message}</p>}
        {choice.kind === "order" && <p className="hint">In gewünschter Reihenfolge anklicken (oben zuerst).</p>}
        {choice.kind === "many" && <p className="hint">{choice.min}–{choice.max <= 0 ? "beliebig" : choice.max} auswählen</p>}
        {body()}
      </div>
    </div>
  );
}
```

`web/src/components/Log.tsx`:

```tsx
import { useEffect, useRef } from "react";
import { useStore } from "../store";

export default function Log() {
  const log = useStore((s) => s.log);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollTo(0, ref.current.scrollHeight); }, [log.length]);
  return (
    <div className="log" ref={ref}>
      {log.map((l, i) => <div key={i}>{l}</div>)}
    </div>
  );
}
```

`web/src/components/Table.tsx`:

```tsx
import { useStore } from "../store";
import PlayerZone from "./PlayerZone";
import Hand from "./Hand";
import Prompt from "./Prompt";
import Log from "./Log";

export default function Table() {
  const state = useStore((s) => s.state);
  const winner = useStore((s) => s.winner);
  const backToLobby = useStore((s) => s.backToLobby);
  if (!state) return <div className="lobby"><p>Warte auf Spielzustand …</p></div>;
  const me = state.players.find((p) => p.id === state.me);
  const foes = state.players.filter((p) => p.id !== state.me);
  return (
    <div className="table">
      <div className="opponents">
        {foes.map((p) => <PlayerZone key={p.id} p={p} state={state} compact={foes.length > 1} />)}
      </div>
      <div className="side">
        <div className="stack">
          <b>Stack</b>
          {state.stack.length === 0 && <div className="empty">leer</div>}
          {[...state.stack].reverse().map((s) => (
            <div key={s.index} className="stack-item">{s.text}</div>
          ))}
        </div>
        <Log />
      </div>
      <div className="mine">
        {me && <PlayerZone p={me} state={state} compact={false} />}
        <Prompt state={state} />
        <Hand state={state} />
      </div>
      {winner !== undefined && (
        <div className="overlay">
          <div className="dialog">
            <h3>{winner ? `${winner} gewinnt` : "Unentschieden"}</h3>
            <button className="primary" onClick={backToLobby}>Zur Lobby</button>
          </div>
        </div>
      )}
    </div>
  );
}
```

`web/src/styles.css`:

```css
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; background: #1b1f24; color: #e6e6e6; }
button { cursor: pointer; padding: 6px 12px; border-radius: 6px; border: 1px solid #555; background: #2c3138; color: inherit; }
button:disabled { opacity: 0.4; cursor: default; }
button.primary { background: #2f6fd6; border-color: #2f6fd6; }
button.danger { background: #7a2a2a; border-color: #7a2a2a; }
select, input { padding: 6px; border-radius: 6px; border: 1px solid #555; background: #2c3138; color: inherit; }

.lobby { max-width: 640px; margin: 40px auto; display: flex; flex-direction: column; gap: 12px; }
.lobby label { display: flex; gap: 8px; align-items: center; }

.table { display: grid; grid-template-columns: 1fr 320px; grid-template-rows: 1fr auto; height: 100vh; gap: 8px; padding: 8px; }
.opponents { grid-column: 1; grid-row: 1; display: flex; gap: 8px; overflow: auto; }
.side { grid-column: 2; grid-row: 1 / span 2; display: flex; flex-direction: column; gap: 8px; min-height: 0; }
.mine { grid-column: 1; grid-row: 2; display: flex; flex-direction: column; gap: 6px; }

.player { flex: 1; border: 1px solid #333; border-radius: 8px; padding: 6px; min-width: 0; overflow: auto; }
.player.active { border-color: #d6a72f; }
.player .header { font-size: 13px; cursor: pointer; padding: 2px 4px; }
.player .header:hover { background: #2c3138; border-radius: 4px; }
.zone { display: flex; flex-wrap: wrap; gap: 4px; min-height: 28px; padding: 2px 0; }
.zone.lands .card { opacity: 0.85; }

.card { width: 96px; min-height: 56px; border: 2px solid #444; border-radius: 6px; padding: 3px 4px; background: #262b31; font-size: 11px; cursor: pointer; user-select: none; }
.card .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card .type, .card .meta { color: #9aa; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card .pt { text-align: right; font-weight: 600; }
.card.back { background: #3a2f5c; border-color: #3a2f5c; }
.card.tapped { transform: rotate(12deg); opacity: 0.8; }
.card.selectable { border-color: #ffd23f; box-shadow: 0 0 6px #ffd23f; }
.card.actionable { border-color: #7dd36f; }
.card.attacking { border-color: #ff5c5c; }
.card.blocking { border-color: #5cb8ff; }
.player.compact .card { width: 72px; min-height: 40px; font-size: 10px; }

.hand { display: flex; gap: 6px; overflow-x: auto; padding: 6px; background: #14171b; border-radius: 8px; min-height: 70px; }
.prompt { display: flex; gap: 8px; align-items: center; padding: 6px; background: #14171b; border-radius: 8px; }
.prompt .message { flex: 1; }
.prompt .phase { color: #9aa; white-space: nowrap; }

.stack { border: 1px solid #333; border-radius: 8px; padding: 6px; max-height: 40%; overflow: auto; }
.stack-item { border-left: 3px solid #d6a72f; padding: 4px 6px; margin: 4px 0; font-size: 12px; background: #262b31; }
.stack .empty { color: #666; }
.log { flex: 1; overflow: auto; font-size: 12px; border: 1px solid #333; border-radius: 8px; padding: 6px; min-height: 0; }

.overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; }
.dialog { background: #262b31; border-radius: 10px; padding: 16px; min-width: 360px; max-width: 80vw; max-height: 80vh; overflow: auto; }
.dialog .hint { color: #9aa; font-size: 12px; }
.options { list-style: none; padding: 0; margin: 8px 0; display: flex; flex-direction: column; gap: 4px; }
.options li { padding: 6px 8px; border: 1px solid #444; border-radius: 6px; cursor: pointer; }
.options li:hover { background: #2c3138; }
.options li.picked { border-color: #2f6fd6; background: #1f3355; }
.buttons { display: flex; gap: 8px; margin-top: 8px; }
```

- [ ] **Step 8: Typecheck und Build**

Run: `cd /home/kevin/projects/MTG-Player/web && npm run build 2>&1 | tail -5`
Expected: `✓ built in …`, keine TypeScript-Fehler. 

- [ ] **Step 9: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add .gitignore web/package.json web/package-lock.json web/vite.config.ts web/tsconfig.json web/index.html web/src
git commit -m "m2: frontend – lobby, tisch, prompt, dialoge, log (react + vite + zustand)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Durchspielen, README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: alles aus Task 1–4.

- [ ] **Step 1: Bridge starten (Hintergrund) und Frontend im Dev-Modus**

Terminal 1 (Bash `run_in_background`, Ausgabe in eine Datei):

```bash
cd /home/kevin/projects/MTG-Player/bridge && mvn -q compile exec:java > /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/bridge.log 2>&1
```

Warten, bis `Bereit.` in der Logdatei steht (`grep -m1 Bereit …/bridge.log`, ca. 10 s nach Kartenladen).

Terminal 2:

```bash
cd /home/kevin/projects/MTG-Player/web && npm run dev > /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/vite.log 2>&1
```

- [ ] **Step 2: Rauchtest über das Protokoll statt Browser** (der Implementierer hat keinen Browser)

Der E2E-Test aus Task 3 ist der Nachweis. Zusätzlich prüfen, dass die gebaute Seite ausgeliefert wird:

Run: `cd /home/kevin/projects/MTG-Player/web && npm run build >/dev/null && curl -s http://127.0.0.1:8080/ | head -3`
Expected: `<!doctype html>` … `<title>MTG-Player</title>`.

Run: `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/assets/`
Expected: `200` (SPA-Fallback auf index.html).

Danach beide Hintergrundprozesse beenden (`pkill -f 'exec:java'`, `pkill -f vite`).

- [ ] **Step 3: README ergänzen**

In `README.md` den Abschnitt "Bridge" ersetzen durch:

```markdown
## Spielen

Einmalig das Frontend bauen, dann die Bridge starten:

```bash
cd web && npm install && npm run build && cd ..
cd bridge && mvn -q compile exec:java
```

Browser: <http://127.0.0.1:8080>. Lobby → eigenes Precon und 1–5 KI-Precons wählen → Spiel starten.
Steuerung: leuchtende Karten sind klickbar, Rechtsklick = andere Fähigkeit, Enter/Leertaste = OK,
Esc = Abbrechen. Forge passt automatisch, wenn du nichts tun kannst (Arena-Stil).

Entwicklung am Frontend: `cd web && npm run dev` (Vite auf :5173, verbindet sich mit der Bridge auf :8081).

## Bridge

```bash
cd bridge
mvn -q test                                # alle Tests inkl. KI-Spiel und End-to-End über WebSocket (Minuten)
mvn -q compile exec:java                   # Bridge-Server (WebSocket 8081, HTTP 8080)
mvn -q compile exec:java -Dexec.args="--ai-demo 42"   # headless KI-Spiel wie in M1
```

Ports: `-Dmtgplayer.wsPort=…`, `-Dmtgplayer.httpPort=…`; Frontend-Verzeichnis: `-Dmtgplayer.web=…` (Standard `../web/dist`).
`ForgeBoot.init()` schreibt bei jedem Start `bridge/assets/forge.profile.properties` (generiert, git-ignoriert) und lenkt
Forges Nutzerdaten damit nach `~/.mtg-player/`; `bridge/assets/res` ist ein Symlink auf `forge/forge-gui/res`.
Der Assets-Pfad ist mit `-Dmtgplayer.assets=<dir>` überschreibbar; Maven setzt ihn für `test` und `exec:java` automatisch.

## Was noch fehlt (M3+)

Phasen-Stops und "Volle Kontrolle", Dialog für Kampfschaden-Verteilung, Textlisten-Import, Kartenbilder, Archidekt.
```

(Die bestehenden Hinweise zu `forge.profile.properties`, Symlink und `-Dmtgplayer.assets` aus dem alten Abschnitt sind damit übernommen; nichts doppelt lassen.)

- [ ] **Step 4: Ganze Suite ein letztes Mal**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test 2>&1 | tail -3 && cd ../web && npm test 2>&1 | tail -3`
Expected: `Tests run: 24, Failures: 0` und `6 passed`.

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add README.md
git commit -m "m2: readme – spielen im browser, ports, ai-demo

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Nach diesem Plan

M2 ist erfüllt, wenn der E2E-Test grün ist und Kevin im Browser ein Spiel gegen eine KI bis zu einem Angriff durchspielen kann. Was aus dem Final-Review von M1 bewusst offen bleibt: `AiMatch` bleibt der headless Harness (Forge selbst baut für Simulationen `Match` direkt, siehe Kommentar in `HostedMatch.endCurrentGame`); Log-Streaming ins Frontend über `GameLog` kommt mit M5, in M2 kommen nur `message`/`error`-Zeilen an.

M3 baut darauf auf: `isUiSetToSkipPhase` aus einer Stop-Konfiguration (`setStops`/`fullControl`-Nachrichten), Dialog für `assignCombatDamage`/`assignGenericAmount`, `manipulateCardList`, Mulligan-Feinschliff.
