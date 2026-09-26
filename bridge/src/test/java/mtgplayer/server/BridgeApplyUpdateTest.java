package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.app.UpdateApply;
import mtgplayer.app.UpdateCheck;
import mtgplayer.app.Version;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.decks.Edhrec;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.CardStore;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aufgabe 5 (App-Paket), Review-Nachtrag: der Protokollweg von {@code applyUpdate}/{@code updateState}
 * ueber eine echte Bridge - OHNE echten Windows-Prozessstart und OHNE die Test-JVM zu beenden. Bridge
 * bekommt dafuer drei eingesetzte Naehte (siehe {@code Bridge}'s 12-Parameter-Testkonstruktor):
 * {@link UpdateApply} (eigene Download-Quelle, kein Netz), einen eingesetzten App-Ordner (statt
 * {@code ForgeBoot.assetsDir().getParent()}), und {@code Bridge.UpdateLauncher} (eine Attrappe statt
 * echtem {@code ProcessBuilder}/{@code System.exit} - genau der Schritt, der sonst einen fremden Ordner
 * bewegen bzw. die eigene JVM beenden wuerde).
 *
 * <p>Deckt drei Luecken aus dem Review ab: Ablehnung ohne bekanntes Release, die appDir-Wache ueber das
 * echte Protokoll, und dass ein erfolgreicher Lauf wirklich ueber die eingesetzte Naht startet statt
 * direkt ProcessBuilder/System.exit zu rufen.</p>
 */
class BridgeApplyUpdateTest {

    private static final int PORT = 18091;
    private static final String ZIP_NAME = "MTG-Player-1.3.0-win.zip";
    private static final String ZIP_URL = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/" + ZIP_NAME;
    private static final String SHA_URL = "https://example.invalid/SHA256SUMS";

    private Bridge bridge;
    private WebSocketClient client;
    private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @TempDir
    Path tmp;

    @BeforeAll
    static void initForge() {
        // Wie jeder andere Bridge*Test hier (siehe BridgeVersionTest): ohne ForgeBoot.init() scheitert
        // Forges Localizer beim ersten Zugriff auf PhaseType mit einem ExceptionInInitializerError, der
        // die Klasse fuer den Rest des JVM-Forks vergiftet.
        ForgeBoot.init();
    }

    private static byte[] zip(String topDir, String... nameAndContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                zos.putNextEntry(new ZipEntry(topDir + "/" + nameAndContent[i]));
                zos.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String releaseJsonMitShaAsset(String tag, String zipUrl, String zipName, String notes) {
        return "{\"tag_name\":\"" + tag + "\",\"body\":\"" + notes + "\",\"assets\":["
                + "{\"name\":\"" + zipName + "\",\"browser_download_url\":\"" + zipUrl + "\"},"
                + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"" + SHA_URL + "\"}]}";
    }

    private void start(UpdateCheck updateCheck, String currentVersion, UpdateApply updateApply,
                        Bridge.UpdateLauncher launcher, Path appDirForUpdate) throws Exception {
        bridge = new Bridge(PORT, new DeckStore(tmp.resolve("decks")), Archidekt.standard(),
                new MatchStore(tmp.resolve("matches.json")), new SubprocessGameRunner(),
                new Edhrec(url -> { throw new RuntimeException("kein Edhrec-Test hier"); }, tmp.resolve("edhrec")),
                CardStore.standard(), updateCheck, () -> currentVersion,
                updateApply, launcher, () -> appDirForUpdate);
        bridge.start();
        client = new WebSocketClient(new URI("ws://127.0.0.1:" + PORT)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { inbox.add(Json.parse(m)); }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { ex.printStackTrace(); }
        };
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "WebSocket-Verbindung");
    }

    @AfterEach
    void stop() throws Exception {
        client.closeBlocking();
        bridge.stop();
    }

    /** Wie in BridgeVersionTest: unerhebliche Zwischennachrichten werden ueberlesen, bis die gesuchte
     *  Art ankommt oder die Zeit ablaeuft. */
    private JsonNode await(String type, int seconds) throws InterruptedException {
        long ende = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < ende) {
            JsonNode n = inbox.poll(Math.max(1, ende - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if (type.equals(n.path("type").asText())) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void applyUpdateOhneBekanntesReleaseLiefertFehlerOhneLauncherAufruf() throws Exception {
        List<Path> gestartet = new ArrayList<>();
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            throw new RuntimeException("kein Aufruf erwartet - Version.DEV fragt gar nicht erst an");
        });
        // Version.DEV: checkVersion() fragt gar nicht erst an (siehe Bridge) - versionMsg bleibt null.
        start(updateCheck, Version.DEV, null, gestartet::add, tmp.resolve("wird-nie-gebraucht"));

        await("lobby", 5);
        client.send("{\"type\":\"applyUpdate\"}");

        JsonNode err = await("error", 5);
        assertTrue(err.get("text").asText().contains("keine neuere Fassung"));
        assertTrue(gestartet.isEmpty(), "ohne bekanntes Update darf der Launcher nie aufgerufen werden");
        assertFalse(inbox.stream().anyMatch(n -> "updateState".equals(n.path("type").asText())),
                "ohne bekanntes Update darf gar kein updateState kommen");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void appDirWacheLehntUeberDasEchteProtokollAbBevorGeladenWird() throws Exception {
        Path appDirOhneVersionTxt = tmp.resolve("kein-app-ordner");
        Files.createDirectories(appDirOhneVersionTxt); // absichtlich KEIN version.txt darin
        Map<String, String> antworten = Map.of(
                "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest",
                releaseJsonMitShaAsset("v1.3.0", ZIP_URL, ZIP_NAME, "Neu"),
                SHA_URL, "a".repeat(64) + "  " + ZIP_NAME + "\n");
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        });
        List<Path> gestartet = new ArrayList<>();
        // Die echte UpdateApply mit einer Quelle, die wirft, wenn sie je aufgerufen wuerde - die
        // appDir-Wache in UpdateApply.run muss VOR jedem Download greifen.
        UpdateApply realUpdateApply = new UpdateApply(
                url -> { throw new AssertionError("Download darf die appDir-Wache nie ueberleben"); },
                tmp.resolve("temp"));
        start(updateCheck, "1.2.0", realUpdateApply, gestartet::add, appDirOhneVersionTxt);

        JsonNode version = await("version", 20);
        assertEquals("1.3.0", version.get("latest").asText());

        client.send("{\"type\":\"applyUpdate\"}");
        JsonNode state = await("updateState", 10);
        assertEquals("fehler", state.get("state").asText());
        assertTrue(state.get("text").asText().contains("version.txt"),
                "der Grund muss die appDir-Wache erkennen lassen");
        assertTrue(gestartet.isEmpty(), "die appDir-Wache muss vor jedem Skriptstart greifen");
    }

    /** Ein echter App-Ordner mit allen vier Merkmalen aus Spec §2 - dieselbe Wache wie in
     *  UpdateApplyTest, jetzt fuer den Protokollweg (2. Review-Nachtrag: eine blosse version.txt
     *  reicht der Wache nicht mehr). */
    private static Path echterAppDir(Path dir, String eigeneVersion) throws IOException {
        Files.createDirectories(dir.resolve("runtime"));
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("version.txt"), eigeneVersion);
        Files.writeString(dir.resolve("MTG-Player.exe"), "alter-starter");
        return dir;
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void erfolgreichesUpdateLaeuftUeberDieEingesetzteLauncherNahtStattEchtemProzessOderExit() throws Exception {
        byte[] content = zip("MTG-Player", "version.txt", "1.3.0", "MTG-Player.exe", "starter-binaer",
                "runtime/release", "java-laufzeit", "app/bridge.jar", "hallo-welt");
        String hash = sha256Hex(content);
        Path appDir = echterAppDir(tmp.resolve("App"), "1.2.0");

        Map<String, String> antworten = Map.of(
                "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest",
                releaseJsonMitShaAsset("v1.3.0", ZIP_URL, ZIP_NAME, "Neu"),
                SHA_URL, hash + "  " + ZIP_NAME + "\n");
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        });
        UpdateApply realUpdateApply = new UpdateApply(url -> {
            assertEquals(ZIP_URL, url);
            return content;
        }, tmp.resolve("temp"));
        List<Path> gestartet = new ArrayList<>();
        // Faengt GENAU den Schritt ab, der sonst einen fremden Ordner bewegen bzw. die Test-JVM
        // beenden wuerde (Review-Befund) - kein echter Prozess, kein System.exit.
        Bridge.UpdateLauncher fakeLauncher = gestartet::add;
        start(updateCheck, "1.2.0", realUpdateApply, fakeLauncher, appDir);

        await("version", 20);
        client.send("{\"type\":\"applyUpdate\"}");

        assertEquals("laden", await("updateState", 10).get("state").asText());
        assertEquals("pruefen", await("updateState", 10).get("state").asText());
        assertEquals("entpacken", await("updateState", 10).get("state").asText());
        assertEquals("neustart", await("updateState", 10).get("state").asText());

        // launcher.launch(...) laeuft im selben Hintergrund-Task NACH der "neustart"-Nachricht -
        // kurz nachwarten, bis er wirklich aufgerufen wurde.
        long ende = System.currentTimeMillis() + 10_000;
        while (gestartet.isEmpty() && System.currentTimeMillis() < ende) {
            Thread.sleep(20);
        }
        assertEquals(1, gestartet.size(), "die eingesetzte Naht muss genau einmal aufgerufen werden");
        assertTrue(Files.isRegularFile(gestartet.get(0)), "das uebergebene Skript muss existieren");
        assertEquals("update.cmd", gestartet.get(0).getFileName().toString());
        // Da die Attrappe weder einen Prozess startet noch System.exit ruft, laeuft die Bridge (und
        // damit diese Verbindung) unveraendert weiter - waere die echte Umsetzung gelaufen, haette sie
        // die gesamte Test-JVM beendet und dieser Code wuerde nie erreicht.
        assertTrue(client.isOpen());
    }

    /**
     * 4. Review-Nachtrag, Blocker 8 (kritisch): scheitert der Start des Tauschskripts (die eingesetzte
     * {@link Bridge.UpdateLauncher} wirft), MUSS ein "updateState"/"fehler" hinterher kommen - sonst
     * bleibt die Leiste beim zuletzt gemeldeten Zustand "neustart" haengen ("Update fertig, die App
     * startet gleich neu ...", siehe web/src/update.ts) und zeigt dem Nutzer nie, dass es tatsaechlich
     * schiefging. Die reine {@code ErrorMsg} (bereits vorher vorhanden) reicht nicht - die Lobby zeigt
     * diese Leiste nur ueber "updateState".
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void scheiterndeLauncherNahtMeldetUpdateStateFehlerStattBeiNeustartHaengenZuBleiben() throws Exception {
        byte[] content = zip("MTG-Player", "version.txt", "1.3.0", "MTG-Player.exe", "starter-binaer",
                "runtime/release", "java-laufzeit", "app/bridge.jar", "hallo-welt");
        String hash = sha256Hex(content);
        Path appDir = echterAppDir(tmp.resolve("App"), "1.2.0");

        Map<String, String> antworten = Map.of(
                "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest",
                releaseJsonMitShaAsset("v1.3.0", ZIP_URL, ZIP_NAME, "Neu"),
                SHA_URL, hash + "  " + ZIP_NAME + "\n");
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        });
        UpdateApply realUpdateApply = new UpdateApply(url -> {
            assertEquals(ZIP_URL, url);
            return content;
        }, tmp.resolve("temp"));
        Bridge.UpdateLauncher werfenderLauncher = script -> {
            throw new IOException("CreateProcess error=193, %1 ist keine gueltige Win32-Anwendung");
        };
        start(updateCheck, "1.2.0", realUpdateApply, werfenderLauncher, appDir);

        await("version", 20);
        client.send("{\"type\":\"applyUpdate\"}");

        assertEquals("laden", await("updateState", 10).get("state").asText());
        assertEquals("pruefen", await("updateState", 10).get("state").asText());
        assertEquals("entpacken", await("updateState", 10).get("state").asText());
        assertEquals("neustart", await("updateState", 10).get("state").asText());
        JsonNode fehler = await("updateState", 10);
        assertEquals("fehler", fehler.get("state").asText(),
                "nach einem gescheiterten Skriptstart muss noch ein updateState/fehler kommen, sonst bleibt die Leiste bei 'neustart' haengen");
        assertTrue(fehler.get("text").asText().contains("193"), "der Grund aus der IOException darf nicht verloren gehen");
    }

    /**
     * 2. Review-Nachtrag, Befund 5: {@code applyUpdate} braucht dieselbe Wiedereintrittssperre wie
     * {@code archidektImport} - zwei Klicks auf "Aktualisieren" duerfen nicht zwei Downloads und zwei
     * Tauschskripte gleichzeitig anstossen. Die eingesetzte Quelle blockiert auf einem Latch, GENAU
     * NACHDEM die erste "laden"-Meldung raus ist - das garantiert (statt auf Timing zu hoffen), dass
     * der zweite Versuch wirklich waehrend des ersten Laufs ankommt.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zweiterApplyUpdateWaehrendDesErstenLaeuftLiefertFehlerStattZweitenDownload() throws Exception {
        byte[] content = zip("MTG-Player", "version.txt", "1.3.0", "MTG-Player.exe", "starter-binaer",
                "runtime/release", "java-laufzeit", "app/bridge.jar", "hallo-welt");
        String hash = sha256Hex(content);
        Path appDir = echterAppDir(tmp.resolve("App"), "1.2.0");

        Map<String, String> antworten = Map.of(
                "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest",
                releaseJsonMitShaAsset("v1.3.0", ZIP_URL, ZIP_NAME, "Neu"),
                SHA_URL, hash + "  " + ZIP_NAME + "\n");
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        });
        java.util.concurrent.CountDownLatch quelleErreicht = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch weiterlaufen = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger downloadAufrufe = new java.util.concurrent.atomic.AtomicInteger();
        UpdateApply blockierendeUpdateApply = new UpdateApply(url -> {
            downloadAufrufe.incrementAndGet();
            quelleErreicht.countDown();
            try {
                assertTrue(weiterlaufen.await(10, TimeUnit.SECONDS), "Test haette laengst freigegeben");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return content;
        }, tmp.resolve("temp"));
        List<Path> gestartet = new ArrayList<>();
        start(updateCheck, "1.2.0", blockierendeUpdateApply, gestartet::add, appDir);

        await("version", 20);
        client.send("{\"type\":\"applyUpdate\"}");
        assertEquals("laden", await("updateState", 10).get("state").asText());
        assertTrue(quelleErreicht.await(10, TimeUnit.SECONDS), "der erste Lauf muss die Download-Quelle erreicht haben");

        // Zweiter Versuch, waehrend der erste nachweislich noch in der Download-Quelle haengt.
        client.send("{\"type\":\"applyUpdate\"}");
        JsonNode err = await("error", 5);
        assertTrue(err.get("text").asText().contains("laeuft schon"));

        // Freigeben und den ersten Lauf regulaer zu Ende laufen lassen - genau EIN Download insgesamt.
        weiterlaufen.countDown();
        assertEquals("pruefen", await("updateState", 10).get("state").asText());
        assertEquals("entpacken", await("updateState", 10).get("state").asText());
        assertEquals("neustart", await("updateState", 10).get("state").asText());
        assertEquals(1, downloadAufrufe.get(), "der zweite Versuch darf nie bis zur Download-Quelle kommen");
    }
}
