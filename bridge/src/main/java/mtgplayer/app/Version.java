package mtgplayer.app;

import mtgplayer.forge.ForgeBoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Die eigene Fassung (Spec 2026-09-26-app-paket-design.md §4/§6): zuerst {@code version.txt} im
 * App-Ordner (schreibt {@code scripts/package.sh}, Task 3), sonst {@code Implementation-Version} aus
 * dem Jar-Manifest (traegt der Bau in Task 7 ein), sonst {@value #DEV} - Kevins laufende
 * Entwicklungs-Bridge und jeder Worktree haben keins von beidem.
 *
 * <p>{@link #isNewer} behandelt {@value #DEV} bewusst wie jede andere nicht als Zahl lesbare Angabe:
 * es gibt dafuer keine sinnvolle Ordnung, also nie einen Update-Hinweis. Ohne das wuerde die eigene
 * Arbeitskopie sich staendig selbst als veraltet gegenueber jedem echten Release melden.</p>
 */
public final class Version {

    public static final String DEV = "dev";

    private Version() { }

    public static String current() {
        return current(appDir());
    }

    /** App-Ordner wie {@code mtgplayer.Main} ihn fuer {@code app/web} berechnet: der Elternordner von
     *  {@code mtgplayer.assets} (siehe {@code AppMode}/{@code package.sh} - assets/ und version.txt
     *  liegen dort nebeneinander auf oberster Ebene des Paketordners). */
    private static Path appDir() {
        return ForgeBoot.assetsDir().getParent();
    }

    /** Testbarer Kern von {@link #current()}: liest {@code version.txt} direkt aus einem uebergebenen
     *  Ordner, ohne ein echtes {@code assets}-Verzeichnis vortaeuschen zu muessen (siehe VersionTest). */
    static String current(Path appDir) {
        if (appDir != null) {
            try {
                String text = Files.readString(appDir.resolve("version.txt")).trim();
                if (!text.isEmpty()) return text;
            } catch (IOException keinPaket) {
                // kein version.txt (oder nicht lesbar) - weiter zum Manifest
            }
        }
        String manifest = Version.class.getPackage().getImplementationVersion();
        if (manifest != null && !manifest.isBlank()) return manifest.trim();
        return DEV;
    }

    /**
     * Ist {@code a} eine echt neuere Fassung als {@code b}? Vergleicht Punkt-Abschnitte als Zahlen,
     * nicht als Text - sonst waere "1.9.0" &gt; "1.10.0" (Ziffer '9' &gt; '1'). Fehlende Abschnitte
     * zaehlen als 0 ("1.2" == "1.2.0"). Jede nicht als reine Zahlenfolge lesbare Fassung ({@code null},
     * leer, {@value #DEV}, ein Tag mit "v"-Vorsilbe wie "v1.2") liefert {@code false} statt einer
     * Ausnahme - weder "unlesbar ist neuer" noch "man kann von unlesbar aus veraltet sein" ergibt
     * einen Sinn, und ein Murks-Wert darf nie einen Update-Hinweis ausloesen.
     */
    public static boolean isNewer(String a, String b) {
        int[] va = parse(a);
        int[] vb = parse(b);
        if (va == null || vb == null) return false;
        int len = Math.max(va.length, vb.length);
        for (int i = 0; i < len; i++) {
            int xa = i < va.length ? va[i] : 0;
            int xb = i < vb.length ? vb[i] : 0;
            if (xa != xb) return xa > xb;
        }
        return false;
    }

    /** {@code null}, wenn sich die Fassung nicht sauber in Ziffern-Abschnitte zerlegen laesst. */
    private static int[] parse(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        String[] teile = s.split("\\.", -1);
        int[] out = new int[teile.length];
        for (int i = 0; i < teile.length; i++) {
            if (!teile[i].matches("\\d+")) return null;
            try {
                out[i] = Integer.parseInt(teile[i]);
            } catch (NumberFormatException zuGross) {
                return null;
            }
        }
        return out;
    }
}
