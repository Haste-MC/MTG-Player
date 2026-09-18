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
        Snapshot.PromptSnap p = new Snapshot.PromptSnap("Wähle", null, "Keep", "Mulligan", true, true, 1);
        ViewContext ctx = new ViewContext(me.getView(), c -> c.canBeShownTo(me.getView()),
                c -> c.getId() == myHandCard.getId(), c -> false, e -> false, p,
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
                e -> false, Snapshot.PromptSnap.EMPTY, new Messages.StopsMsg(List.of(), List.of()), false, true);
        Snapshot spectator = StateSerializer.snapshot(game.getView(), spectatorCtx);
        assertEquals(Boolean.TRUE, spectator.spectator());
        JsonNode spectatorJson = Json.parse(Json.toJson(spectator));
        assertTrue(spectatorJson.get("spectator").asBoolean());
    }
}
