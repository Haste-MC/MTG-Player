package mtgplayer.sparring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.stats.CardLog;
import mtgplayer.stats.CardStore;
import mtgplayer.stats.MatchRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integrationsnachweis (Review-Nachtrag zu Task 2, Runde C): {@link SparringRun#playOne} ist der einzige
 * Ort, an dem ein Sparring-Kindprozess wirklich schreibt - {@link SparringRunTest} erreicht ihn nie (dort
 * steht ueberall ein Attrappen-{@link GameRunner}, {@code playOne} selbst laeuft nur ueber
 * {@code Main --sparring-one} bzw. hier direkt). Ein spaeterer Umbau, der {@code ownDecks} oder die
 * Kartensenke dort verliert, faellt ohne diesen Test still unter den Tisch - Sparring ist Kevins
 * wichtigste Datenquelle dafuer (siehe Klassenkommentar {@link SparringRun}).
 *
 * <p>Wie {@code AiMatchTest}: eine kurze, aber ECHTE KI-gegen-KI-Partie (keine {@code Scene}-Attrappe wie
 * in {@code CardRecordingTest}) - das ist der einzige Weg, die tatsaechliche Verdrahtung von
 * {@code ownDecks}/{@code cardSink} durch {@link AiMatch#play} bis in den {@code MatchRecorder} zu
 * pruefen. {@code mtgplayer.data} zeigt dabei auf ein {@code @TempDir} - {@code DeckStore.standard()} und
 * {@code CardStore.standard()} in {@code playOne} landen damit in einem Testverzeichnis, nie in Kevins
 * echtem {@code ~/.mtg-player}.</p>
 */
class SparringRunPlayOneTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void playOneSchreibtEineKartendateiMitEchtenZeilenFuerBeideDecks(@TempDir Path dataDir) throws Exception {
        String vorher = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.data", dataDir.toString());
        try {
            // Beide Decks kommen wie im Betrieb aus demselben DeckStore (SparringOpponents zieht Gegner
            // ebenfalls von dort) - genau der Store, den playOne selbst ueber DeckStore.standard() anlegt.
            DeckStore store = DeckStore.standard();
            store.save("Mein Deck", Precons.load("Abzan Armor [TDC] [2025]"));
            store.save("Gegner", Precons.load("Adaptive Enchantment [C18] [2018]"));

            SparringArgs.Job job = new SparringArgs.Job("Mein Deck", "Gegner", AiConfig.DEFAULT.spec(), 5, 60, 42L);
            List<String> log = new ArrayList<>();

            MatchRecord r = SparringRun.playOne(job, log::add);

            assertNotNull(r);
            assertEquals("sparring", r.source());
            assertEquals(2, r.seats().size());

            // playOne schreibt die Kartendatei selbst und direkt (siehe Klassenkommentar) - ueber
            // dasselbe mtgplayer.data wie eben gesetzt, also denselben CardStore.standard().
            Optional<CardLog> cardLog = CardStore.standard().read(r.id());
            assertTrue(cardLog.isPresent(), "die Kartendatei existiert nach einer echten Sparring-Partie");
            CardLog cl = cardLog.get();
            assertEquals(2, cl.seats().size(), "beide Decks stammen aus demselben Store - beide Sitze "
                    + "bekommen eine Kartenbiografie: " + cl);
            for (CardLog.SeatCards seat : cl.seats()) {
                assertFalse(seat.cards().isEmpty(), "echte Kartenzeilen, kein leerer Sitz: " + seat);
            }
        } finally {
            if (vorher == null) {
                System.clearProperty("mtgplayer.data");
            } else {
                System.setProperty("mtgplayer.data", vorher);
            }
        }
    }
}
