package mtgplayer.app;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 4 (App-Paket): neuestes GitHub-Release abfragen - immer ueber eine eingesetzte Quelle
 *  (kein Test geht ins Netz, siehe {@link mtgplayer.decks.Edhrec} als Vorbild fuer dieses Muster). */
class UpdateCheckTest {

    private static final String LATEST_URL = "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest";
    private static final String SHA_URL = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/SHA256SUMS";
    private static final String ZIP_URL = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/MTG-Player-1.3.0-win.zip";
    private static final String HASH = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    private static Function<String, String> source(Map<String, String> antworten) {
        return url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        };
    }

    private static String releaseJson(String assetsBlock, String body) {
        return "{\"tag_name\":\"v1.3.0\",\"body\":" + jsonString(body) + ",\"assets\":[" + assetsBlock + "]}";
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private static String asset(String name, String url) {
        return "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url + "\"}";
    }

    @Test
    void liestTagZipUndPruefsummeAusDemAssetSha256Sums() {
        String assets = asset("MTG-Player-1.3.0-win.zip", ZIP_URL) + "," + asset("SHA256SUMS", SHA_URL);
        Map<String, String> antworten = Map.of(
                LATEST_URL, releaseJson(assets, "Release-Notizen"),
                SHA_URL, HASH + "  MTG-Player-1.3.0-win.zip\n");
        UpdateCheck check = new UpdateCheck(source(antworten));

        Optional<UpdateCheck.Release> r = check.latest();

        assertTrue(r.isPresent());
        assertEquals("1.3.0", r.get().tag(), "das fuehrende 'v' des Tags gehoert nicht in die Fassung");
        assertEquals(ZIP_URL, r.get().url());
        assertEquals(HASH, r.get().sha256());
    }

    @Test
    void liestPruefsummeAusDemReleaseTextWennKeinSha256SumsAssetDaIst() {
        String assets = asset("MTG-Player-1.3.0-win.zip", ZIP_URL);
        String body = "Aenderungen ... SHA256: " + HASH + " ... Ende";
        Map<String, String> antworten = Map.of(LATEST_URL, releaseJson(assets, body));
        UpdateCheck check = new UpdateCheck(source(antworten));

        Optional<UpdateCheck.Release> r = check.latest();

        assertTrue(r.isPresent());
        assertEquals(HASH, r.get().sha256());
    }

    @Test
    void fehlenderTagNameErgibtLeeresOptional() {
        Map<String, String> antworten = Map.of(LATEST_URL,
                "{\"assets\":[" + asset("MTG-Player-1.3.0-win.zip", ZIP_URL) + "]}");
        UpdateCheck check = new UpdateCheck(source(antworten));

        assertFalse(check.latest().isPresent());
    }

    @Test
    void fehlendesZipAssetErgibtLeeresOptional() {
        Map<String, String> antworten = Map.of(LATEST_URL,
                releaseJson(asset("SHA256SUMS", SHA_URL), ""));
        UpdateCheck check = new UpdateCheck(source(antworten));

        assertFalse(check.latest().isPresent());
    }

    @Test
    void fehlendePruefsummeErgibtLeeresOptional() {
        // Weder SHA256SUMS-Asset noch ein Hex-Block im Release-Text - eine Fassung ohne Pruefsumme
        // ist fuer Aufgabe 5 (Update anwenden) unbrauchbar, also zaehlt das wie jedes andere fehlende Feld.
        String assets = asset("MTG-Player-1.3.0-win.zip", ZIP_URL);
        Map<String, String> antworten = Map.of(LATEST_URL, releaseJson(assets, "keine Pruefsumme hier"));
        UpdateCheck check = new UpdateCheck(source(antworten));

        assertFalse(check.latest().isPresent());
    }

    @Test
    void kaputtesJsonErgibtLeeresOptional() {
        Map<String, String> antworten = Map.of(LATEST_URL, "das ist kein JSON {{{");
        UpdateCheck check = new UpdateCheck(source(antworten));

        assertFalse(check.latest().isPresent());
    }

    @Test
    void werfendeQuelleErgibtLeeresOptional() {
        UpdateCheck check = new UpdateCheck(url -> { throw new RuntimeException("kein Netz im Test"); });

        assertFalse(check.latest().isPresent());
    }

    @Test
    void scheiternDesZweitenAbrufsFuerDieSha256SumsFaelltAufDenReleaseTextZurueck() {
        String assets = asset("MTG-Player-1.3.0-win.zip", ZIP_URL) + "," + asset("SHA256SUMS", SHA_URL);
        String body = "SHA256: " + HASH;
        // SHA_URL bewusst nicht in den Antworten - source() wirft dafuer, latest() soll trotzdem
        // ueber den Release-Text an die Pruefsumme kommen statt komplett leer auszugehen.
        Map<String, String> antworten = Map.of(LATEST_URL, releaseJson(assets, body));
        UpdateCheck check = new UpdateCheck(source(antworten));

        Optional<UpdateCheck.Release> r = check.latest();

        assertTrue(r.isPresent());
        assertEquals(HASH, r.get().sha256());
    }
}
