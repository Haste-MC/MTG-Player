package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
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
        s.card("Nesting Dragon", a, ZoneType.Hand);                        // bleibt auf der Hand liegen
        s.card("Cultivate", a, ZoneType.Library);                          // liegt die ganze Partie in der Bibliothek
        s.card("Grizzly Bears", b, ZoneType.Hand);                         // fremdes Deck - darf nicht auftauchen
        s.token("b_2_2_bird_flying", a, ZoneType.Battlefield);             // Spielstein - darf nicht auftauchen

        MatchRecorder rec = new MatchRecorder(s.game(), "live", null,
                Map.of(rpA, DECK_A, rpB, DECK_B), Set.of(DECK_A), null, null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));
        s.game().fireEvent(new GameEventSpellAbilityCast(
                SpellAbilityView.get(bears.getFirstSpellAbility()), null, 0, null));

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
        assertNotNull(gewirkt.castTurn(), "gewirkte Karte traegt einen castTurn: " + gewirkt);

        CardLog.Card liegtNoch = card(seatA, "Nesting Dragon");
        assertEquals("hand", liegtNoch.end(), "bleibt auf der Hand liegen: " + liegtNoch);
        assertNull(liegtNoch.cast(), "nie gewirkt bleibt null: " + liegtNoch);

        CardLog.Card inDerBibliothek = card(seatA, "Cultivate");
        assertEquals("library", inDerBibliothek.end(), "lag die ganze Partie in der Bibliothek: " + inDerBibliothek);

        assertTrue(seatA.cards().stream().noneMatch(c -> c.name().contains("Bird")),
                "ein Spielstein taucht in keiner Zeile auf: " + seatA.cards());
    }
}
