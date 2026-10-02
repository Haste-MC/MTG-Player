package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Ordnet Forges Kartendateien ihren Schluesseln zu: Kartenskripte unter {@code cardsfolder/} ihrem
 * Kartennamen, Editionsdateien unter {@code editions/} ihrem Set-Code.
 *
 * <p>Das ist die Haelfte des Abgleichs, die OHNE Forge auskommt - und die gebraucht wird, sobald eine
 * Karte durchfaellt: der Pruefmodus kennt dann ihren NAMEN, entfernt werden muss aber ihre DATEI.</p>
 *
 * <p>Kartenskripte unter {@code cardsfolder/} ohne {@code Name:}-Zeile werden uebersprungen und von
 * {@link #uebersprungen} gezaehlt. Editionsdateien ohne {@code Code=}-Zeile verschwinden lautlos.</p>
 */
public final class CardFiles {

    private CardFiles() { }

    public static Map<String, List<Path>> karten(Path res) throws IOException {
        return index(res.resolve("cardsfolder"), CardFiles::kartenname);
    }

    public static Map<String, List<Path>> editionen(Path res) throws IOException {
        return index(res.resolve("editions"), CardFiles::setCode);
    }

    /** Schluessel, die in mehr als einer Datei stehen - alphabetisch, damit Berichte vergleichbar sind. */
    public static List<String> doppelte(Map<String, List<Path>> index) {
        List<String> treffer = new ArrayList<>();
        index.forEach((schluessel, dateien) -> {
            if (dateien.size() > 1) {
                treffer.add(schluessel);
            }
        });
        treffer.sort(String::compareTo);
        return treffer;
    }

    /** Dateien unter {@code cardsfolder/}, die keine {@code Name:}-Zeile tragen. */
    public static int uebersprungen(Path res) throws IOException {
        Path dir = res.resolve("cardsfolder");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> dateien = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) dateien.filter(Files::isRegularFile)::iterator) {
                if (kartenname(p) == null) {
                    n++;
                }
            }
        }
        return n;
    }

    private interface Schluessel {
        String of(Path datei) throws IOException;
    }

    private static Map<String, List<Path>> index(Path dir, Schluessel schluessel) throws IOException {
        Map<String, List<Path>> index = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return index;
        }
        try (Stream<Path> dateien = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) dateien.filter(Files::isRegularFile)::iterator) {
                String s = schluessel.of(p);
                if (s != null) {
                    index.computeIfAbsent(s, k -> new ArrayList<>()).add(p);
                }
            }
        }
        index.values().forEach(l -> l.sort(Path::compareTo));
        return index;
    }

    /** @return der Wert der ERSTEN {@code Name:}-Zeile, oder {@code null}. */
    private static String kartenname(Path datei) throws IOException {
        return ersteZeileMit(datei, "Name:");
    }

    /** @return der Wert der ERSTEN {@code Code=}-Zeile, oder {@code null}. */
    private static String setCode(Path datei) throws IOException {
        return ersteZeileMit(datei, "Code=");
    }

    private static String ersteZeileMit(Path datei, String praefix) throws IOException {
        // Zeilenweise statt readString: cardsfolder/ hat ueber 30.000 Dateien, und gebraucht wird
        // immer nur die eine Zeile am Anfang.
        try (var zeilen = Files.lines(datei, StandardCharsets.UTF_8)) {
            return zeilen.filter(z -> z.startsWith(praefix))
                    .map(z -> z.substring(praefix.length()).trim())
                    .findFirst().orElse(null);
        } catch (java.io.UncheckedIOException kaputteKodierung) {
            // Eine Datei, die sich nicht als UTF-8 lesen laesst, ist kein Kartenskript dieser Sammlung.
            return null;
        }
    }
}
