package mtgplayer.carddata;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Schneidet waehrend eines Vorgangs ALLES mit, was auf {@code System.out} UND {@code System.err}
 * geschrieben wird, Zeile fuer Zeile, und reicht es unveraendert an die echten Strome weiter.
 *
 * <p>Beide Strome, weil Forge beim Laden auf stdout nur eine einzige Zeile schreibt; alles
 * Diagnostische (Stacktrace eines nicht parsebaren Skripts, "was not assigned to any set",
 * "Upcoming set ... dated in the future", Zip-Fehler) geht auf stderr. Genau das soll
 * {@link CardDataCheck.Befund#parseMeldungen} tragen.</p>
 *
 * <p>Die Zeilen werden als Bytes gepuffert und erst am Zeilenende als UTF-8 dekodiert; ein Zeichen
 * ueber mehrere {@code write(int)}-Aufrufe bleibt so heil. Eine letzte Zeile ohne Umbruch wird am
 * Ende nachgereicht. Die Strome werden auch dann wiederhergestellt, wenn der Vorgang abbricht.</p>
 */
public final class StromSammler {

    /** Wie {@link Runnable}, darf aber werfen. */
    @FunctionalInterface
    public interface Vorgang {
        void lauf() throws Exception;
    }

    private StromSammler() { }

    /**
     * Fuehrt {@code vorgang} aus und gibt die mitgeschnittenen Zeilen zurueck (stdout und stderr
     * in der Reihenfolge ihres Eintreffens). Wirft der Vorgang, wird er unveraendert weitergeworfen -
     * die Strome sind dann trotzdem wieder die echten.
     */
    public static List<String> einsammeln(Vorgang vorgang) throws Exception {
        List<String> zeilen = new ArrayList<>();
        PrintStream echtOut = System.out;
        PrintStream echtErr = System.err;
        Zeilenstrom out = new Zeilenstrom(echtOut, zeilen);
        Zeilenstrom err = new Zeilenstrom(echtErr, zeilen);
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            vorgang.lauf();
        } finally {
            System.setOut(echtOut);
            System.setErr(echtErr);
            out.abschliessen();
            err.abschliessen();
        }
        return zeilen;
    }

    private static final class Zeilenstrom extends OutputStream {
        private final PrintStream weiter;
        private final List<String> ziel;
        private final ByteArrayOutputStream zeile = new ByteArrayOutputStream();

        Zeilenstrom(PrintStream weiter, List<String> ziel) {
            this.weiter = weiter;
            this.ziel = ziel;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] puffer, int von, int laenge) {
            weiter.write(puffer, von, laenge);
            synchronized (ziel) {
                for (int i = von; i < von + laenge; i++) {
                    byte b = puffer[i];
                    if (b == '\n') {
                        abgeben();
                    } else {
                        zeile.write(b);
                    }
                }
            }
        }

        @Override
        public void flush() {
            weiter.flush();
        }

        void abschliessen() {
            synchronized (ziel) {
                if (zeile.size() > 0) {
                    abgeben();
                }
            }
            weiter.flush();
        }

        // Aufrufer haelt die Sperre auf ziel.
        private void abgeben() {
            String text = zeile.toString(StandardCharsets.UTF_8);
            zeile.reset();
            if (text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
            }
            ziel.add(text);
        }
    }
}
