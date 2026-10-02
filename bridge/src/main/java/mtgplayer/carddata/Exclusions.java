package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Liste der Kartennamen und Set-Codes, die unsere Forge-Fassung nicht verkraftet
 * ({@code docs/kartendaten-ausgeschlossen.txt}).
 *
 * <p>Sie hat genau einen Zweck: der woechentliche Abgleich soll ruhig sein. Ohne sie wuerde upstream
 * jede Woche dieselben Karten hereinreichen, die Pruefung sie jede Woche wieder aussortieren, und der
 * Bericht waere jedes Mal gleich laut - bis ihn niemand mehr liest. Mit ihr nennt der Bericht nur, was
 * NEU dazugekommen ist.</p>
 *
 * <p>Sie gilt fuer unsere Engine-Fassung. Beim Wechsel auf ein neueres Forge wird sie geleert und neu
 * aufgebaut - ihre Laenge ist dann zugleich die Antwort auf „was bekomme ich dadurch zurueck?".</p>
 *
 * <p>Format: eine Zeile je Eintrag, {@code Schluessel\tGrund\tDatum}, alphabetisch. Zeilen, die mit
 * {@code #} beginnen, und Leerzeilen sind Erklaerung fuer den Menschen.</p>
 */
public final class Exclusions {

    public record Eintrag(String schluessel, String grund, String seit) { }

    private static final String KOPF = """
            # Karten und Set-Codes, die unsere Forge-Fassung nicht bauen kann.
            # Erzeugt von scripts/kartendaten-sync.sh - von Hand aendern ist erlaubt, aber unnoetig.
            # Spalten (Tabulator): Schluessel, Grund, seit wann.
            """;

    private Exclusions() { }

    public static List<Eintrag> lesen(Path datei) throws IOException {
        List<Eintrag> eintraege = new ArrayList<>();
        if (!Files.isRegularFile(datei)) {
            return eintraege;
        }
        for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            if (zeile.isBlank() || zeile.startsWith("#")) {
                continue;
            }
            String[] teile = zeile.split("\t", 3);
            if (teile.length == 3) {
                eintraege.add(new Eintrag(teile[0], teile[1], teile[2]));
            }
        }
        return eintraege;
    }

    public static void schreiben(Path datei, List<Eintrag> eintraege) throws IOException {
        StringBuilder sb = new StringBuilder(KOPF);
        eintraege.stream()
                .sorted((a, b) -> a.schluessel().compareTo(b.schluessel()))
                .forEach(e -> sb.append(e.schluessel()).append('\t')
                        .append(einzeilig(e.grund())).append('\t').append(e.seit()).append('\n'));
        Path eltern = datei.getParent();
        if (eltern != null) {
            Files.createDirectories(eltern);
        }
        Files.writeString(datei, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Der Grund ist der Text einer Forge-Ausnahme und kann Zeilenumbrueche und Tabulatoren tragen; beides
     * zerstoert das Format (ein Umbruch macht aus einem Eintrag zwei zu kurze Zeilen, die {@link #lesen}
     * verwirft, ein Tabulator verschiebt die Spalten). Folgen von Leerraum werden deshalb zu einem Leerzeichen.
     */
    private static String einzeilig(String grund) {
        return grund.replaceAll("\\s+", " ").trim();
    }

    /**
     * @return der Bestand plus jeden Befund, der noch nicht darin steht. Ein bestehender Eintrag behaelt
     *         Grund UND Datum - sonst waere jede Woche jedes Datum neu und die Liste erzaehlte nichts
     *         mehr darueber, seit wann eine Karte fehlt.
     */
    public static List<Eintrag> ergaenzen(List<Eintrag> bestand, CardDataCheck.Befund befund, String heute) {
        Map<String, Eintrag> nachSchluessel = new LinkedHashMap<>();
        bestand.forEach(e -> nachSchluessel.put(e.schluessel(), e));

        befund.nichtBaubar().forEach(p -> nachSchluessel.putIfAbsent(p.karte(),
                new Eintrag(p.karte(), p.grund(), heute)));
        befund.nichtAuffindbar().forEach(name -> nachSchluessel.putIfAbsent(name,
                new Eintrag(name, "Kartenskript von dieser Forge-Fassung nicht lesbar", heute)));
        befund.doppelteNamen().forEach(name -> nachSchluessel.putIfAbsent(name,
                new Eintrag(name, "Kartenname in mehr als einer Datei", heute)));
        befund.doppelteSetCodes().forEach(code -> nachSchluessel.putIfAbsent(code,
                new Eintrag(code, "Set-Code in mehr als einer Editionsdatei", heute)));

        return new ArrayList<>(nachSchluessel.values());
    }
}
