package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Haelt die Regel von {@link MatchRecorder#hasTeams} als Tabelle fest, ohne {@code Game} und ohne
 * {@code ForgeBoot}: Team ist, wenn sich mindestens zwei Sitze eine Nummer teilen. Schwaechere Regeln
 * ("genau zwei Teams", "die ersten beiden Sitze sind gleich", "Nummern verschieden genug") bestuenden
 * die wenigen Engine-Faelle in {@code MatchRecorderTeamTest} ebenfalls - hier nicht.
 */
class MatchRecorderHasTeamsTest {

    @Test
    void geteilteNummerHeisstTeams() {
        assertTrue(MatchRecorder.hasTeams(List.of(1, 1, 2, 2)), "2v2");
        assertTrue(MatchRecorder.hasTeams(List.of(1, 2, 2)), "1v2: ein Zweier-Team genuegt");
        assertTrue(MatchRecorder.hasTeams(List.of(1, 1, 2, 2, 3, 3)), "drei Teams, nicht nur zwei");
        assertTrue(MatchRecorder.hasTeams(List.of(1, 2, 3, 3)), "das Paar steht hinten, nicht vorn");
    }

    @Test
    void lauterVerschiedeneNummernSindJederGegenJeden() {
        assertFalse(MatchRecorder.hasTeams(List.of(0, 1)), "so nummeriert Forge ein Duell selbst");
        assertFalse(MatchRecorder.hasTeams(List.of(0, 1, 2)), "und drei Sitze im Free-for-all");
        assertFalse(MatchRecorder.hasTeams(List.of(1, 2, 3)), "jeder allein im Team ist kein Team");
    }
}
