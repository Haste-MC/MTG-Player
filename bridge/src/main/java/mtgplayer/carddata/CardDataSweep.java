package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
public final class CardDataSweep {

    private CardDataSweep() { }

    public static int aussortieren(CardDataCheck.Befund befund, Path res, Path neueDateien)
            throws IOException {
        Set<String> neu = Set.copyOf(Files.isRegularFile(neueDateien)
                ? Files.readAllLines(neueDateien, StandardCharsets.UTF_8)
                : List.of());
        Map<String, List<Path>> karten = CardFiles.karten(res);
        Map<String, List<Path>> editionen = CardFiles.editionen(res);

        List<Path> zuLoeschen = new ArrayList<>();
        befund.nichtBaubar().forEach(p -> zuLoeschen.addAll(karten.getOrDefault(p.karte(), List.of())));
        befund.nichtAuffindbar().forEach(n -> zuLoeschen.addAll(karten.getOrDefault(n, List.of())));
        befund.doppelteNamen().forEach(n -> zuLoeschen.addAll(nurNeue(karten.get(n), res, neu)));
        befund.doppelteSetCodes().forEach(c -> zuLoeschen.addAll(nurNeue(editionen.get(c), res, neu)));

        int entfernt = 0;
        for (Path p : zuLoeschen) {
            if (Files.deleteIfExists(p)) {
                entfernt++;
            }
        }
        return entfernt;
    }

    private static List<Path> nurNeue(List<Path> dateien, Path res, Set<String> neu) {
        if (dateien == null) {
            return List.of();
        }
        List<Path> treffer = new ArrayList<>();
        for (Path p : dateien) {
            // Die Liste aus "git status" nennt Pfade relativ zum Submodul-Wurzelverzeichnis.
            String relativ = "forge-gui/res/" + res.relativize(p).toString().replace('\\', '/');
            if (neu.contains(relativ)) {
                treffer.add(p);
            }
        }
        return treffer;
    }
}
