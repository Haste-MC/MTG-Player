package mtgplayer.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sitzreihenfolge bei Teams: die Zugreihenfolge folgt den Sitzen, also wuerde ein Team, dessen Sitze
 * nebeneinander liegen, zweimal hintereinander ziehen. Diese Regel verteilt die Sitze abwechselnd.
 *
 * <p>Sitz 0 bleibt immer vorn: {@code HostedMatch.startMatch} sortiert den menschlichen Sitz stabil nach
 * vorn, und gegen diese Sortierung anzuarbeiten wuerde still scheitern - die Umsortierung muss sie also
 * vorwegnehmen.</p>
 *
 * <p>Bei ungleich grossen Teams (1v2) laesst sich ein Doppelzug nicht vermeiden; dann wird nur so
 * gleichmaessig verteilt, wie es geht.</p>
 *
 * <p>Ohne Teams ({@code null}, leer oder lauter Eintraege {@code < 0}) bleibt die Reihenfolge, wie sie ist.
 * {@code null} ergibt dabei {@code [0, 1]}: der einzige Aufrufer, der {@code null} uebergibt, ist eine
 * Zwei-Sitze-Partie ohne Teams.</p>
 */
public final class Seating {

    private Seating() { }

    /**
     * @param teams Team-Nummer je Sitz (Index = Sitz); {@code < 0} heisst kein Team
     * @return die neue Sitzreihenfolge als Indizes in {@code teams}
     */
    public static List<Integer> interleave(List<Integer> teams) {
        if (teams == null || teams.isEmpty()) {
            return identity(teams == null ? 2 : 0);
        }
        if (teams.stream().allMatch(t -> t == null || t < 0)) {
            return identity(teams.size());
        }
        // Je Team seine Sitze in gegebener Reihenfolge; das Team von Sitz 0 kommt zuerst dran.
        Map<Integer, List<Integer>> proTeam = new LinkedHashMap<>();
        proTeam.computeIfAbsent(teams.get(0), t -> new ArrayList<>());
        for (int i = 0; i < teams.size(); i++) {
            proTeam.computeIfAbsent(teams.get(i), t -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> schlangen = new ArrayList<>(proTeam.values());
        List<Integer> out = new ArrayList<>();
        // Reihum eine Schlange nach der anderen leeren: das verteilt die Teams so gleichmaessig wie
        // moeglich und laesst ein groesseres Team nur am Ende doppelt sitzen.
        while (out.size() < teams.size()) {
            boolean etwasGenommen = false;
            for (List<Integer> schlange : schlangen) {
                if (!schlange.isEmpty()) {
                    out.add(schlange.remove(0));
                    etwasGenommen = true;
                }
            }
            if (!etwasGenommen) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> identity(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return List.copyOf(out);
    }
}
