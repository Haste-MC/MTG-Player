package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Die Ansichtsschicht kennt kein Team: {@code PlayerView} traegt keines. Die Bridge haelt deshalb
 *  eine Kopie in Sitzreihenfolge und loest sie beim ersten Snapshot auf Sitz-Ids auf. */
class TeamMapTest {

    @Test
    void ohneTeamsLeereZuordnung() {
        assertTrue(WebGuiGame.teamsById(null, List.of()).isEmpty());
    }

    @Test
    void sitzReihenfolgeWirdAufIdsAbgebildet() {
        Map<Integer, Integer> m = WebGuiGame.teamsById(List.of(1, 1, 2, 2), List.of(7, 8, 9, 10));
        assertEquals(Map.of(7, 1, 8, 1, 9, 2, 10, 2), m);
    }

    @Test
    void mehrSitzeAlsNummernBleibtLeer() {
        assertTrue(WebGuiGame.teamsById(List.of(1, 2), List.of(7, 8, 9)).isEmpty(),
                "unpassende Laenge: lieber keine Teams zeigen als falsche");
    }
}
