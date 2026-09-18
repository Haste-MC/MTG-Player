package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.control.FControlGameEventHandler;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.CommanderRules;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Commander stirbt → Forge fragt per InputConfirm ("Yes"/"No"), ob er in die Kommandozone geht
 * (CR 903.9a, {@code GameAction#stateBasedAction_Commander}). Das Prompt muss den Browser erreichen
 * und die Antwort muss wirken. Die SBA läuft nur, wenn die Commander-Variante angewandt ist – deshalb
 * die Regeln aus {@link CommanderRules}, wie im echten Spiel.
 */
class CommanderDeathTest {

    private static final String PROMPT = "may put it into the command zone";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    record Harness(Game game, Player me, WebGuiGame gui, BlockingQueue<String> sent, Card commander) {}

    /** Spiel ohne HostedMatch, verdrahtet wie HostedMatch.startGame; der Commander liegt auf dem Schlachtfeld. */
    private static Harness aufbauen() {
        assertTrue(CommanderRules.create().hasAppliedVariant(GameType.Commander));
        Deck a = Precons.load("Abzan Armor [TDC] [2025]");
        Deck b = Precons.load("Adaptive Enchantment [C18] [2018]");
        RegisteredPlayer ra = RegisteredPlayer.forCommander(a);
        ra.setPlayer(new LobbyPlayerHuman("Du"));
        RegisteredPlayer rb = RegisteredPlayer.forCommander(b);
        rb.setPlayer(new LobbyPlayerAi("Gegner", null));
        Game game = new Match(CommanderRules.create(), List.of(ra, rb), "t").createGame();
        Player me = game.getPlayers().get(0);
        PlayerControllerHuman controller = (PlayerControllerHuman) me.getController();

        BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        WebGuiGame gui = new WebGuiGame(m -> sent.add(Json.toJson(m)));
        controller.setGui(gui);
        gui.setGameView(game.getView());
        gui.setOriginalGameController(me.getView(), controller);
        game.subscribeToEvents(new FControlGameEventHandler(controller));

        // Commander markieren wie Player#initVariantsZones, dann aufs Schlachtfeld
        Card cmd = Card.fromPaperCard(a.getCommanders().get(0), me);
        me.initCommanderColor(cmd);
        cmd.setCollectible(true);
        me.addCommander(cmd);
        me.getZone(ZoneType.Battlefield).add(cmd);
        cmd.setZone(me.getZone(ZoneType.Battlefield));
        return new Harness(game, me, gui, sent, cmd);
    }

    /** Wartet auf einen state-Push mit aktivem Prompt und der gegebenen OK-Beschriftung. */
    private static JsonNode promptMit(BlockingQueue<String> sent, String okLabel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String s = sent.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (s == null) break;
            JsonNode n = Json.parse(s);
            JsonNode p = n.get("prompt");
            if ("state".equals(n.get("type").asText()) && p != null
                    && okLabel.equals(p.path("okLabel").asText()) && p.path("okEnabled").asBoolean()) {
                return p;
            }
        }
        throw new AssertionError("kein Prompt mit okLabel=" + okLabel + " angekommen");
    }

    /** Game-Thread: Commander in den Friedhof, dann SBAs – wie beim Sterben im Kampf. */
    private static CompletableFuture<Void> commanderSterbenLassen(Harness h) {
        return CompletableFuture.runAsync(() -> {
            h.game().getAction().moveToGraveyard(h.commander(), null, AbilityKey.newMap());
            h.game().getAction().checkStateEffects(true);
        });
    }

    private static int anzahl(Player p, ZoneType zone, String name) {
        return (int) p.getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).count();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void jaBringtDenCommanderInDieKommandozone() throws Exception {
        Harness h = aufbauen();
        CompletableFuture<Void> sba = commanderSterbenLassen(h);
        JsonNode p = promptMit(h.sent(), "Yes");
        assertTrue(p.get("message").asText().contains(PROMPT), p.toString());
        assertEquals("No", p.get("cancelLabel").asText());
        int seq = p.get("seq").asInt();
        GuiBase.getInterface().invokeInEdtLater(() -> h.gui().onOk(seq));
        sba.get(20, TimeUnit.SECONDS);
        String name = h.commander().getName();
        assertEquals(1, anzahl(h.me(), ZoneType.Command, name), "Commander in der Kommandozone");
        assertEquals(0, anzahl(h.me(), ZoneType.Graveyard, name), "Commander nicht mehr im Friedhof");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void neinLaesstDenCommanderImFriedhof() throws Exception {
        Harness h = aufbauen();
        CompletableFuture<Void> sba = commanderSterbenLassen(h);
        JsonNode p = promptMit(h.sent(), "Yes");
        int seq = p.get("seq").asInt();
        GuiBase.getInterface().invokeInEdtLater(() -> h.gui().onCancel(seq));
        sba.get(20, TimeUnit.SECONDS);
        String name = h.commander().getName();
        assertEquals(1, anzahl(h.me(), ZoneType.Graveyard, name), "Commander bleibt im Friedhof");
        assertEquals(0, anzahl(h.me(), ZoneType.Command, name));
    }
}
