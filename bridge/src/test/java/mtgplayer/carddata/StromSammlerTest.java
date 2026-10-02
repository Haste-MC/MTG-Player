package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class StromSammlerTest {

    /** Der Kern von Befund 3: Forge meldet auf stderr, und das muss im Befund landen. */
    @Test
    void eineMeldungVonStderrLandetImBefund() throws Exception {
        List<String> zeilen = StromSammler.einsammeln(() -> {
            System.out.println("eine Zeile auf stdout");
            System.err.println("The card Foo was not assigned to any set");
        });
        assertTrue(zeilen.contains("The card Foo was not assigned to any set"), zeilen.toString());
        assertTrue(zeilen.contains("eine Zeile auf stdout"), zeilen.toString());
    }

    @Test
    void utf8UeberMehrereEinzelbytesBleibtHeil() throws Exception {
        List<String> zeilen = StromSammler.einsammeln(() -> {
            byte[] roh = "Jötun Grunt – Ünï".getBytes(StandardCharsets.UTF_8);
            for (byte b : roh) {
                System.err.write(b);    // Byte fuer Byte, wie ein schlecht gepufferter Schreiber
            }
            System.err.write('\n');
        });
        assertEquals(List.of("Jötun Grunt – Ünï"), zeilen);
    }

    @Test
    void letzteZeileOhneUmbruchGehtNichtVerloren() throws Exception {
        List<String> zeilen = StromSammler.einsammeln(() -> {
            System.out.print("erste\r\nzweite ohne Umbruch");
            System.err.print("auch ohne");
        });
        assertEquals(List.of("erste", "zweite ohne Umbruch", "auch ohne"), zeilen);
    }

    @Test
    void durchgereichtUndAuchBeiAbbruchWiederhergestellt() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream echtOut = new ByteArrayOutputStream();
        ByteArrayOutputStream echtErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(echtOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(echtErr, true, StandardCharsets.UTF_8));
        PrintStream vorherOut = System.out;
        PrintStream vorherErr = System.err;
        try {
            assertThrows(IllegalStateException.class, () -> StromSammler.einsammeln(() -> {
                System.out.println("nach stdout");
                System.err.print("nach stderr ohne Umbruch");
                throw new IllegalStateException("Laden abgebrochen");
            }));
            assertSame(vorherOut, System.out);
            assertSame(vorherErr, System.err);
            assertEquals("nach stdout" + System.lineSeparator(), echtOut.toString(StandardCharsets.UTF_8));
            assertEquals("nach stderr ohne Umbruch", echtErr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
