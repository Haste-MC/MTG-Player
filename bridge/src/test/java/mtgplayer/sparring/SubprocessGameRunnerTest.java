package mtgplayer.sparring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Die Protokolle einer Sparring-Partie duerfen sich nicht gegenseitig ueberschreiben: der Dateiname
 * traegt den Zeitpunkt des Starts, die laufende Nummer und den Gegner. Frueher zaehlte nur eine
 * Nummer ab 0 je Bridge-Start - ein zweiter Lauf (oder ein Neustart) loeschte damit die Protokolle
 * des ersten. Startet kein Kindprozess.
 */
class SubprocessGameRunnerTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 9, 24, 9, 15, 30);

    @Test
    void dateinameTraegtZeitpunktNummerUndGegner() {
        assertEquals("sparring-2026-09-24-091530-0-Gegner A",
                SubprocessGameRunner.logBaseName(WHEN, 0, "Gegner A"));
    }

    @Test
    void zweiPartienDerselbenSekundeBekommenVerschiedeneNamen() {
        assertNotEquals(SubprocessGameRunner.logBaseName(WHEN, 0, "Gegner A"),
                SubprocessGameRunner.logBaseName(WHEN, 1, "Gegner A"));
    }

    @Test
    void zweiLaeufeDerselbenNummerBekommenVerschiedeneNamen() {
        assertNotEquals(SubprocessGameRunner.logBaseName(WHEN, 0, "Gegner A"),
                SubprocessGameRunner.logBaseName(WHEN.plusMinutes(7), 0, "Gegner A"));
    }

    /** Deck-Namen duerfen alles enthalten (siehe DeckStore.fileName) - der Dateiname darf das nicht. */
    @Test
    void deckNameWirdFuerDenDateinamenBereinigt() {
        String name = SubprocessGameRunner.logBaseName(WHEN, 2, "A/B: C?");
        assertTrue(name.endsWith("-A_B_ C_"), name);
    }
}
