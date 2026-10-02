package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entfernt die Dateien hinter einem {@link CardDataCheck.Befund}.
 *
 * <p>Bei einer DUBLETTE darf nicht irgendeine der beiden Dateien fallen, sonst verschwindet unter
 * Umstaenden die Karte, die vorher da war. Deshalb bekommt diese Klasse die Liste der Dateien, die der
 * Abgleich gerade NEU hereingebracht hat (aus {@code git status} im Submodul): von einer Dublette faellt
 * die neue Datei, die bestehende bleibt. Steht keine der Dateien auf der Liste, faellt keine - dann ist
 * die Dublette aelter als dieser Abgleich und gehoert von Hand angesehen.</p>
 *
 * <p>Dasselbe gilt fuer {@code nichtBaubar} und {@code nichtAuffindbar}, sobald ein Schluessel MEHRERE
 * Dateien hat: dann faellt nur die neue. Hat er genau eine, faellt sie.</p>
 *
 * <p>Nichts davon darf lautlos misslingen: ein Schluessel ohne Datei kommt im {@link Ergebnis} zurueck,
 * eine fehlende oder unbrauchbare Neu-Liste ist ein Fehler.</p>
 */
public final class CardDataSweep {

    private static final String PRAEFIX = "forge-gui/res/";

    /**
     * @param entfernt         Zahl der geloeschten Dateien
     * @param ohneDatei        Schluessel aus dem Befund, zu denen es unter {@code res} keine Datei gibt - das
     *                         Problem ist dort NICHT geloest, der Aufrufer muss laut werden
     * @param fortzuschreiben  der Befund ohne diese Schluessel: nur was hierin steht, darf in die
     *                         Ausschlussliste
     */
    public record Ergebnis(int entfernt, List<String> ohneDatei, CardDataCheck.Befund fortzuschreiben) { }

    private CardDataSweep() { }

    /**
     * @param neueDateien Datei mit den neu hereingekommenen Pfaden ({@code forge-gui/res/<relativ zu res>},
     *                    eine je Zeile, Leerraum an den Raendern wird abgeschnitten); sie MUSS existieren,
     *                    leer ist erlaubt
     * @throws NoSuchFileException   wenn die Neu-Liste fehlt
     * @throws IllegalStateException wenn der Befund Dubletten nennt, die Neu-Liste nicht leer ist, aber
     *                               keine ihrer Zeilen auf eine vorhandene Datei unter {@code res} zeigt
     *                               (dann stimmt das Format nicht - nichts wird geloescht)
     */
    public static Ergebnis aussortieren(CardDataCheck.Befund befund, Path res, Path neueDateien)
            throws IOException {
        Set<String> neu = neueLesen(neueDateien);
        boolean dubletten = !befund.doppelteNamen().isEmpty() || !befund.doppelteSetCodes().isEmpty();
        if (dubletten && !neu.isEmpty() && neu.stream().noneMatch(z -> vorhanden(res, z))) {
            throw new IllegalStateException("Neu-Liste " + neueDateien + " ist nicht leer, aber keine ihrer "
                    + neu.size() + " Zeilen zeigt auf eine vorhandene Datei unter " + res
                    + " (erwartet: " + PRAEFIX + "<relativ zu res>) - nichts geloescht, damit keine "
                    + "Dublette still stehen bleibt");
        }
        Map<String, List<Path>> karten = CardFiles.karten(res);
        Map<String, List<Path>> editionen = CardFiles.editionen(res);

        Set<String> ohneDatei = new LinkedHashSet<>();
        List<Path> zuLoeschen = new ArrayList<>();

        List<CardDataCheck.Problem> nichtBaubar = new ArrayList<>();
        for (CardDataCheck.Problem p : befund.nichtBaubar()) {
            List<Path> dateien = karten.get(p.karte());
            if (leer(dateien)) {
                ohneDatei.add(p.karte());
            } else {
                nichtBaubar.add(p);
                zuLoeschen.addAll(einzelnOderNeue(dateien, res, neu));
            }
        }
        List<String> nichtAuffindbar = new ArrayList<>();
        for (String n : befund.nichtAuffindbar()) {
            List<Path> dateien = karten.get(n);
            if (leer(dateien)) {
                ohneDatei.add(n);
            } else {
                nichtAuffindbar.add(n);
                zuLoeschen.addAll(einzelnOderNeue(dateien, res, neu));
            }
        }
        List<String> doppelteNamen = new ArrayList<>();
        for (String n : befund.doppelteNamen()) {
            List<Path> dateien = karten.get(n);
            if (leer(dateien)) {
                ohneDatei.add(n);
            } else {
                doppelteNamen.add(n);
                zuLoeschen.addAll(nurNeue(dateien, res, neu));
            }
        }
        List<String> doppelteSetCodes = new ArrayList<>();
        for (String c : befund.doppelteSetCodes()) {
            List<Path> dateien = editionen.get(c);
            if (leer(dateien)) {
                ohneDatei.add(c);
            } else {
                doppelteSetCodes.add(c);
                zuLoeschen.addAll(nurNeue(dateien, res, neu));
            }
        }

        int entfernt = 0;
        for (Path p : zuLoeschen) {
            if (Files.deleteIfExists(p)) {
                entfernt++;
            }
        }
        return new Ergebnis(entfernt, List.copyOf(ohneDatei), new CardDataCheck.Befund(befund.karten(),
                nichtBaubar, nichtAuffindbar, doppelteSetCodes, doppelteNamen, befund.parseMeldungen()));
    }

    private static Set<String> neueLesen(Path neueDateien) throws IOException {
        if (!Files.isRegularFile(neueDateien)) {
            throw new NoSuchFileException(neueDateien.toString(), null,
                    "Neu-Liste fehlt - eine fehlende Liste ist kein 'keine neuen Dateien' (dafuer: leere Datei)");
        }
        Set<String> neu = new LinkedHashSet<>();
        for (String zeile : Files.readAllLines(neueDateien, StandardCharsets.UTF_8)) {
            String z = zeile.trim();
            if (!z.isEmpty()) {
                neu.add(z);
            }
        }
        return neu;
    }

    /** Zeigt eine Zeile der Neu-Liste auf eine vorhandene Datei unter {@code res}? */
    private static boolean vorhanden(Path res, String zeile) {
        if (!zeile.startsWith(PRAEFIX)) {
            return false;
        }
        try {
            return Files.isRegularFile(res.resolve(zeile.substring(PRAEFIX.length())));
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private static boolean leer(List<Path> dateien) {
        return dateien == null || dateien.isEmpty();
    }

    /** Genau eine Datei faellt wie sie ist; bei mehreren faellt nur, was die Neu-Liste nennt. */
    private static List<Path> einzelnOderNeue(List<Path> dateien, Path res, Set<String> neu) {
        return dateien.size() == 1 ? dateien : nurNeue(dateien, res, neu);
    }

    private static List<Path> nurNeue(List<Path> dateien, Path res, Set<String> neu) {
        if (dateien == null) {
            return List.of();
        }
        List<Path> treffer = new ArrayList<>();
        for (Path p : dateien) {
            // Die Liste aus "git status" nennt Pfade relativ zum Submodul-Wurzelverzeichnis.
            String relativ = PRAEFIX + res.relativize(p).toString().replace('\\', '/');
            if (neu.contains(relativ)) {
                treffer.add(p);
            }
        }
        return treffer;
    }
}
