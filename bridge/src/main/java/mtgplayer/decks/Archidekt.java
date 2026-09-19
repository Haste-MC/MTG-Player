package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archidekt-Deck (URL oder ID) → Textliste für {@link DeckImport}. Die JSON-Übersetzung ist rein;
 * der Fetcher ist austauschbar (kein Netz in Tests).
 */
public final class Archidekt {

    public interface Fetcher {
        /** @return Antwort-Body bei HTTP 200; sonst IOException mit Status/Grund */
        String get(String url) throws IOException;
    }

    public record Result(String name, String text) { }

    private static final Pattern ID_IN_URL = Pattern.compile("archidekt\\.com/(?:api/)?decks/(\\d+)");
    private static final Pattern BARE_ID = Pattern.compile("^\\d+$");

    private final Fetcher fetcher;

    public Archidekt(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    public static Archidekt standard() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new Archidekt(url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("unterbrochen", e);
            }
        });
    }

    public static long parseDeckId(String urlOrId) {
        String s = urlOrId == null ? "" : urlOrId.trim();
        if (BARE_ID.matcher(s).matches()) {
            return Long.parseLong(s);
        }
        Matcher m = ID_IN_URL.matcher(s);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalArgumentException("Keine Archidekt-Deck-URL: " + urlOrId);
    }

    public Result fetch(String urlOrId) {
        long id = parseDeckId(urlOrId);
        String url = "https://archidekt.com/api/decks/" + id + "/";
        String body;
        try {
            body = fetcher.get(url);
        } catch (IOException e) {
            throw new IllegalArgumentException("Archidekt: " + e.getMessage(), e);
        }
        try {
            return toTextList(Json.parse(body));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Archidekt: Antwort nicht lesbar (" + e.getMessage() + ")", e);
        }
    }

    /** Reine Übersetzung der API-Antwort. Regeln siehe Plan (includedInDeck, isPremier, Sideboard). */
    public static Result toTextList(JsonNode deck) {
        Set<String> excluded = new HashSet<>();
        Set<String> commanderCats = new HashSet<>();
        for (JsonNode cat : deck.path("categories")) {
            String name = cat.path("name").asText("");
            if (!cat.path("includedInDeck").asBoolean(true)) excluded.add(name);
            if (cat.path("isPremier").asBoolean(false)) commanderCats.add(name);
        }
        List<String> commander = new ArrayList<>();
        List<String> main = new ArrayList<>();
        List<String> side = new ArrayList<>();
        for (JsonNode c : deck.path("cards")) {
            List<String> cats = new ArrayList<>();
            for (JsonNode k : c.path("categories")) cats.add(k.asText());
            if (!cats.isEmpty() && excluded.contains(cats.get(0))) continue;
            String line = line(c);
            if (line == null) continue;
            if (cats.stream().anyMatch(commanderCats::contains)) commander.add(line);
            else if (cats.contains("Sideboard")) side.add(line);
            else main.add(line);
        }
        StringBuilder sb = new StringBuilder();
        if (!commander.isEmpty()) { sb.append("Commander\n"); commander.forEach(l -> sb.append(l).append('\n')); }
        sb.append("Main\n"); main.forEach(l -> sb.append(l).append('\n'));
        if (!side.isEmpty()) { sb.append("Sideboard\n"); side.forEach(l -> sb.append(l).append('\n')); }
        return new Result(deck.path("name").asText("Archidekt"), sb.toString());
    }

    private static String line(JsonNode c) {
        int qty = c.path("quantity").asInt(1);
        String name = c.path("card").path("oracleCard").path("name").asText(null);
        if (name == null || name.isBlank()) return null;
        String set = c.path("card").path("edition").path("editioncode").asText("");
        String num = c.path("card").path("collectorNumber").asText("");
        if (set.isBlank() || num.isBlank() || "null".equals(num)) {
            return qty + " " + name;
        }
        return qty + " " + name + " (" + set.toLowerCase() + ") " + num;
    }
}
