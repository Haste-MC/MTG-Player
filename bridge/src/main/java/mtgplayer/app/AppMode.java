package mtgplayer.app;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * App-Modus fuer die herunterladbare Windows-Version (Zip zum Entpacken, Doppelklick, kein
 * separates Java-Setup): freie Ports statt der festen 8080/8081 aus Kevins Entwicklungs-Bridge,
 * ein eigenes Browserfenster statt "oeffne deinen Browser von Hand", und eine Schreibpruefung des
 * Ordners, bevor {@code ForgeBoot.init()} eine halbe Minute lang Kartenskripte laedt. Nur aktiv,
 * wenn {@code -Dmtgplayer.app=true} gesetzt ist - ohne diese Property (Kevins laufende Bridge)
 * aendert sich nichts an {@code Main}.
 */
public final class AppMode {

    private AppMode() { }

    /** Ob der App-Modus aktiv ist. Kevins Entwicklungs-Bridge setzt diese Property nie. */
    public static boolean enabled() {
        return Boolean.getBoolean("mtgplayer.app");
    }

    /**
     * Versucht zuerst den Wunschport zu binden, damit die Adresse zwischen Starts moeglichst
     * gleich bleibt. Ist er belegt, liefert das Betriebssystem mit Port 0 einen freien.
     *
     * <p>Zwischen dieser Probe und dem tatsaechlichen Binden durch {@code HttpStatic}/{@code Bridge}
     * liegt immer ein kleines Rennfenster - der hier freie Port koennte in der Zwischenzeit von
     * einem anderen Prozess belegt werden. Das ist bewusst hingenommen (eine Garantie waere ohne
     * atomares "reservieren und weiterreichen" ohnehin nicht zu haben); der Aufrufer behandelt
     * einen Bindefehler beim tatsaechlichen Start als normalen Fehler mit klarer Meldung, nicht
     * als Absturz.</p>
     */
    public static int freePort(int wunsch) throws IOException {
        try (ServerSocket probe = new ServerSocket(wunsch)) {
            return wunsch;
        } catch (IOException belegt) {
            try (ServerSocket frei = new ServerSocket(0)) {
                return frei.getLocalPort();
            }
        }
    }

    /**
     * Reine Kommandozeilen-Bildung fuer {@link #openWindow(String)}, getrennt testbar ohne dass
     * dabei wirklich ein Browser startet (den gibt es auf dem Testsystem ohnehin nicht).
     */
    static List<String> windowCommand(Path browser, String url) {
        return List.of(browser.toString(), "--app=" + url);
    }

    /**
     * Oeffnet die Adresse in einem eigenen Fenster ohne Adressleiste ueber {@code --app=} von Edge
     * oder Chrome, faellt sonst auf den Standardbrowser zurueck. Laesst sich hier nicht ehrlich
     * testen (kein Windows, kein Edge) - deshalb bleibt der eigentliche Start ungetestet. Jeder
     * Fehlschlag fuehrt nur zum naechsten Schritt; der letzte Fehlschlag wird gemeldet, nicht
     * geworfen, denn ohne eigenes Fenster laeuft die App trotzdem weiter und die Adresse steht in
     * der Konsole.
     */
    public static void openWindow(String url) {
        for (Path browser : browserCandidates()) {
            if (Files.isRegularFile(browser)) {
                try {
                    new ProcessBuilder(windowCommand(browser, url)).start();
                    return;
                } catch (IOException fehlgeschlagen) {
                    // naechster Kandidat
                }
            }
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception fehlgeschlagen) {
            // Review-Befund, Punkt 4: der gepackte Starter ist ein Fenster-Programm ohne Konsole -
            // eine blosse println landet im Nichts, der Nutzer saehe niemals ein Fenster UND nie
            // diese Meldung. Die Adresse steht deshalb zusaetzlich in einem Dialog, damit sie sich
            // von Hand in einen Browser eintippen laesst.
            //
            // Eigener Probelauf (Blocker 3, Linux-Abbild): GENAU dieser Zweig hier haengt main() an
            // JOptionPane fest, bis jemand den Dialog wegklickt - in main() steht {@link #openWindow}
            // (nach der Umkehr aus Blocker 3) VOR ForgeBoot.init(), ein blockierender Aufruf hier wuerde
            // also das Kartenladen (und damit die ganze App) auf diesen einen Klick warten lassen, statt
            // im Hintergrund weiterzumachen - genau das Gegenteil dessen, was Blocker 3 erreichen soll.
            // Der Dialog laeuft deshalb in einem eigenen Thread: er erscheint, sobald AWT dazu kommt,
            // haelt aber niemanden auf.
            String meldung = "Kein Fenster geoeffnet - bitte " + url + " im Browser oeffnen ("
                    + fehlgeschlagen.getMessage() + ")";
            System.out.println(meldung);
            dialogAsync(meldung, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Uebliche Windows-Installationspfade fuer Edge und Chrome, Edge zuerst (per-Maschine vor
     * per-Nutzer-Installation): {@code %ProgramFiles%}, {@code %ProgramFiles(x86)%},
     * {@code %LOCALAPPDATA%}. Auf diesem (Linux-)Testsystem sind die Umgebungsvariablen nicht
     * gesetzt, die Liste ist dann leer - {@link #openWindow(String)} faellt dann direkt auf
     * {@code Desktop.browse} zurueck.
     */
    private static List<Path> browserCandidates() {
        List<Path> roots = new ArrayList<>();
        for (String env : new String[] { "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA" }) {
            String value = System.getenv(env);
            if (value != null) {
                roots.add(Paths.get(value));
            }
        }
        List<Path> kandidaten = new ArrayList<>();
        for (Path root : roots) {
            kandidaten.add(root.resolve("Microsoft").resolve("Edge").resolve("Application").resolve("msedge.exe"));
        }
        for (Path root : roots) {
            kandidaten.add(root.resolve("Google").resolve("Chrome").resolve("Application").resolve("chrome.exe"));
        }
        return kandidaten;
    }

    /**
     * Legt eine Probe-Datei an und loescht sie wieder. {@code null}, wenn das Verzeichnis
     * beschreibbar ist, sonst ein Text zum Anzeigen im Fenster/Terminal - noch vor
     * {@code ForgeBoot.init()} aufgerufen, damit die Meldung kommt, bevor Forge Karten laedt.
     */
    public static String writableOrNull(Path appDir) {
        Path probe = appDir.resolve(".schreibtest");
        try {
            Files.deleteIfExists(probe);
            Files.createFile(probe);
        } catch (IOException nichtBeschreibbar) {
            return "Bitte den Ordner an eine Stelle entpacken, an der du schreiben darfst (nicht C:\\Programme).";
        }
        // Anlegen hat geklappt - der Ordner ist beschreibbar. Scheitert nur noch das Aufraeumen
        // (z.B. ein Virenscanner haelt die Datei kurz fest), ist das kein Grund, den Ordner als
        // nicht beschreibbar zu melden - vorher landete ein solcher Fehler im selben catch-Block
        // und meldete faelschlich "nicht beschreibbar", obwohl das Schreiben laengst geklappt hatte.
        try {
            Files.delete(probe);
        } catch (IOException aufraeumenFehlgeschlagen) {
            System.err.println("Probe-Datei " + probe + " konnte nicht geloescht werden: "
                    + aufraeumenFehlgeschlagen.getMessage());
        }
        return null;
    }

    /**
     * Zeigt einen Abbruchgrund als Dialog (Review-Befund, Punkt 4): der gepackte Starter ist ein
     * Fenster-Programm ohne Konsole, {@code System.err} geht ins Nichts - wer nach {@code C:\Programme}
     * entpackt, saehe die (korrekt anschlagende) Schreibpruefung nie, nur ein Fenster, das nicht kommt.
     * Deshalb fuer jeden Abbruchgrund im App-Modus verwendet: Schreibpruefung, kein freier Port,
     * Fenster liess sich nicht oeffnen (siehe {@link #openWindow}).
     *
     * <p>Schreibt IMMER zusaetzlich auf {@code System.err} (falls doch eine Konsole dranhaengt, z. B.
     * beim Aufruf aus einer cmd) und ueberspringt den Dialog in einer headless-Umgebung (Tests, CI) -
     * dort wuerde {@link JOptionPane} sonst mit einer {@code HeadlessException} abstuerzen, obwohl der
     * Abbruch selbst korrekt war.</p>
     */
    public static void showFatalError(String message) {
        System.err.println(message);
        dialog(message, JOptionPane.ERROR_MESSAGE);
    }

    private static void dialog(String message, int type) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(null, message, "MTG-Player", type);
        } catch (RuntimeException keinDialogMoeglich) {
            // bestmoeglich - kein Dialog ist kein Grund, den eigentlichen Abbruch scheitern zu lassen
        }
    }

    /** Wie {@link #dialog}, aber auf einem eigenen Thread (siehe Kommentar in {@link #openWindow}): fuer
     *  einen Abbruchgrund, nach dem main() ohnehin gleich zurueckkehrt (Schreibpruefung, kein freier
     *  Port), ist Blockieren gewollt - fuer die reine Fenster-Fallback-Meldung waere es das nicht, die
     *  App soll trotzdem ganz normal weiter hochfahren (Kartenladen, WebSocket). */
    private static void dialogAsync(String message, int type) {
        Thread t = new Thread(() -> dialog(message, type), "app-mode-dialog");
        t.setDaemon(true);
        t.start();
    }
}
