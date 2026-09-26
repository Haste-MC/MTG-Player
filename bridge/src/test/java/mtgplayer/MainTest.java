package mtgplayer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Blocker 1c (Review-Befund): ein unbekanntes erstes Argument bricht {@link Main#main} mit einer
 * Meldung ab, statt es stillschweigend zu ignorieren und in den normalen Server-Zweig zu fallen.
 * Genau das droht, wenn {@code mtgplayer.proc.ChildJvm#JAVA_BIN} (trotz dessen eigenem Fix) doch
 * einmal auf den gepackten Starter selbst zeigt: der reicht unbekannte Kommandozeilen-Optionen als
 * Programmargumente weiter (args[0] waere dann z.B. "-Xmx4g") - ohne diesen Abbruch wuerde das eine
 * zweite komplette App samt eigenem Fenster hochfahren, pro Sparring-/Bench-Partie.
 *
 * <p>Dieser eine Pfad ist der einzige in {@code Main.main}, der sich ohne echten Server/Forge-Start
 * ehrlich testen laesst: kein {@code -Dmtgplayer.app=true} gesetzt (appMode also aus, die
 * Schreibpruefung bleibt weg) und ein unbekanntes args[0] gibt sofort auf, VOR jedem
 * {@code ForgeBoot.init()} und vor jedem Port-Binden.</p>
 */
class MainTest {

    @Test
    void unbekanntesErstesArgumentBrichtAbOhneServerZuStarten() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Main.main(new String[] { "-Xmx4g" });
        } finally {
            System.setErr(originalErr);
        }
        String meldung = captured.toString(StandardCharsets.UTF_8);
        assertTrue(meldung.contains("-Xmx4g"), "Meldung soll das unbekannte Argument nennen: " + meldung);
        assertTrue(meldung.toLowerCase().contains("unbekannt"), "Meldung soll den Abbruchgrund nennen: " + meldung);
    }

    @Test
    void bekannteArgumenteLoesenDenAbbruchNichtAus() throws Exception {
        // Kein Verhaltensnachweis fuer --bench-one etc. hier (die brauchen echtes Forge/System.exit) -
        // nur die Abgrenzung: ein bekanntes Flag darf NICHT die "unbekanntes Argument"-Meldung
        // ausloesen. --sparring-one ohne zweites Argument wirft stattdessen die eigene, spezifischere
        // IllegalArgumentException (siehe Main) - das genuegt hier als Beleg.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Main.main(new String[] { "--sparring-one" }));
        } finally {
            System.setErr(originalErr);
        }
        assertFalse(captured.toString(StandardCharsets.UTF_8).toLowerCase().contains("unbekanntes erstes argument"));
    }
}
