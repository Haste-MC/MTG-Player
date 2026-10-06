package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ImageCacheTest {

    private static String key;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
        key = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0).getImageKey(false);
    }

    private Path logDatei;

    @BeforeEach
    void logUmlenken(@TempDir Path logDir) {
        logDatei = logDir.resolve("logs").resolve("bridge.log");
        CrashLog.setFile(logDatei);
    }

    @AfterEach
    void logZuruecksetzen() {
        CrashLog.setFile(null);
    }

    private String log() throws IOException {
        return Files.isRegularFile(logDatei) ? Files.readString(logDatei) : "";
    }

    private static int vorkommen(String text, String teil) {
        int n = 0;
        for (int i = text.indexOf(teil); i >= 0; i = text.indexOf(teil, i + 1)) n++;
        return n;
    }

    @Test
    void vorschaukarteOhneSammlernummerFragtUeberDenNamenAn(@TempDir Path dir) throws Exception {
        String avacyn = StaticData.instance().getCommonCards().getCard("Avacyn, Angel of Horror").getImageKey(false);
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return new byte[] {7}; }, 0);
        assertArrayEquals(new byte[] {7}, cache.get(avacyn).get());
        assertEquals(List.of("https://api.scryfall.com/cards/named?exact=Avacyn%2C%20Angel%20of%20Horror&format=image&version=normal"), calls);
        assertEquals("", log(), "ein Bild, das ankommt, steht nicht im Log");
    }

    @Test
    void keyOhneUrlWirdMitGrundProtokolliertUndNurEinmal(@TempDir Path dir) throws Exception {
        ImageCache cache = new ImageCache(dir, url -> new byte[] {1}, 0);
        String unbekannt = "c:Gibtsnicht|XXX|1";
        for (int i = 0; i < 5; i++) assertTrue(cache.get(unbekannt).isEmpty());
        String log = log();
        assertTrue(log.contains("[images] kein Bild"), log);
        assertTrue(log.contains(unbekannt), log);
        assertTrue(log.contains("Forge kennt die Karte nicht"), log);
        assertEquals(1, vorkommen(log, unbekannt), "fuenf Abrufe, ein Eintrag");
        // eine andere Karte wird dagegen eigens gemeldet
        assertTrue(cache.get("t:goblin_r_1_1").isEmpty());
        assertTrue(log().contains("t:goblin_r_1_1"));
    }

    @Test
    void keinBildVomServerWirdMitKeyUndUrlProtokolliert(@TempDir Path dir) throws Exception {
        ImageCache cache = new ImageCache(dir, url -> null, 0);
        assertTrue(cache.get(key).isEmpty());
        String log = log();
        assertTrue(log.contains(key) && log.contains("https://api.scryfall.com/cards/tdc/"), log);
        assertEquals(1, vorkommen(log, key));
    }

    @Test
    void transportfehlerWirdMitKeyProtokolliert(@TempDir Path dir) throws Exception {
        ImageCache cache = new ImageCache(dir, url -> { throw new IOException("Timeout"); }, 0);
        assertTrue(cache.get(key).isEmpty());
        String log = log();
        assertTrue(log.contains(key) && log.contains("Timeout"), log);
    }

    @Test
    void ladeEinmalDannAusDemCache(@TempDir Path dir) throws Exception {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return new byte[] {1, 2, 3}; }, 0);
        assertArrayEquals(new byte[] {1, 2, 3}, cache.get(key).get());
        assertArrayEquals(new byte[] {1, 2, 3}, cache.get(key).get());
        assertEquals(1, calls.size(), "zweiter Zugriff kommt aus dem Cache");
        assertTrue(calls.get(0).startsWith("https://api.scryfall.com/cards/tdc/"));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".jpg")).count());
        }
    }

    @Test
    void fehlschlagWirdNegativGecacht(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return null; }, 0);
        assertTrue(cache.get(key).isEmpty());
        assertTrue(cache.get(key).isEmpty());
        assertEquals(1, calls.size(), "404 wird nicht sofort erneut angefragt");
    }

    @Test
    void tokenOhneUrlFragtNichtAn(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return new byte[] {1}; }, 0);
        assertTrue(cache.get("t:goblin_r_1_1").isEmpty());
        assertEquals(0, calls.size());
    }

    @Test
    void gleichzeitigeFehlschlaegeFragenNurEinmalAn(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCache cache = new ImageCache(dir, url -> { calls.incrementAndGet(); return null; }, 0);
        int n = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Optional<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(key);
                }));
            }
            start.countDown();
            for (Future<Optional<byte[]>> f : futures) {
                assertTrue(f.get(5, TimeUnit.SECONDS).isEmpty());
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, calls.get(), "gleichzeitige Anfragen auf denselben Key fragen den Fetcher nur einmal an");
    }

    @Test
    void mindestabstandZwischenRequests(@TempDir Path dir) {
        ImageCache cache = new ImageCache(dir, url -> new byte[] {1}, 150);
        String key2 = Precons.load("Adaptive Enchantment [C18] [2018]").getCommanders().get(0).getImageKey(false);
        long t0 = System.currentTimeMillis();
        cache.get(key);
        cache.get(key2);
        assertTrue(System.currentTimeMillis() - t0 >= 150, "zweiter Download wartet den Mindestabstand ab");
    }
}
