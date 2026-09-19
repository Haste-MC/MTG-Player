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
import forge.card.GamePieceType;
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
    private static Card myCommander;
    private static Card myEffect;

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
        // Commander wie in Player.initVariantsZones: Card.setCommander(true) ueber Player.addCommander,
        // das ruft CardView.updateCommander -> CardView.isCommander() == true.
        myCommander = Card.fromPaperCard(a.get(DeckSection.Commander).toFlatList().get(0), me);
        me.addCommander(myCommander);
        me.getZone(ZoneType.Battlefield).add(myCommander);

        // Forges Effekt-Hilfskarten entstehen normalerweise ueber SpellAbilityEffect.createEffect(...)
        // (protected/statisch, braucht eine SpellAbility) - fuer den Test reicht der direkte Weg, den
        // createEffect selbst intern geht: new Card(id, game) + setGamePieceType(EFFECT) + setName(...),
        // dann in die Kommandozone legen (wo Forge Effekt-Karten tatsaechlich ablegt). setOwner ist
        // Pflicht: Zone.add loest darueber Player.getCardsActivatableInExternalZones/mayPlayerLook aus,
        // das ohne Owner mit einer NullPointerException abbricht (owner-lose Karte gibt es in Forge nicht).
        myEffect = new Card(game.nextCardId(), game);
        myEffect.setGamePieceType(GamePieceType.EFFECT);
        myEffect.setName("Giant Growth");
        myEffect.setOwner(me);
        me.getZone(ZoneType.Command).add(myEffect);
    }

    @Test
    void commanderFlagNurBeimCommander() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap cmd = s.cards().get(myCommander.getId());
        assertNotNull(cmd);
        assertEquals(Boolean.TRUE, cmd.commander());
        assertEquals("Battlefield", cmd.zone());
        assertNull(s.cards().get(myPermanent.getId()).commander(), "normale Karte traegt kein commander-Feld");
        JsonNode json = Json.parse(Json.toJson(s));
        assertTrue(json.get("cards").get(String.valueOf(myCommander.getId())).get("commander").asBoolean());
        assertFalse(json.get("cards").get(String.valueOf(myPermanent.getId())).has("commander"));
    }

    @Test
    void effektKarteTraegtEffectFlagNormaleKarteNicht() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap eff = s.cards().get(myEffect.getId());
        assertNotNull(eff);
        assertEquals(Boolean.TRUE, eff.effect());
        assertNull(eff.emblem(), "Effekt-Hilfskarte ist kein Emblem");
        assertEquals("Command", eff.zone());
        assertTrue(s.players().get(0).command().contains(myEffect.getId()));
        assertNull(s.cards().get(myPermanent.getId()).effect(), "normale Karte traegt kein effect-Feld");

        JsonNode json = Json.parse(Json.toJson(s));
        assertTrue(json.get("cards").get(String.valueOf(myEffect.getId())).get("effect").asBoolean());
        assertFalse(json.get("cards").get(String.valueOf(myPermanent.getId())).has("effect"));
        assertFalse(json.get("cards").get(String.valueOf(myEffect.getId())).has("emblem"));
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
        Snapshot.PromptSnap p = new Snapshot.PromptSnap("Wähle", null, "Keep", "Mulligan", true, true, 1);
        ViewContext ctx = new ViewContext(me.getView(), c -> c.canBeShownTo(me.getView()),
                c -> c.getId() == myHandCard.getId(), c -> false, e -> false, pv -> false, p,
                new Messages.StopsMsg(List.of(), List.of()), false, false);
        Snapshot s = StateSerializer.snapshot(game.getView(), ctx);
        assertEquals("Keep", s.prompt().okLabel());
        assertEquals(Boolean.TRUE, s.cards().get(myHandCard.getId()).selectable());
        assertNull(s.cards().get(myPermanent.getId()).selectable());
    }

    @Test
    void spectatorFlagNurBeiZuschauerGesetzt() {
        Snapshot human = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        assertNull(human.spectator(), "menschlicher Sitz traegt kein spectator-Feld");
        JsonNode humanJson = Json.parse(Json.toJson(human));
        assertFalse(humanJson.has("spectator"), "null-Feld wird weggelassen");

        ViewContext spectatorCtx = new ViewContext(null, c -> true, c -> false, c -> false,
                e -> false, p -> false, Snapshot.PromptSnap.EMPTY, new Messages.StopsMsg(List.of(), List.of()), false, true);
        Snapshot spectator = StateSerializer.snapshot(game.getView(), spectatorCtx);
        assertEquals(Boolean.TRUE, spectator.spectator());
        JsonNode spectatorJson = Json.parse(Json.toJson(spectator));
        assertTrue(spectatorJson.get("spectator").asBoolean());
    }

    @Test
    void targetableWirdDurchgereicht() {
        ViewContext ctx = new ViewContext(me.getView(), c -> c.canBeShownTo(me.getView()), c -> false, c -> false,
                e -> false, p -> p.getId() == foe.getView().getId(), Snapshot.PromptSnap.EMPTY,
                new Messages.StopsMsg(List.of(), List.of()), false, false);
        Snapshot s = StateSerializer.snapshot(game.getView(), ctx);
        assertEquals(Boolean.TRUE, s.players().get(1).targetable());
        assertNull(s.players().get(0).targetable());
    }
}
