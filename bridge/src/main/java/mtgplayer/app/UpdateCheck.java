package mtgplayer.app;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fragt das neueste GitHub-Release ab (Spec 2026-09-26-app-paket-design.md §5/§6). Wie
 * {@link mtgplayer.decks.Edhrec}: die Quelle ist eine einsetzbare Funktion (URL -&gt; Antworttext),
 * damit kein Test ins Netz geht. Jeder Fehlschlag - Netzfehler, kaputtes JSON, fehlender Tag/ZIP/
 * Pruefsumme, eine werfende Quelle - endet in {@link Optional#empty()} statt einer Ausnahme: ein
 * Ausfall bei GitHub darf das Spiel nie behindern.
 *
 * <p><b>Entscheidung zur Pruefsumme</b> (diese Aufgabe trifft sie fuer Aufgabe 7, den echten Bau):
 * primaer ein eigenes Release-Asset {@code SHA256SUMS} mit Zeilen im Format von {@code sha256sum}
 * ("&lt;hash&gt;  &lt;dateiname&gt;") - der Plan sieht in §4 Schritt 8 ausdruecklich vor, dass ZIP
 * UND Pruefsumme beide als Release-Anhang landen ("beides an das GitHub-Release haengen"), nicht als
 * Text. Zusaetzlich liest {@link #shaFromBody} denselben 64-stelligen Hex-Block auch aus dem
 * Release-Text - falls Aufgabe 7 sich doch dafuer entscheidet, oder als Rueckfall, wenn der zweite
 * Abruf (das Asset selbst) fehlschlaegt, muss hier nichts mehr geaendert werden.</p>
 */
public final class UpdateCheck {

    private static final String LATEST_URL = "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest";
    private static final Pattern HEX64 = Pattern.compile("\\b[0-9a-fA-F]{64}\\b");

    /** notes: der Release-Text ("body" in der GitHub-Antwort), leer wenn keiner da ist - Aufgabe 6
     *  zeigt ihn in der Lobby ("was ist neu"), siehe Spec §6. */
    public record Release(String tag, String url, String sha256, String notes) { }

    private final Function<String, String> source;

    /** @param source liefert den Antwort-Text zu einer URL (im Betrieb der HTTP-Abruf, im Test eine
     *                eingesetzte Funktion); wirft, wenn der Abruf scheitert. */
    public UpdateCheck(Function<String, String> source) {
        this.source = source;
    }

    public UpdateCheck() {
        this(UpdateCheck::fetch);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Selbe Bauart wie {@link mtgplayer.decks.Archidekt#standard()}/{@link mtgplayer.decks.Edhrec}:
     *  eigener User-Agent, 10 s Zeitgrenze (Spec §5) - eine Update-Pruefe soll den App-Start nicht
     *  spuerbar verzoegern und nie haengen bleiben. */
    private static String fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new RuntimeException("HTTP " + res.statusCode());
            }
            return res.body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Neuestes Release, oder leer bei jedem Fehlschlag. Der Aufrufer (Bridge) unterscheidet nicht
     *  "warum leer", nur "gibt es etwas Brauchbares" - siehe Klassenkommentar. */
    public Optional<Release> latest() {
        String body;
        try {
            body = source.apply(LATEST_URL);
        } catch (RuntimeException fehlgeschlagen) {
            return Optional.empty();
        }
        try {
            JsonNode root = Json.parse(body);
            String tag = tagOf(root);
            if (tag == null) return Optional.empty();

            String zipName = null;
            String zipUrl = null;
            String shaAssetUrl = null;
            for (JsonNode asset : root.path("assets")) {
                String name = asset.path("name").asText("");
                String url = asset.path("browser_download_url").asText(null);
                if (url == null || url.isBlank()) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (zipUrl == null && lower.endsWith(".zip")) {
                    zipName = name;
                    zipUrl = url;
                } else if (lower.contains("sha256")) {
                    shaAssetUrl = url;
                }
            }
            if (zipUrl == null) return Optional.empty();

            String notes = root.path("body").asText("");
            String sha256 = shaAssetUrl != null ? shaFromAsset(shaAssetUrl, zipName) : null;
            if (sha256 == null) sha256 = shaFromBody(notes);
            if (sha256 == null) return Optional.empty();

            return Optional.of(new Release(tag, zipUrl, sha256, notes));
        } catch (RuntimeException kaputteAntwort) {
            return Optional.empty();
        }
    }

    /** {@code tag_name} ohne fuehrendes "v" ({@code v1.2.0} -&gt; {@code 1.2.0}, Spec §4) - so vergleicht
     *  sich das Ergebnis direkt mit {@link Version#current()} ueber {@link Version#isNewer}. */
    private static String tagOf(JsonNode root) {
        String raw = root.path("tag_name").asText(null);
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        return trimmed.length() > 1 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                ? trimmed.substring(1) : trimmed;
    }

    /** Zweiter Abruf ueber dieselbe Quelle: die {@code SHA256SUMS}-Datei einlesen und die Zeile zum
     *  ZIP-Dateinamen suchen. Scheitert dieser zweite Abruf, faellt {@link #latest()} auf den
     *  Release-Text zurueck statt komplett leer auszugehen. */
    private String shaFromAsset(String assetUrl, String zipName) {
        String text;
        try {
            text = source.apply(assetUrl);
        } catch (RuntimeException fehlgeschlagen) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (zipName != null && !line.contains(zipName)) continue;
            Matcher m = HEX64.matcher(line);
            if (m.find()) return m.group().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Rueckfall, falls die Pruefsumme (noch) im Release-Text statt in einem eigenen Asset steht. */
    private static String shaFromBody(String text) {
        Matcher m = HEX64.matcher(text);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
    }
}
