package mtgplayer.images;

import mtgplayer.forge.ForgeBoot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-Cache für Kartenbilder. Downloads laufen serialisiert mit Mindestabstand (Scryfall-Etikette);
 * Fehlschläge werden 10 Minuten lang nicht wiederholt.
 */
public final class ImageCache {

    public interface Fetcher {
        /** @return Bilddaten oder null bei 404/Fehler */
        byte[] fetch(String url) throws IOException;
    }

    private static final long NEGATIVE_TTL_MS = 10 * 60 * 1000L;

    private final Path dir;
    private final Fetcher fetcher;
    private final long minGapMs;
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final Object downloadLock = new Object();
    private long lastRequestAt;

    public ImageCache(Path dir, Fetcher fetcher, long minGapMs) {
        this.dir = dir;
        this.fetcher = fetcher;
        this.minGapMs = minGapMs;
    }

    public static ImageCache standard() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Fetcher http = url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                    .header("Accept", "image/jpeg,image/*")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            try {
                HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                return res.statusCode() == 200 ? res.body() : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
        return new ImageCache(ForgeBoot.dataDir().resolve("cache").resolve("images"), http, 100);
    }

    public Optional<byte[]> get(String imageKey) {
        Optional<String> url = ImageKeys2Scryfall.url(imageKey);
        if (url.isEmpty()) return Optional.empty();
        Path file = dir.resolve(sha1(imageKey) + ".jpg");
        if (Files.isRegularFile(file)) {
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                // Datei kaputt → neu laden
            }
        }
        Long until = failedUntil.get(imageKey);
        if (until != null && until > System.currentTimeMillis()) return Optional.empty();

        synchronized (downloadLock) {
            Long untilLocked = failedUntil.get(imageKey); // ein anderer Thread hat es gerade als fehlgeschlagen markiert
            if (untilLocked != null && untilLocked > System.currentTimeMillis()) return Optional.empty();
            if (Files.isRegularFile(file)) { // ein anderer Thread hat es gerade geladen
                try { return Optional.of(Files.readAllBytes(file)); } catch (IOException ignored) { }
            }
            long wait = lastRequestAt + minGapMs - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Optional.empty(); }
            }
            lastRequestAt = System.currentTimeMillis();
            byte[] data = null;
            try {
                data = fetcher.fetch(url.get());
            } catch (IOException e) {
                System.err.println("[images] " + url.get() + ": " + e);
            }
            if (data == null || data.length == 0) {
                failedUntil.put(imageKey, System.currentTimeMillis() + NEGATIVE_TTL_MS);
                return Optional.empty();
            }
            try {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(sha1(imageKey) + ".part");
                Files.write(tmp, data);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[images] cache schreiben: " + e);
            }
            return Optional.of(data);
        }
    }

    static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
