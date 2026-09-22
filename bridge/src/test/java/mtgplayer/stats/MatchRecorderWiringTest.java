package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.CommanderRules;
import mtgplayer.match.HumanMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Die Verdrahtung zwischen {@link HumanMatch}, {@link WebGuiGame#onNewGame} und dem Recorder:
 * genau ein Datensatz je Partie, auch wenn {@code setGameView} mehrfach kommt.
 *
 * <p>Der erste Fall laeuft bewusst mit einem ECHTEN Zuschauer-Spiel ueber
 * {@link HumanMatch#startSpectator} (Forges {@code HostedMatch}-Pfad, eigener Spiel-Thread) und
 * beendet es ueber {@link HumanMatch#end()}: eine KI-Commander-Partie zu Ende zu spielen dauert
 * Minuten bis Stunden und waere im Test weder schnell noch verlaesslich, der Abbruch dagegen
 * durchlaeuft dieselbe Kette vollstaendig - Haken beim Spielstart, Recorder am echten Ereignisbus,
 * Abschluss ueber {@code GameEventGameFinished}, ein Datensatz im Sink. Die Mehrfach-Aufrufe von
 * {@code setGameView} (Teilspiele) sind im Zuschauer-Spiel nicht erzwingbar und stehen deshalb im
 * zweiten Fall direkt am Haken.</p>
 */
class MatchRecorderWiringTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    // ---------------------------------------------------------------- echtes Zuschauer-Spiel

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void zuschauerSpielLiefertGenauEinenDatensatzUndBeimBeendenNichtGewertet() throws Exception {
        List<String> precons = List.of("Abzan Armor [TDC] [2025]", "Adaptive Enchantment [C18] [2018]");
        List<Deck> decks = precons.stream().map(Precons::load).toList();
        List<MatchRecord> sunk = new ArrayList<>();
        WebGuiGame gui = new WebGuiGame(m -> { });
        // Forges Zuschauer-Pfad holt sich die GUI ueber GuiBase.getNewGuiGame() - im Betrieb macht
        // das die Bridge, hier der Test.
        WebGuiBase base = (WebGuiBase) GuiBase.getInterface();
        base.setGuiSupplier(() -> gui);

        HumanMatch match = new HumanMatch();
        try {
            match.startSpectator(decks, List.of("KI 1", "KI 2"),
                    List.of(AiConfig.DEFAULT, AiConfig.DEFAULT), 3, gui, r -> {
                        synchronized (sunk) {
                            sunk.add(r);
                        }
                    });
            waitUntil(match::isRunning, 60_000, "Zuschauer-Spiel laeuft");
            letItRun(match, 10_000);              // echte Ereignisse (Mulligans, erster Zug) sammeln

            match.end();
            waitUntil(() -> {
                synchronized (sunk) {
                    return !sunk.isEmpty();
                }
            }, 30_000, "Datensatz im Sink");
        } finally {
            match.end();
            base.setGuiSupplier(null);
        }

        synchronized (sunk) {
            assertEquals(1, sunk.size(), "genau ein Datensatz je Partie, war " + sunk);
            MatchRecord r = sunk.get(0);
            assertEquals("spectate", r.source());
            assertEquals(2, r.seats().size());
            assertEquals(precons, r.seats().stream().map(MatchRecord.Seat::deck).toList(),
                    "Deckname je Sitz aus der Lobby-Auswahl");
            assertTrue(r.turns() >= 1, "mindestens ein Zug, war " + r.turns());
            assertFalse(r.counted(), "eine abgewuergte Partie zaehlt nicht");
            assertEquals("abgebrochen", r.excludeReason());
        }
    }

    // ---------------------------------------------------------------- der Haken selbst

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void hakenFeuertGenauEinmalJeSpielUndNieFuerEinTeilspiel() {
        WebGuiGame gui = new WebGuiGame(m -> { });
        List<Game> seen = new ArrayList<>();
        gui.onNewGame(seen::add);

        Game first = emptyGame(null);
        gui.setGameView(null);
        gui.setGameView(first.getView());
        gui.setGameView(null);
        gui.setGameView(first.getView());         // HostedMatch macht das im Teilspiel-Pfad doppelt
        assertEquals(List.of(first), seen, "dasselbe Spiel haengt den Recorder nur einmal an");

        Game subgame = emptyGame(first);
        gui.setGameView(subgame.getView());
        assertEquals(List.of(first), seen, "ein Teilspiel (Shahrazad) ist keine Partie");

        gui.setGameView(first.getView());         // Teilspiel-Ende: zurueck zum Hauptspiel
        assertEquals(List.of(first), seen, "das Hauptspiel wird danach nicht erneut gehakt");

        Game second = emptyGame(null);
        gui.setGameView(second.getView());
        assertEquals(List.of(first, second), seen, "ein neues Spiel bekommt einen eigenen Recorder");

        // Ein neuer Haken faengt von vorn an (naechste Partie ueber dieselbe GUI).
        List<Game> again = new ArrayList<>();
        gui.onNewGame(again::add);
        gui.setGameView(second.getView());
        assertEquals(1, again.size());
        assertSame(second, again.get(0));
    }

    // ---------------------------------------------------------------- Hilfen

    /** Leeres Spiel wie im Szenen-Harness; {@code maingame != null} macht daraus ein Teilspiel. */
    private static Game emptyGame(Game maingame) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String name : List.of("A", "B")) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(AiConfig.DEFAULT.newLobbyPlayer(name)));
        }
        GameRules rules = CommanderRules.create();
        Match match = new Match(rules, players, "Wiring");
        return maingame == null ? new Game(players, rules, match) : new Game(players, rules, match, maingame, 20);
    }

    /** Das Spiel eine Weile laufen lassen (oder bis es von selbst endet). */
    private static void letItRun(HumanMatch match, long millis) throws Exception {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until && match.isRunning()) {
            Thread.sleep(100);
        }
    }

    private static void waitUntil(BooleanSupplier cond, long millis, String what) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Zeitueberschreitung beim Warten auf: " + what);
    }
}
