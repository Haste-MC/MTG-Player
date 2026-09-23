package mtgplayer.forge;

import forge.StaticData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Einziger Ort, der Forge hochfährt. Reihenfolge ist zwingend:
 * 1. forge.profile.properties schreiben (Forge liest sie beim ersten Zugriff auf ForgeConstants)
 * 2. GuiBase.setInterface (ForgeConstants.ASSETS_DIR kommt daher – statisch, einmalig)
 * 3. FModel.initialize (lädt ~30k Kartenskripte, dauert 20–60 s)
 * Alles, was vor init() ForgeConstants/FModel berührt, scheitert in einem statischen Initialisierer
 * und vergiftet damit die JVM für den Rest des Laufs (NoClassDefFoundError bei späteren Tests).
 */
public final class ForgeBoot {

    private static boolean initialized;

    private ForgeBoot() { }

    /**
     * ~/.mtg-player/ – eigene Daten der Bridge und Forges Nutzerdaten darunter.
     * Ueberschreibbar mit {@code -Dmtgplayer.data=<dir>}, damit Tests/Bench-Laeufe (Log, {@code matches.json},
     * Bench-Ausgaben, Forge-Profil) nicht in die echte Ablage des Nutzers schreiben; siehe {@code bridge/pom.xml}.
     */
    public static Path dataDir() {
        String override = System.getProperty("mtgplayer.data");
        if (override != null) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".mtg-player");
    }

    /** bridge/assets/ – wird relativ zum Arbeitsverzeichnis (bridge/) oder per -Dmtgplayer.assets gefunden. */
    public static Path assetsDir() {
        String override = System.getProperty("mtgplayer.assets");
        Path p = override != null ? Paths.get(override) : Paths.get("assets");
        return p.toAbsolutePath().normalize();
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        Path realAssets = assetsDir();
        if (!Files.isDirectory(realAssets.resolve("res").resolve("cardsfolder"))) {
            throw new IllegalStateException("assets/res/cardsfolder fehlt unter " + realAssets
                    + " – Symlink bridge/assets/res -> ../../forge/forge-gui/res vorhanden?"
                    + " Pfad ueberschreibbar mit -Dmtgplayer.assets=<dir>");
        }
        // Mit -Dmtgplayer.data isoliert (Tests/Bench): eigenes assets/-Verzeichnis unter dataDir() statt in die
        // von Kevins laufender Bridge geteilte bridge/assets/forge.profile.properties zu schreiben.
        Path assets = System.getProperty("mtgplayer.data") != null ? isolatedAssetsDir(realAssets) : realAssets;
        writeProfile(assets);
        GuiBase.setInterface(new WebGuiBase(assets.toString() + "/"));
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            // menschlicher Sitz: Arena-Stil (Forge passt, wenn nichts spielbar ist) + spielbare Karten markieren
            prefs.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
            prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
            // Kartenwahl aus Bibliothek/Friedhof/Exil als Listen-Dialog statt Klick auf die Zone –
            // der Browser zeigt diese Zonen nicht als klickbare Panels (Tutor-Effekte hingen sonst)
            prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
            // headless: keine Musik/Sounds, kein Namensdialog beim Spielstart
            prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
            prefs.setPref(FPref.PLAYER_NAME, "Du");
            return null;
        });
        if (Thread.getDefaultUncaughtExceptionHandler() == null) {
            // Sichtbar im Terminal, in ~/.mtg-player/logs/bridge.log und (ueber CrashLog.setListener) im Browser.
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> CrashLog.report("uncaught in " + t.getName(), null, e));
        }
        initialized = true;
    }

    public static int cardCount() {
        return StaticData.instance().getCommonCards().getUniqueCards().size();
    }

    /**
     * Eigenes assets/-Verzeichnis unter {@code dataDir()} fuer isolierte Laeufe (siehe {@link #init()}):
     * ein Symlink {@code res} zeigt auf das echte {@code realAssets/res} (Kartenskripte etc., unveraendert
     * geteilt), aber {@code forge.profile.properties} landet dort statt in {@code realAssets}. Existiert der
     * Symlink schon (z. B. vom letzten Testlauf mit demselben {@code mtgplayer.data}), bleibt er stehen.
     */
    private static Path isolatedAssetsDir(Path realAssets) {
        Path isolated = dataDir().resolve("assets");
        Path link = isolated.resolve("res");
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(isolated);
                Files.createSymbolicLink(link, realAssets.resolve("res"));
            } catch (IOException e) {
                throw new IllegalStateException("kann isoliertes assets/res nicht als Symlink anlegen: " + link
                        + " -> " + realAssets.resolve("res") + " (mtgplayer.data=" + dataDir() + ")", e);
            }
        }
        return isolated;
    }

    private static void writeProfile(Path assets) {
        Path user = dataDir().resolve("forge");
        Path cache = dataDir().resolve("cache");
        String content = "userDir=" + user + "/\n" + "cacheDir=" + cache + "/\n";
        try {
            Files.createDirectories(user);
            Files.createDirectories(cache);
            Files.writeString(assets.resolve("forge.profile.properties"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("kann forge.profile.properties nicht schreiben", e);
        }
    }
}
