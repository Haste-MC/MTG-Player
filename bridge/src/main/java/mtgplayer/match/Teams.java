package mtgplayer.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Teamnummern einer {@code startGame}-Nachricht, in Sitzreihenfolge (menschlicher Sitz zuerst, im
 * Zuschauer-Modus nur die KI-Sitze).
 *
 * <p>Gueltig ist nur: entweder traegt KEIN Sitz ein Team (Jeder gegen jeden wie bisher), oder ALLE
 * tragen eins und es sind mindestens zwei verschiedene. Ein halb gesetztes Feld waere sonst eine
 * Partie, in der ein Sitz keine Gegner hat - Forge beendet sie sofort mit
 * {@code AllOpposingTeamsLost}, und niemand wuesste warum.
 */
public final class Teams {

    /** Hoechste Teamnummer, die die Lobby anbietet; mehr Sitze als 6 gibt es nicht. */
    private static final int MAX = 6;

    private Teams() { }

    public static List<Integer> parse(JsonNode msg, boolean spectate, int opponents) {
        List<Integer> teams = new ArrayList<>();
        List<Boolean> gesetzt = new ArrayList<>();
        if (!spectate) {
            JsonNode human = msg.path("humanTeam");
            gesetzt.add(!human.isMissingNode() && !human.isNull());
            teams.add(human.asInt(-1));
        }
        int i = 0;
        for (JsonNode o : msg.path("opponents")) {
            JsonNode team = o.path("team");
            gesetzt.add(!team.isMissingNode() && !team.isNull());
            teams.add(team.asInt(-1));
            i++;
        }
        if (i != opponents) {
            throw new IllegalArgumentException("Teams: " + opponents + " Gegner erwartet, " + i + " gelesen");
        }
        if (gesetzt.stream().noneMatch(Boolean::booleanValue)) {
            return null;
        }
        if (gesetzt.stream().anyMatch(b -> !b)) {
            throw new IllegalArgumentException("Teams: entweder alle Sitze oder keiner");
        }
        for (int t : teams) {
            if (t < 1 || t > MAX) {
                throw new IllegalArgumentException("Teams: Nummer 1 bis " + MAX + ", nicht " + t);
            }
        }
        Set<Integer> verschieden = new HashSet<>(teams);
        if (verschieden.size() < 2) {
            throw new IllegalArgumentException("Teams: mindestens zwei verschiedene Teams");
        }
        return List.copyOf(teams);
    }
}
