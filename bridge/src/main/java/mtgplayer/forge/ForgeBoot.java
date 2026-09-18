package mtgplayer.forge;

import forge.StaticData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** ~/.mtg-player/ – eigene Daten der Bridge und Forges Nutzerdaten darunter. */
    public static Path dataDir() {
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
        Path assets = assetsDir();
        if (!Files.isDirectory(assets.resolve("res").resolve("cardsfolder"))) {
            throw new IllegalStateException("assets/res/cardsfolder fehlt unter " + assets
                    + " – Symlink bridge/assets/res -> ../../forge/forge-gui/res vorhanden?"
                    + " Pfad ueberschreibbar mit -Dmtgplayer.assets=<dir>");
        }
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
            // M2-Minimum: sichtbar im Log statt stillschweigend verschluckt. Weiterleitung an den Browser kommt in M3.
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("[uncaught] " + t.getName());
                e.printStackTrace();
            });
        }
        initialized = true;
    }

    public static int cardCount() {
        return StaticData.instance().getCommonCards().getUniqueCards().size();
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
