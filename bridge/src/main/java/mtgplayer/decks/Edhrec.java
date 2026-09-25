package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** EDHREC als Quelle fuer Kartenvorschlaege (Spec 2026-09-25-kartenvorschlaege §3). Nach aussen geht nur
 *  der Commandername - keine Deckliste, keine Partiedaten. Jeder Fehlschlag endet in einem leeren
 *  Optional; der Aufrufer faellt dann auf Forges Kartendatenbank zurueck. */
public final class Edhrec {

    /** Wie lange ein zwischengespeicherter Stand ohne neuen Abruf benutzt wird. */
    public static final Duration MAX_AGE = Duration.ofDays(7);
    private static final String BASE = "https://json.edhrec.com/pages/commanders/";
    private static final String HEADER_GAME_CHANGERS = "Game Changers";

    /** share: Anteil der Decks mit dieser Karte an allen erfassten Decks (0..1). */
    public record Card(String name, double share, boolean gameChanger) { }

    public record Page(String slug, List<Card> cards, Instant fetched) {
        /** kleingeschriebener Name -> Karte, fuer den schnellen Zugriff beim Abgleich mit einem Deck. */
        public Map<String, Card> byName() {
            Map<String, Card> out = new LinkedHashMap<>();
            for (Card c : cards) {
                out.put(c.name().toLowerCase(Locale.ROOT), c);
            }
            return out;
        }
    }

    private final Function<String, String> source;
    private final Path cacheDir;

    /** @param source liefert den Antwort-Text zu einer URL (im Betrieb der HTTP-Abruf, im Test eine
     *                eingesetzte Funktion); wirft, wenn der Abruf scheitert */
    public Edhrec(Function<String, String> source, Path cacheDir) {
        this.source = source;
        this.cacheDir = cacheDir;
    }

    public Edhrec() {
        this(Edhrec::fetch, ForgeBoot.dataDir().resolve("edhrec"));
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Selbe Werte wie {@link Archidekt#standard()}: 10 s Verbindung, 20 s Antwort, eigener User-Agent. */
    private static String fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build();
        try {
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // wirft absichtlich, damit page() auf einen (ggf. abgelaufenen) Zwischenstand zurueckfaellt
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

    /** EDHREC-Slug fuer einen (Partner-)Commander. Bei mehreren Namen in alphabetischer Reihenfolge -
     *  EDHREC selbst fuehrt Partner so, unabhaengig davon, wer im Deck an erster Stelle steht.
     *  {@code null}-Eintraege und leere Namen werden uebersprungen statt zu werfen (Aufrufer wie
     *  {@link #page} bekommen sonst nie ihr leeres {@code Optional}, sondern eine Ausnahme). */
    public static String slug(List<String> commanderNames) {
        List<String> names = new ArrayList<>();
        for (String n : commanderNames) {
            if (n != null && !n.isBlank()) names.add(n);
        }
        if (names.isEmpty()) return "";
        names.sort(String::compareToIgnoreCase);
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            // doppelseitige Karten laufen bei EDHREC unter ihrer Vorderseite
            int slash = name.indexOf("//");
            String front = (slash >= 0 ? name.substring(0, slash) : name).trim();
            // Apostroph ist der einzige Sonderfall, der NICHT zum Trennzeichen wird - sonst waere jeder
            // Commander mit Apostroph im Namen (Yuriko, K'rrik, ...) dauerhaft ohne Vorschlaege, weil der
            // EDHREC-Slug den Apostroph ersatzlos streicht statt ihn durch "-" zu ersetzen.
            String part = front.toLowerCase(Locale.ROOT)
                    .replaceAll("['’]", "")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (!part.isEmpty()) parts.add(part);
        }
        return String.join("-", parts);
    }

    /** Reine Uebersetzung der EDHREC-Antwort; wirft bei kaputtem JSON (der Aufrufer in {@link #page}
     *  faengt das ab und faellt auf einen alten Stand zurueck). */
    public static Page parse(String slug, String json, Instant fetched) {
        JsonNode root = Json.parse(json);
        Map<String, Card> byName = new LinkedHashMap<>();
        for (JsonNode list : root.path("container").path("json_dict").path("cardlists")) {
            boolean gameChangers = HEADER_GAME_CHANGERS.equals(list.path("header").asText(""));
            for (JsonNode cv : list.path("cardviews")) {
                String name = cv.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                if (!cv.hasNonNull("num_decks") || !cv.hasNonNull("potential_decks")) continue;
                double numDecks = cv.path("num_decks").asDouble();
                double potentialDecks = cv.path("potential_decks").asDouble();
                double share = potentialDecks > 0 ? numDecks / potentialDecks : 0;
                String key = name.toLowerCase(Locale.ROOT);
                Card existing = byName.get(key);
                boolean gameChanger = gameChangers || (existing != null && existing.gameChanger());
                if (existing == null || share > existing.share()) {
                    byName.put(key, new Card(name, share, gameChanger));
                } else if (gameChanger != existing.gameChanger()) {
                    byName.put(key, new Card(existing.name(), existing.share(), gameChanger));
                }
            }
        }
        return new Page(slug, new ArrayList<>(byName.values()), fetched);
    }

    /** Zwischenspeicher, Abruf, Rueckfall auf abgelaufenen Stand (Spec §3): ein alter Stand ist besser
     *  als gar keiner, wenn EDHREC gerade nicht erreichbar ist. */
    public Optional<Page> page(List<String> commanderNames) {
        String slug = slug(commanderNames);
        if (slug.isEmpty()) return Optional.empty();
        Path file = cacheDir.resolve(slug + ".json");
        if (Files.isRegularFile(file)) {
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                if (Duration.between(modified.toInstant(), Instant.now()).compareTo(MAX_AGE) < 0) {
                    return Optional.of(parse(slug, Files.readString(file), modified.toInstant()));
                }
            } catch (IOException | RuntimeException ignore) {
                // kaputte Datei: weiter zum Abruf, notfalls unten noch ein zweiter Versuch mit dem alten Stand
            }
        }
        try {
            String json = source.apply(BASE + slug + ".json");
            Instant fetched = Instant.now();
            Page page = parse(slug, json, fetched);
            write(file, json, fetched);
            return Optional.of(page);
        } catch (IOException | RuntimeException e) {
            // Abruf gescheitert oder Antwort unlesbar: falls ein (ggf. abgelaufener) Stand auf der
            // Platte liegt, den benutzen statt den Aufrufer leer ausgehen zu lassen.
            if (Files.isRegularFile(file)) {
                try {
                    FileTime modified = Files.getLastModifiedTime(file);
                    return Optional.of(parse(slug, Files.readString(file), modified.toInstant()));
                } catch (IOException | RuntimeException ignore2) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
    }

    /** Erst in eine eindeutige Temp-Datei schreiben, dann {@code ATOMIC_MOVE} - wie {@link
     *  mtgplayer.stats.MatchStore}, damit ein Absturz waehrend des Schreibens nie eine halbe Datei
     *  hinterlaesst. */
    private static void write(Path file, String json, Instant fetched) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "edhrec", ".tmp");
        try {
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.setLastModifiedTime(file, FileTime.from(fetched));
    }
}
