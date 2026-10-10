package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Die Zugreihenfolge folgt der Sitzreihenfolge: sitzen beide eines Teams nebeneinander, zieht dieses
 *  Team zweimal hintereinander. Diese Regel verteilt die Sitze abwechselnd - und zwar fuer den Bench und
 *  fuer die echte Partie gleich, sonst misst der Bench etwas anderes, als gespielt wird. */
class SeatingTest {

    @Test
    void ohneTeamsBleibtDieReihenfolge() {
        assertEquals(List.of(0, 1, 2, 3), Seating.interleave(List.of(-1, -1, -1, -1)));
        assertEquals(List.of(0, 1), Seating.interleave(null));
    }

    @Test
    void zweiGegenZweiWechseltAb() {
        // Sitz 0 bleibt vorn (Forge sortiert den menschlichen Sitz dorthin), danach abwechselnd.
        assertEquals(List.of(0, 2, 1, 3), Seating.interleave(List.of(1, 1, 2, 2)));
    }

    @Test
    void bereitsAbwechselndBleibtUnveraendert() {
        assertEquals(List.of(0, 1, 2, 3), Seating.interleave(List.of(1, 2, 1, 2)));
    }

    @Test
    void dreiTeamsZuZweitReihumVerteilt() {
        List<Integer> teams = List.of(1, 1, 2, 2, 3, 3);
        List<Integer> order = Seating.interleave(teams);
        // Kein Team sitzt zweimal hintereinander.
        for (int i = 1; i < order.size(); i++) {
            int vorher = teams.get(order.get(i - 1));
            int jetzt = teams.get(order.get(i));
            org.junit.jupiter.api.Assertions.assertNotEquals(vorher, jetzt,
                    "Sitz " + i + " hat dasselbe Team wie sein Vorgaenger: " + order);
        }
        assertEquals(0, order.get(0), "Sitz 0 bleibt vorn");
    }

    @Test
    void ungleicheTeamsVerteilenSoGutEsGeht() {
        // 1v2: ein Doppelzug ist unvermeidbar, aber das grosse Team darf nicht komplett vorne sitzen.
        List<Integer> teams = List.of(1, 2, 2);
        List<Integer> order = Seating.interleave(teams);
        assertEquals(List.of(0, 1, 2), order);
    }

    @Test
    void jederSitzKommtGenauEinmalVor() {
        List<Integer> order = Seating.interleave(List.of(1, 1, 1, 2, 2, 2));
        assertEquals(6, order.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), order.stream().sorted().toList());
    }
}
