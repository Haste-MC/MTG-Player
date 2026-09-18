package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class ImageCacheTest {

    private static String key;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
        key = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0).getImageKey(false);
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
    void mindestabstandZwischenRequests(@TempDir Path dir) {
        ImageCache cache = new ImageCache(dir, url -> new byte[] {1}, 150);
        String key2 = Precons.load("Adaptive Enchantment [C18] [2018]").getCommanders().get(0).getImageKey(false);
        long t0 = System.currentTimeMillis();
        cache.get(key);
        cache.get(key2);
        assertTrue(System.currentTimeMillis() - t0 >= 150, "zweiter Download wartet den Mindestabstand ab");
    }
}
