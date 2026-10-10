package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.player.Player;
import forge.gui.control.WatchLocalGame;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.forge.Precons;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Das NATUERLICHE Ende einer Zuschauer-Partie: eine KI gewinnt, ohne dass jemand aufgibt.
 *
 * <p><b>Warum dieser Test fehlte.</b> {@code BridgeSpectatorTest} beendet jede Zuschauer-Partie per
 * "concede" - und auf diesem Weg schickt {@code Bridge} die {@code gameOver}-Nachricht SELBST (siehe
 * dort, Zweig "concede" ohne eigenen Sitz). Ob Forges eigener Weg ueber
 * {@code IGuiGame.finishGame()} beim Zuschauer ueberhaupt ankommt, hat deshalb nie etwas geprueft.
 * Kevins Protokoll vom 2026-10-07 20:01 zeigte genau diese Luecke: Spiel laut {@code GameView}
 * beendet, Spiel-Thread fertig, UI-Thread untaetig - und im Browser stand noch Zug 35.
 *
 * <p><b>Warum von innen beendet.</b> Das Ende wird ueber {@code game.setGameOver} auf Forges
 * Spiel-Thread ausgeloest, also auf demselben Weg, den eine gewinnende KI nimmt. Eine echte Partie
 * zu Ende zu spielen waere dasselbe Ereignis nach vielen Minuten mit unvorhersagbarer Dauer.
 */
class SpectatorEndTest {

    private final BlockingQueue<Object> gesendet = new LinkedBlockingQueue<>();
    private HumanMatch match;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @AfterEach
    void stopMatch() {
        if (match != null) {
            match.end();
        }
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(null);
    }

    /**
     * Forges Zuschauer-Pfad holt die Oberflaeche ueber {@code GuiBase.getNewGuiGame()} - im Betrieb
     * stellt die {@code Bridge} sie dort bereit, hier der Test (wie in {@code MatchRecorderWiringTest}).
     */
    private Game starteZuschauerPartie() throws InterruptedException {
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"));
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(() -> gui);
        match = new HumanMatch();
        match.startSpectator(decks, List.of("KI 1", "KI 2"),
                Collections.nCopies(2, AiConfig.DEFAULT), 3, gui);
        return warteAufSpiel();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void dasNatuerlicheEndeErreichtDenBrowser() throws Exception {
        Game game = starteZuschauerPartie();
        Thread.sleep(5000);                      // echte Ereignisse (Mulligans, erster Zug) laufen lassen
        gesendet.clear();                        // alles vom Spielstart beiseite

        // Forges eigener Weg, genau wie bei einer gewinnenden KI - NICHT match.end().
        game.getAction().invoke(() -> game.setGameOver(GameEndReason.AllOpponentsLost));

        Object over = warteAuf(Messages.GameOver.class, 60);
        assertNotNull(over, "keine gameOver-Nachricht beim Zuschauer angekommen");
    }

    /**
     * Die Verdrahtung des Teamsiegs bis zum Browser. {@code TeamGewinnerTest} prueft {@code ausgang}
     * und {@code teamWinner} nur einzeln - ersetzte man in {@code finishGame()} die Team-Zuordnung durch
     * {@code Map.of()}, blieben alle gruen, und der Dialog meldete beim Teamsieg wieder "Spiel beendet".
     * Hier laeuft der echte Weg: vier KI-Sitze ueber {@code HumanMatch.startSpectator} mit Teams
     * 1/1/2/2, Team 2 scheidet aus, und die {@code GameOver}-Nachricht der echten Oberflaeche muss das
     * Teamkennzeichen und genau die beiden Sitze des Gewinnerteams tragen. Nur die Stellung wird
     * hergerichtet (Leben 0, Zustandspruefung), gespielt wird nichts zu Ende.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void einTeamSiegErreichtDenBrowserMitTeamUndSitzen() throws Exception {
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"),
                Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"));
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(() -> gui);
        match = new HumanMatch();
        match.startSpectator(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"),
                Collections.nCopies(4, AiConfig.DEFAULT), List.of(1, 1, 2, 2), 3, gui, null,
                java.util.Set.of(), null);
        Game game = warteAufSpiel();
        List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
        // Lobby 1,1,2,2 - gesessen wird abwechselnd, also gehoeren die Sitze 1 und 3 zu Team 2.
        assertEquals(List.of(1, 2, 1, 2), sitze.stream().map(Player::getTeam).toList());
        gesendet.clear();

        // Auf Forges Spiel-Thread, wie bei einer gewinnenden KI: Team 2 geht auf 0 Leben, die
        // Zustandspruefung setzt "verloren" und beendet die Partie - Forges eigener Weg zu finishGame().
        game.getAction().invoke(() -> {
            sitze.get(1).setLife(0, null);
            sitze.get(3).setLife(0, null);
            game.getAction().checkStateEffects(true);
        });

        Messages.GameOver over = (Messages.GameOver) warteAuf(Messages.GameOver.class, 60);
        assertNotNull(over, "keine gameOver-Nachricht angekommen");
        assertEquals("Team 1", over.winner(), "das Teamkennzeichen, nicht der Name eines einzelnen Sitzes");
        assertEquals(List.of(sitze.get(0).getId(), sitze.get(2).getId()), over.winnerSeats(),
                "genau die beiden Sitze des Gewinnerteams, in Sitzreihenfolge");
    }

    /** Wartet, bis Forge das Spiel erzeugt hat und es laeuft. */
    private Game warteAufSpiel() throws InterruptedException {
        long ende = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        while (System.currentTimeMillis() < ende) {
            if (match.isRunning()) {
                return match.laufendesSpiel();
            }
            Thread.sleep(200);
        }
        return fail("Forge hat innerhalb von 2 Minuten kein laufendes Spiel geliefert");
    }

    private Object warteAuf(Class<?> art, int sekunden) throws InterruptedException {
        return warteAuf(gesendet, art, sekunden);
    }

    private Object warteAuf(BlockingQueue<Object> queue, Class<?> art, int sekunden) throws InterruptedException {
        long ende = System.currentTimeMillis() + sekunden * 1000L;
        while (System.currentTimeMillis() < ende) {
            Object m = queue.poll(Math.max(1, ende - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (m == null) {
                break;
            }
            if (art.isInstance(m)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Das Netz unter dem Spielende: eine Oberflaeche, bei der Forges Abschluss NIE gelaufen ist (eine
     * zweite, frisch auf dieselbe - schon beendete - Partie gesetzt), liefert ihn beim Wachposten
     * nach. Genau dieser Fall stand am 2026-10-07 um 20:01 in Kevins Protokoll.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void einVerlorenesSpielendeWirdNachgeliefert() throws Exception {
        Game game = starteZuschauerPartie();
        game.getAction().invoke(() -> game.setGameOver(GameEndReason.AllOpponentsLost));
        assertNotNull(warteAuf(Messages.GameOver.class, 60), "Forges eigener Weg");

        BlockingQueue<Object> zweite = new LinkedBlockingQueue<>();
        WebGuiGame gui2 = new WebGuiGame(zweite::add);
        try {
            gui2.setSpectator(new WatchLocalGame(game, null, gui2));
            gui2.setGameView(game.getView());
            zweite.clear();

            gui2.spielendeNachliefern();
            assertNotNull(warteAuf(zweite, Messages.GameOver.class, 20), "Abschluss nicht nachgeliefert");

            zweite.clear();
            gui2.spielendeNachliefern();
            assertNull(warteAuf(zweite, Messages.GameOver.class, 3), "genau einmal, nicht je Takt erneut");
        } finally {
            gui2.resetForNewMatch();
        }
    }

    /** Sanity: der Transport bekommt ueberhaupt etwas - sonst beweist der Test oben nichts. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void derZuschauerBekommtWaehrendDerPartieZustaende() throws Exception {
        starteZuschauerPartie();
        assertTrue(gesendet.size() > 0, "der Zuschauer bekommt Nachrichten");
    }
}
