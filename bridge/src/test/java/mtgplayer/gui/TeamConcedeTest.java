package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.BooleanNode;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.match.CommanderRules;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Aufgeben im Team: gibt nur der eigene Sitz auf, solange ein Partner lebt (Aufgabe 12). Forges eigener Weg
 * ({@code AbstractGuiGame.concede()}) beendet danach das ganze Spiel - fuer den KI-Partner, der weiterspielen
 * soll, ist das falsch; in jeder anderen Lage bleibt Forges Weg, weil es ohne Partner nichts zum Zuschauen gibt.
 *
 * <p>Die Stellung ist eine echte Partie mit vier Sitzen, aber ohne laufenden Spiel-Thread: das genuegt, um die
 * Entscheidung ({@link WebGuiGame#sitzMitLebendemPartner}) und ihre Wirkung auf den Sitz zu pruefen. Dass die
 * laufende Partie danach WIRKLICH weiterspielt und "End game" sie beendet, zeigt erst der Test ueber das
 * Protokoll ({@code BridgeEndToEndTest}) - ohne Spiel-Thread wartet niemand auf eine Eingabe.</p>
 */
class TeamConcedeTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Alle in diesem Test gebauten Oberflaechen - {@link #tickerBeenden()} raeumt sie ab. */
    private static final List<WebGuiGame> aufgebaut = new CopyOnWriteArrayList<>();

    /**
     * {@code setGameView} mit einem echten Spiel startet einen {@code thinking-ticker}-Thread (Daemon, 1 s),
     * der in den Transport des Tests schreibt. Ohne Abbau lebt er bis zum Ende der Suite weiter und
     * schreibt in Listen, die andere Tests lesen. {@code resetForNewMatch()} ist der unterstuetzte Weg,
     * ihn zu beenden.
     */
    @AfterEach
    void tickerBeenden() {
        for (WebGuiGame g : aufgebaut) {
            g.resetForNewMatch();
        }
        aufgebaut.clear();
    }

    private record Tisch(Game game, WebGuiGame gui, List<Object> gesendet, List<Player> sitze) {
        Player ich() {
            return sitze.get(0);
        }

        Player partner() {
            return sitze.get(1);
        }
    }

    /** Sitz 0 ist der Mensch; {@code teams} je Sitz (null: gar keine Teams am {@code gui}). */
    private static Tisch aufbauen(List<Integer> teams) {
        List<RegisteredPlayer> reg = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RegisteredPlayer rp = new RegisteredPlayer(new Deck());
            rp.setPlayer(i == 0 ? new LobbyPlayerHuman("Du") : new LobbyPlayerAi("KI " + i, null));
            rp.setTeamNumber(teams == null ? -1 : teams.get(i));
            reg.add(rp);
        }
        var rules = CommanderRules.create();
        Game game = new Game(reg, rules, new Match(rules, reg, "TeamConcedeTest"));
        game.setAge(GameStage.Play);
        List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
        List<Object> gesendet = Collections.synchronizedList(new ArrayList<>());
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        aufgebaut.add(gui);
        if (teams != null) {
            gui.setTeams(teams);
        }
        gui.setGameView(game.getView());
        gui.setOriginalGameController(sitze.get(0).getView(), (PlayerControllerHuman) sitze.get(0).getController());
        return new Tisch(game, gui, gesendet, sitze);
    }

    private static Messages.Choice naechsteFrage(Tisch t) throws InterruptedException {
        for (long ende = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < ende; ) {
            synchronized (t.gesendet()) {
                for (Object o : t.gesendet()) {
                    if (o instanceof Messages.Choice c) {
                        t.gesendet().remove(o);
                        return c;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("keine Rueckfrage gesendet");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void imTeamMitLebendemPartnerGibtNurDerEigeneSitzAuf() {
        Tisch t = aufbauen(List.of(1, 1, 2, 2));
        assertSame(t.ich().getController(), t.gui().sitzMitLebendemPartner(t.game().getView()),
                "Partner im selben Team, lebt: nur den eigenen Sitz aufgeben");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void istDerPartnerSchonDraussenGiltForgesWeg() {
        Tisch t = aufbauen(List.of(1, 1, 2, 2));
        t.partner().setHasLost(true);
        assertNull(t.gui().sitzMitLebendemPartner(t.game().getView()),
                "ohne lebenden Partner gibt es nichts zum Zuschauen - die Partie endet wie bisher");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gegnerischeTeamsZaehlenNichtAlsPartner() {
        Tisch t = aufbauen(List.of(1, 2, 3, 4));
        assertNull(t.gui().sitzMitLebendemPartner(t.game().getView()), "Free-for-all: jeder sitzt allein im Team");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void nurLebendeGegnerImSelbenTeamNichtGemeintSindAndereTeams() {
        Tisch t = aufbauen(List.of(1, 2, 2, 2));
        assertNull(t.gui().sitzMitLebendemPartner(t.game().getView()),
                "drei Sitze im anderen Team sind keine Partner, auch wenn sie leben");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneTeamsOderMitTeamNummerMinusEinsGibtEsKeinenPartner() {
        Tisch ohne = aufbauen(null);
        assertNull(ohne.gui().sitzMitLebendemPartner(ohne.game().getView()), "keine Teams am gui");
        Tisch keins = aufbauen(List.of(-1, -1, -1, -1));
        assertNull(keins.gui().sitzMitLebendemPartner(keins.game().getView()),
                "-1 heisst 'kein Team': zwei Sitze ohne Team sind keine Partner");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void bereitsAusgeschiedenerMenschHatKeinenSitzMehrZumAufgeben() {
        Tisch t = aufbauen(List.of(1, 1, 2, 2));
        t.ich().setHasLost(true);
        assertNull(t.gui().sitzMitLebendemPartner(t.game().getView()), "wer draussen ist, gibt nicht noch einmal auf");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void onConcedeImTeamGibtNurDenEigenenSitzAufUndFragtVorher() throws Exception {
        Tisch t = aufbauen(List.of(1, 1, 2, 2));
        CompletableFuture<Void> lauf = CompletableFuture.runAsync(() -> t.gui().onConcede());

        Messages.Choice frage = naechsteFrage(t);
        assertEquals("confirm", frage.kind());
        assertFalse(t.ich().hasLost(), "vor der Antwort ist noch nichts aufgegeben");
        assertTrue(t.gui().broker().answer(frage.id(), BooleanNode.TRUE));
        lauf.get(30, TimeUnit.SECONDS);

        assertNotNull(t.ich().getOutcome(), "der eigene Sitz hat aufgegeben");
        assertEquals(forge.game.player.GameLossReason.Conceded, t.ich().getOutcome().lossState,
                "als Aufgabe verbucht, nicht als sonst ein Verlust - der Datensatz sagt 'aufgegeben'");
        assertTrue(t.ich().hasLost(), "der Sitz ist ausgeschieden");
        assertFalse(t.partner().hasLost(), "der Partner spielt weiter");
        assertFalse(t.game().isGameOver(), "die Partie laeuft: das andere Team hat nicht gewonnen");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void onConcedeMitNeinLaesstDenSitzImSpiel() throws Exception {
        Tisch t = aufbauen(List.of(1, 1, 2, 2));
        CompletableFuture<Void> lauf = CompletableFuture.runAsync(() -> t.gui().onConcede());
        Messages.Choice frage = naechsteFrage(t);
        assertTrue(t.gui().broker().answer(frage.id(), BooleanNode.FALSE));
        lauf.get(30, TimeUnit.SECONDS);

        assertNull(t.ich().getOutcome(), "'Cancel' gibt nichts auf");
        assertFalse(t.ich().hasLost());
    }
}
