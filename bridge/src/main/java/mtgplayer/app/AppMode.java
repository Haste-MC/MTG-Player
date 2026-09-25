package mtgplayer.app;

import java.awt.Desktop;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
            System.out.println("Kein Fenster geoeffnet - bitte " + url + " im Browser oeffnen ("
                    + fehlgeschlagen.getMessage() + ")");
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
}
