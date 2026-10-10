package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.match.CommanderRules;
import mtgplayer.protocol.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Die Handkarten des Partners sind unsere Entscheidung im Serialisierer - Forges
 * {@code CardView.canBeShownTo} sagt dazu Nein. Deshalb eine eigene, eng gefasste Regel.
 *
 * <p>Der obere Teil prueft die reine Regel ({@link WebGuiGame#istPartnerhand}), der untere die
 * Verdrahtung: ein echter Snapshot einer echten Stellung. Die Regel allein bliebe gruen, wenn
 * {@code mayView} sie gar nicht aufriefe oder der Haken nie ankaeme.</p>
 */
class PartnerHandTest {

    private static final Map<Integer, Integer> TEAMS = Map.of(0, 1, 1, 1, 2, 2, 3, 2);

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    // ---- die reine Regel -------------------------------------------------------------

    @Test
    void handDesPartnersJa() {
        assertTrue(WebGuiGame.istPartnerhand(1, ZoneType.Hand, 0, TEAMS));
    }

    @Test
    void eigeneHandNichtUeberDieseRegel() {
        assertFalse(WebGuiGame.istPartnerhand(0, ZoneType.Hand, 0, TEAMS), "die eigene Hand regelt Forge selbst");
    }

    @Test
    void gegnerischeHandNein() {
        assertFalse(WebGuiGame.istPartnerhand(2, ZoneType.Hand, 0, TEAMS));
    }

    @Test
    void andereZoneDesPartnersNein() {
        assertFalse(WebGuiGame.istPartnerhand(1, ZoneType.Library, 0, TEAMS), "nur die Hand, nicht die Bibliothek");
    }

    @Test
    void ohneMenschlichenSitzNein() {
        assertFalse(WebGuiGame.istPartnerhand(1, ZoneType.Hand, null, TEAMS), "Zuschauer haben kein eigenes Team");
    }

    @Test
    void ohneTeamsNein() {
        assertFalse(WebGuiGame.istPartnerhand(1, ZoneType.Hand, 0, Map.of()));
    }

    @Test
    void ohneZoneNein() {
        assertFalse(WebGuiGame.istPartnerhand(1, null, 0, TEAMS), "eine Karte ohne Zone ist nie 'Hand des Partners'");
    }

    @Test
    void sitzOhneTeamNein() {
        assertFalse(WebGuiGame.istPartnerhand(5, ZoneType.Hand, 0, TEAMS), "ein unbekannter Sitz ist kein Partner");
        assertFalse(WebGuiGame.istPartnerhand(1, ZoneType.Hand, 0, Map.of(0, -1, 1, -1)),
                "-1 heisst 'kein Team', zwei Sitze ohne Team sind keine Partner");
    }

    // ---- die Verdrahtung: Snapshot einer echten Stellung -----------------------------------

    /** Alle in diesem Test gebauten Oberflaechen - {@link #tickerBeenden()} raeumt sie ab. */
    private static final List<WebGuiGame> aufgebaut = new CopyOnWriteArrayList<>();

    /**
     * Jeder {@code setGameView} mit einem echten Spiel startet einen {@code thinking-ticker}-Thread
     * (Daemon, 1 s), der Nachrichten in {@code gesendet} schreibt. Ohne Abbau bleibt er nach dem Test
     * am Leben und schreibt bis zum Ende der Suite weiter - elf Tickers gleichzeitig in Listen, die
     * spaeter von anderen Tests gelesen werden (das war die ConcurrentModificationException im
     * Vollauf). {@code resetForNewMatch()} ist der unterstuetzte Weg, den Ticker zu beenden.
     */
    @AfterEach
    void tickerBeenden() {
        for (WebGuiGame g : aufgebaut) {
            g.resetForNewMatch();
        }
        aufgebaut.clear();
    }

    /** Vier Sitze, Team 1 = Mensch (Sitz 0) + Sitz 1, Team 2 = Sitz 2 + 3; Hand und Bibliothek je Sitz gefuellt. */
    private record Tisch(Game game, WebGuiGame gui, List<Object> gesendet, List<Player> sitze) {
        Player ich() {
            return sitze.get(0);
        }

        Player partner() {
            return sitze.get(1);
        }

        Player gegner() {
            return sitze.get(2);
        }

        /** Der Snapshot, den ein Browser jetzt bekaeme. */
        Snapshot snapshot() {
            gesendet.clear();
            gui.pushState();
            // Kopie unter dem Monitor: synchronizedList sperrt nur Einzelaufrufe, nicht das Iterieren; ein
            // fremder Schreiber (Ticker) wuerfe sonst mitten im stream() eine ConcurrentModificationException.
            List<Object> kopie;
            synchronized (gesendet) {
                kopie = new ArrayList<>(gesendet);
            }
            return kopie.stream().filter(Snapshot.class::isInstance).map(Snapshot.class::cast).findFirst()
                    .orElseGet(() -> fail("pushState hat keinen Snapshot gesendet"));
        }
    }

    /** Mit menschlichem Sitz 0 oder ohne (Zuschauer: lauter KI, kein lokaler Spieler am gui). */
    /** {@code partnerHandZeigen == null}: den Setter gar nicht rufen - so wird die Vorgabe des Feldes
     *  geprueft und nicht die zuletzt gesetzte Fassung. */
    private static Tisch aufbauen(boolean mitMensch, Boolean partnerHandZeigen) {
        List<Integer> teams = List.of(1, 1, 2, 2);
        List<RegisteredPlayer> reg = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RegisteredPlayer rp = new RegisteredPlayer(new Deck());
            rp.setPlayer(i == 0 && mitMensch ? new LobbyPlayerHuman("Du") : new LobbyPlayerAi("KI " + i, null));
            rp.setTeamNumber(teams.get(i));
            reg.add(rp);
        }
        var rules = CommanderRules.create();
        Game game = new Game(reg, rules, new Match(rules, reg, "PartnerHandTest"));
        game.setAge(GameStage.Play);
        List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
        for (Player p : sitze) {
            karte("Grizzly Bears", p, ZoneType.Hand, game);
            karte("Giant Growth", p, ZoneType.Hand, game);
            karte("Forest", p, ZoneType.Library, game);
        }
        List<Object> gesendet = Collections.synchronizedList(new ArrayList<>());
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        aufgebaut.add(gui);
        gui.setTeams(teams);
        if (partnerHandZeigen != null) {
            gui.setRevealPartnerHand(partnerHandZeigen);
        }
        gui.setGameView(game.getView());
        if (mitMensch) {
            Player ich = sitze.get(0);
            gui.setOriginalGameController(ich.getView(), (PlayerControllerHuman) ich.getController());
        }
        return new Tisch(game, gui, gesendet, sitze);
    }

    /** Lebende Taktgeber der Denk-Anzeige (Daemon-Threads "thinking-ticker"). */
    private static long lebendeTicker() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "thinking-ticker".equals(t.getName()) && t.isAlive()).count();
    }

    /**
     * Beweis fuer den Abbau: {@code setGameView} startet einen Taktgeber, {@code resetForNewMatch()} beendet
     * ihn wieder. Gezaehlt wird relativ zum Stand davor - andere Testklassen in derselben JVM duerfen ihre
     * eigenen haben, dieser Test darf nur keinen zusaetzlichen zurueckbehalten. Faellt der Abbau aus, bleiben
     * Taktgeber bis zum Ende der Suite am Leben und schreiben in fremde Listen (ConcurrentModificationException).
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void resetForNewMatchBeendetDenTaktgeberDerDenkAnzeige() throws InterruptedException {
        long vorher = lebendeTicker();
        Tisch t = aufbauen(true, null);
        assertEquals(vorher + 1, lebendeTicker(), "setGameView hat genau einen Taktgeber gestartet");
        t.gui().resetForNewMatch();
        // close() unterbricht nur; der Thread braucht einen Augenblick, bis er aus dem sleep() heraus ist
        for (long ende = System.currentTimeMillis() + 5000; lebendeTicker() > vorher && System.currentTimeMillis() < ende; ) {
            Thread.sleep(20);
        }
        assertEquals(vorher, lebendeTicker(), "nach resetForNewMatch lebt der Taktgeber nicht mehr");
    }

    private static Card karte(String name, Player owner, ZoneType zone, Game game) {
        var paper = forge.model.FModel.getMagicDb().getCommonCards().getCard(name);
        Card c = Card.fromPaperCard(paper, owner);
        c.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(zone).add(c);
        return c;
    }

    private static List<Snapshot.CardSnap> handVon(Snapshot snap, Player p) {
        Snapshot.PlayerSnap ps = snap.players().stream().filter(x -> x.id() == p.getId()).findFirst().orElseThrow();
        assertFalse(ps.hand().isEmpty(), "Voraussetzung: die Hand ist gefuellt");
        return ps.hand().stream().map(id -> snap.cards().get(id)).toList();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mitHakenSiehtDerMenschDiePartnerhandMitEinzelheiten() {
        Tisch t = aufbauen(true, true);
        Snapshot snap = t.snapshot();

        List<Snapshot.CardSnap> hand = handVon(snap, t.partner());
        assertEquals(2, hand.size());
        for (Snapshot.CardSnap c : hand) {
            assertNotNull(c, "die Karte steht im Woerterbuch");
            assertFalse(c.faceDown(), "offen, nicht als Rueckseite");
            assertNotNull(c.name(), "mit Namen - sonst zeigt der Tisch nur eine leere Karte");
            assertNotNull(c.imageKey(), "mit Bild");
            assertEquals("Hand", c.zone());
        }
        assertEquals(List.of("Giant Growth", "Grizzly Bears"),
                hand.stream().map(Snapshot.CardSnap::name).sorted().toList());
        // Die eigene Hand blieb sichtbar - die Regel ergaenzt Forge, sie ersetzt es nicht.
        assertTrue(handVon(snap, t.ich()).stream().noneMatch(Snapshot.CardSnap::faceDown), "eigene Hand weiter offen");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneSetterAufrufBleibtDiePartnerhandVerdeckt() {
        // Jede andere Pruefung setzt den Haken ausdruecklich - damit waere eine Vorgabe "true" nie
        // aufgefallen, und eine frisch gebaute Bridge haette die Partnerhand von sich aus gezeigt.
        Tisch t = aufbauen(true, null);
        Snapshot snap = t.snapshot();

        for (Snapshot.CardSnap c : handVon(snap, t.partner())) {
            assertTrue(c.faceDown(), "ohne gesetzten Haken bleibt die Partnerhand verdeckt");
            assertNull(c.name());
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneHakenBleibtDiePartnerhandVerdeckt() {
        Tisch t = aufbauen(true, false);   // der Standard: der Haken ist aus
        Snapshot snap = t.snapshot();

        for (Snapshot.CardSnap c : handVon(snap, t.partner())) {
            assertTrue(c.faceDown(), "Partnerhand ohne Haken verdeckt");
            assertNull(c.name(), "und ohne Namen - keine Kartendetails im Snapshot");
            assertNull(c.imageKey());
        }
        assertTrue(handVon(snap, t.ich()).stream().noneMatch(Snapshot.CardSnap::faceDown), "eigene Hand bleibt offen");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gegnerischeHandBleibtMitHakenVerdeckt() {
        Tisch t = aufbauen(true, true);
        Snapshot snap = t.snapshot();

        for (Snapshot.CardSnap c : handVon(snap, t.gegner())) {
            assertTrue(c.faceDown(), "der Haken gilt dem Partner, nicht dem Gegner");
            assertNull(c.name());
        }
        for (Snapshot.CardSnap c : handVon(snap, t.sitze().get(3))) {
            assertTrue(c.faceDown(), "auch dessen Partner bleibt fuer mich Gegner");
            assertNull(c.name());
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void bibliothekDesPartnersBleibtMitHakenVerdeckt() {
        Tisch t = aufbauen(true, true);
        // Die Bibliothek steht nicht im Snapshot (nur ihre Groesse), aber mayView wird dort befragt, wo
        // sie auftauchen koennte (Auswahldialoge, "Karten ansehen"): die Antwort selbst ist die Pruefung.
        Card oben = t.partner().getCardsIn(ZoneType.Library).get(0);
        assertFalse(t.gui.mayView(oben.getView()), "Partner-Bibliothek: nein, auch mit Haken");
        assertFalse(t.gui.mayView(t.gegner().getCardsIn(ZoneType.Library).get(0).getView()), "Gegner-Bibliothek: nein");
        // Gegenprobe, dass dieselbe Abfrage fuer die Partnerhand Ja sagt - sonst pruefte der Test nichts.
        Card inHand = t.partner().getCardsIn(ZoneType.Hand).get(0);
        assertTrue(t.gui.mayView(inHand.getView()), "Partnerhand: ja");
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void karteDesPartnersAufDemSchlachtfeldBleibtUnberuehrt() {
        Tisch t = aufbauen(true, true);
        Card auf = karte("Grizzly Bears", t.partner(), ZoneType.Battlefield, t.game());
        Card verdeckt = karte("Hill Giant", t.partner(), ZoneType.Battlefield, t.game());
        verdeckt.turnFaceDown(true);
        assertTrue(t.gui.mayView(auf.getView()), "offene Karte im Spiel: wie immer fuer alle sichtbar");
        Snapshot snap = t.snapshot();
        Snapshot.CardSnap v = snap.cards().get(verdeckt.getId());
        assertTrue(v.faceDown());
        assertNull(v.verdeckt(), "was unter des Partners verdeckter Karte liegt, zeigt der Haken nicht");
    }

    /**
     * Ohne menschlichen Sitz greift die Partnerregel nie. Forge laesst einen Zuschauer ohnehin alles sehen
     * ({@code AbstractGuiGame.mayView} ist ohne lokale Spieler immer wahr) - der Haken darf daran weder
     * etwas aendern noch werfen: die Sicht mit und ohne Haken ist dieselbe.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zuschauerPartieIstMitUndOhneHakenGleich() {
        Tisch mit = aufbauen(false, true);
        Tisch ohne = aufbauen(false, false);
        assertTrue(mit.gui.getLocalPlayers().isEmpty(), "Voraussetzung: kein menschlicher Sitz");
        Snapshot a = mit.snapshot();
        Snapshot b = ohne.snapshot();
        assertTrue(a.spectator(), "es ist eine Zuschauer-Sicht");
        assertEquals(sicht(b), sicht(a), "der Haken aendert am Zuschauerbild nichts");
        assertFalse(WebGuiGame.istPartnerhand(1, ZoneType.Hand, null, TEAMS),
                "und die Regel selbst sagt ohne menschlichen Sitz Nein");
    }

    /** Je Karte (verdeckt?, Name) - die Karten-Ids sind je Tisch andere, deshalb nur der Inhalt. */
    private static List<String> sicht(Snapshot s) {
        return s.cards().values().stream().map(c -> c.faceDown() + ":" + c.name()).sorted().toList();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneTeamsZeigtDerHakenNichts() {
        Tisch t = aufbauen(true, true);
        t.gui.setTeams(null);               // Haken an, aber die Partie hat keine Teams
        for (Snapshot.CardSnap c : handVon(t.snapshot(), t.partner())) {
            assertTrue(c.faceDown(), "ohne echte Teams gibt es keinen Partner");
        }
    }
}
