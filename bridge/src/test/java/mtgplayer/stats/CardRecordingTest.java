package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Kartenbiografie ueber den echten {@link MatchRecorder} (Spec 2026-09-25-kartenaufzeichnung §2,
 * Task-1-Brief Schritt 2). Aufbau wie {@code mtgplayer.ai.GoadTest}/{@code MeldTitaniaTest}
 * ({@code Scene.of(...)}, Karten direkt in Zonen legen), der gewirkte Zauber wird wie in
 * {@link MatchRecorderCombatTest} ueber ein echtes {@code GameEventSpellAbilityCast} eingespeist statt
 * auf eine KI-Entscheidung zu warten - dasselbe Ereignis, das Forge im echten Spiel feuert.
 *
 * <p>Sitz A spielt ein Deck aus {@code ownDecks} und wird aufgezeichnet, Sitz B ein fremdes Deck und
 * darf ueberhaupt keine {@link CardLog.SeatCards}-Zeile erzeugen. Der Recorder wird direkt konstruiert
 * (er meldet sich selbst an Forges Ereignisbus an) und die Partie ueber {@link MatchRecorder#finish()}
 * abgeschlossen - der dokumentierte reguläre Weg fuer Aufrufer von aussen (Test, Abbruch), keine
 * Hintertuer.</p>
 */
class CardRecordingTest {
    private static final String DECK_A = "Titania Landfall";
    private static final String DECK_B = "Anderes Deck";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} schreiben, falls etwas meldet. */
    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    private static CardLog.SeatCards seatCards(CardLog log, int seat) {
        return log.seats().stream().filter(s -> s.seat() == seat).findFirst().orElse(null);
    }

    private static CardLog.Card card(CardLog.SeatCards seat, String name) {
        return seat.cards().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " nicht in " + seat.cards()));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zeichnetNurDasEigeneDeckAufUndOhneSpielsteine() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        RegisteredPlayer rpA = a.getRegisteredPlayer(), rpB = b.getRegisteredPlayer();

        Card bears = s.card("Grizzly Bears", a, ZoneType.Hand);            // wird gewirkt
        Card forest = s.card("Forest", a, ZoneType.Hand);                  // wird gespielt (Land)
        s.card("Nesting Dragon", a, ZoneType.Hand);                        // bleibt auf der Hand liegen
        s.card("Cultivate", a, ZoneType.Library);                          // liegt die ganze Partie in der Bibliothek
        s.card("Grizzly Bears", b, ZoneType.Hand);                         // fremdes Deck - darf nicht auftauchen
        s.token("b_2_2_bird_flying", a, ZoneType.Battlefield);             // Spielstein - darf nicht auftauchen

        MatchRecorder rec = new MatchRecorder(s.game(), "live", null,
                Map.of(rpA, DECK_A, rpB, DECK_B), Set.of(DECK_A), null, null);

        // A's ERSTER eigener Zug (global 1), dann B (global 2), dann A's ZWEITER eigener Zug (global 3) -
        // Forges globale Zugnummer (3) und As eigener Zug (2) laufen damit bewusst auseinander, sonst
        // wuerde der Test die beiden Zaehlweisen nicht unterscheiden (siehe castTurn-Assertions unten).
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));
        s.game().fireEvent(new GameEventSpellAbilityCast(
                SpellAbilityView.get(bears.getFirstSpellAbility()), null, 0, null));
        s.game().fireEvent(new GameEventLandPlayed(a.getView(), forest.getView()));

        rec.finish();
        CardLog log = rec.cardLog();

        assertNotNull(log, "eine gezaehlte Partie liefert einen CardLog");
        assertEquals(1, log.seats().size(), "nur Sitz A steht in ownDecks");

        CardLog.SeatCards seatA = seatCards(log, 0);
        assertNotNull(seatA, "Sitz A wird aufgezeichnet");
        assertEquals(DECK_A, seatA.deck());
        assertNull(seatCards(log, 1), "Sitz B steht nicht in ownDecks und erzeugt keine SeatCards-Zeile");

        CardLog.Card gewirkt = card(seatA, "Grizzly Bears");
        assertTrue(gewirkt.cast() != null && gewirkt.cast() >= 1, "gewirkte Karte traegt cast >= 1: " + gewirkt);
        assertEquals(2, gewirkt.castTurn(), "castTurn zaehlt As EIGENEN Zug (den zweiten), nicht Forges "
                + "globale Zugnummer (waere 3): " + gewirkt);

        CardLog.Card gespielt = card(seatA, "Forest");
        assertEquals(2, gespielt.castTurn(), "castTurn zaehlt auch beim Land den eigenen Zug, nicht die "
                + "globale Zugnummer: " + gespielt);

        CardLog.Card liegtNoch = card(seatA, "Nesting Dragon");
        assertEquals("hand", liegtNoch.end(), "bleibt auf der Hand liegen: " + liegtNoch);
        assertNull(liegtNoch.cast(), "nie gewirkt bleibt null: " + liegtNoch);

        CardLog.Card inDerBibliothek = card(seatA, "Cultivate");
        assertEquals("library", inDerBibliothek.end(), "lag die ganze Partie in der Bibliothek: " + inDerBibliothek);

        assertTrue(seatA.cards().stream().noneMatch(c -> c.name().contains("Bird")),
                "ein Spielstein taucht in keiner Zeile auf: " + seatA.cards());
    }

    /**
     * Befund 3: {@code Player.getAllCards()} ({@link MatchRecorder#resolveEndZones}) und die laufenden
     * Zonenwechsel-Ereignisse ({@link MatchRecorder#cardTally}) ordnen ein Permanent nach dem BEHERRSCHER
     * zu, nicht nach dem Besitzer - ein von A gestohlenes Permanent aus B's Deck sitzt dafuer buchstaeblich
     * in A's eigenen Zonenobjekten. Ohne den Besitzer-Filter bekaeme es trotzdem eine Zeile in A's
     * Kartentabelle: entweder ueber die Endzonen-Auflösung (bleibt bis Partieende auf dem Schlachtfeld
     * stehen) oder ueber die laufende Zaehlung (stirbt waehrend A es kontrolliert - zaehlt sonst als A's
     * eigener Verlust).
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gestohleneKartenLandenNichtInDerEigenenKartentabelle() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        RegisteredPlayer rpA = a.getRegisteredPlayer(), rpB = b.getRegisteredPlayer();

        // B's Sol Ring, gestohlen von A: bleibt bis Partieende unter A's Kontrolle, ohne zu sterben -
        // trifft die Endzonen-Auflösung.
        Card stolenPermanent = s.card("Sol Ring", b, ZoneType.Battlefield);
        b.getZone(ZoneType.Battlefield).remove(stolenPermanent);
        a.getZone(ZoneType.Battlefield).add(stolenPermanent);
        stolenPermanent.setController(a, s.game().getNextTimestamp());

        // B's Grizzly Bears, ebenfalls gestohlen, stirbt aber noch waehrend A sie kontrolliert - trifft
        // die laufende Zaehlung (onCardChangeZone -> cardTally).
        Card stolenAndDies = s.card("Grizzly Bears", b, ZoneType.Battlefield);
        b.getZone(ZoneType.Battlefield).remove(stolenAndDies);
        a.getZone(ZoneType.Battlefield).add(stolenAndDies);
        stolenAndDies.setController(a, s.game().getNextTimestamp());

        MatchRecorder rec = new MatchRecorder(s.game(), "live", null,
                Map.of(rpA, DECK_A, rpB, DECK_B), Set.of(DECK_A), null, null);

        s.game().getAction().moveTo(ZoneType.Graveyard, stolenAndDies, null, null);

        rec.finish();
        CardLog log = rec.cardLog();
        CardLog.SeatCards seatA = seatCards(log, 0);

        assertNotNull(seatA, "Sitz A wird aufgezeichnet");
        assertTrue(seatA.cards().stream().noneMatch(c -> c.name().equals("Sol Ring")),
                "ein auf dem Schlachtfeld gestohlenes Permanent darf am Partieende keine Zeile bekommen: "
                        + seatA.cards());
        assertTrue(seatA.cards().stream().noneMatch(c -> c.name().equals("Grizzly Bears")),
                "ein gestohlenes Permanent, das unter A's Kontrolle stirbt, darf nicht als A's eigener "
                        + "Verlust zaehlen: " + seatA.cards());
    }

    /**
     * Befund 6: {@code counteredGames}/{@code lostGames} (CardStats) und damit die zugrundeliegenden
     * {@code CardLog.Card}-Felder {@code countered}/{@code lost} waren von KEINEM Test beruehrt. Aufbau
     * des Konters wie {@code MatchRecorderTest.gekonterterZauberZaehltBeimZaubernden} (echter Stapel,
     * kein manuell gebautes Ereignis), der Verlust wie {@code ziehenAbwerfenUndMillenAusEchtenZonenwechseln}
     * (echter {@code GameAction.moveTo}).
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gekonterterZauberUndSterbendesPermanentTragenCounteredUndLost() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        RegisteredPlayer rpA = a.getRegisteredPlayer(), rpB = b.getRegisteredPlayer();

        Card bears = s.card("Grizzly Bears", a, ZoneType.Hand);           // wird gewirkt und gekontert
        Card counter = s.card("Counterspell", b, ZoneType.Hand);
        Card giant = s.card("Hill Giant", a, ZoneType.Battlefield);       // stirbt noch waehrend der Partie

        SpellAbility bearsSa = bears.getFirstSpellAbility();
        bearsSa.setActivatingPlayer(a);
        SpellAbility counterSa = counter.getFirstSpellAbility();
        counterSa.setActivatingPlayer(b);

        MatchRecorder rec = new MatchRecorder(s.game(), "live", null,
                Map.of(rpA, DECK_A, rpB, DECK_B), Set.of(DECK_A), null, null);

        s.game().getStack().add(bearsSa);
        counterSa.getTargets().add(bearsSa);          // ohne Ziel legt Forge ihn gar nicht erst
        s.game().getStack().add(counterSa);
        // Forge entfernt einen gekonterten Zauber ohne vorheriges GameEventSpellResolved vom Stapel.
        s.game().fireEvent(new GameEventSpellRemovedFromStack(SpellAbilityView.get(bearsSa)));
        s.game().getAction().moveTo(ZoneType.Graveyard, giant, null, null);

        rec.finish();
        CardLog log = rec.cardLog();
        CardLog.SeatCards seatA = seatCards(log, 0);

        CardLog.Card gekontert = card(seatA, "Grizzly Bears");
        assertEquals(1, gekontert.countered(), "A's Baeren wurden gekontert: " + gekontert);

        CardLog.Card gestorben = card(seatA, "Hill Giant");
        assertEquals(1, gestorben.lost(), "der Riese starb: " + gestorben);
        assertEquals("graveyard", gestorben.end(), "landet im Friedhof: " + gestorben);
    }
}
