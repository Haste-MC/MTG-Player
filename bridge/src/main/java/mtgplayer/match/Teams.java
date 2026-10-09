package mtgplayer.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Teamnummern einer {@code startGame}-Nachricht, in Sitzreihenfolge (menschlicher Sitz zuerst, im
 * Zuschauer-Modus nur die KI-Sitze).
 *
 * <p>Gueltig ist nur: entweder traegt KEIN Sitz ein Team (Jeder gegen jeden wie bisher), oder ALLE
 * tragen eins, es sind mindestens zwei verschiedene, und mindestens ein Team hat zwei oder mehr Sitze
 * (ein Team aus einem Sitz ist ein Jeder-gegen-jeden-Sitz; so sieht es auch
 * {@code MatchRecorder.hasTeams}). Ein halb gesetztes Feld waere sonst eine
 * Partie, in der ein Sitz keine Gegner hat - Forge beendet sie sofort mit
 * {@code AllOpposingTeamsLost}, und niemand wuesste warum.
 */
public final class Teams {

    /** Hoechste Teamnummer, die die Lobby anbietet; mehr Sitze als 6 gibt es nicht. */
    private static final int MAX = 6;

    private Teams() { }

    /** Draht-Wert fuer "kein Team"; entspricht dem Platzhalter, den die Lobby fuer Jeder-gegen-jeden sendet. */
    private static final int KEIN_TEAM = -1;

    /**
     * Liest die Teamnummern aller Sitze.
     *
     * @return {@code null}, wenn kein Sitz ein Team traegt; sonst die Nummern in Sitzreihenfolge
     * @throws IllegalArgumentException bei ungueltiger Belegung (Text siehe Meldung)
     */
    public static List<Integer> parse(JsonNode msg, boolean spectate, int opponents) {
        // null steht fuer "nicht gesetzt"; so bleibt 0 als echte, aber ungueltige Zahl erkennbar.
        List<Integer> teams = new ArrayList<>();
        if (!spectate) {
            teams.add(lesen(msg.path("humanTeam")));
        }
        int i = 0;
        for (JsonNode o : msg.path("opponents")) {
            teams.add(lesen(o.path("team")));
            i++;
        }
        if (teams.stream().allMatch(Objects::isNull)) {
            // Eine Nachricht ohne Teams ist Jeder-gegen-jeden; die Sitzzahl pruefen dann andere Stellen.
            return null;
        }
        // Erst hier ist klar, dass Teams gemeint sind - nur dann muss jeder Gegner eine Nummer haben.
        if (i != opponents) {
            throw new IllegalArgumentException("Teams: " + opponents + " Gegner erwartet, " + i + " gelesen");
        }
        if (teams.stream().anyMatch(Objects::isNull)) {
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
        // Ein Team aus einem einzigen Sitz ist ein Jeder-gegen-jeden-Sitz. Sind alle allein, ist es kein
        // Team-Spiel, und MatchRecorder.hasTeams hielte es im Datensatz fuer Jeder-gegen-jeden. Beide
        // Stellen muessen dasselbe unter "Teams" verstehen, also lehnt schon die Lobby es ab.
        if (verschieden.size() == teams.size()) {
            throw new IllegalArgumentException("Teams: mindestens ein Team braucht zwei Sitze");
        }
        return List.copyOf(teams);
    }

    /**
     * Liest die Teamnummer eines Sitzes. Fehlend, {@code null} und {@code -1} heissen alle "kein Team"
     * (ergibt {@code null}). Alles ausser einer ganzen Zahl wird abgelehnt statt umgedeutet: {@code asInt}
     * machte aus {@code "2"}, {@code true} und {@code 1.9} stillschweigend eine Teamnummer.
     */
    private static Integer lesen(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new IllegalArgumentException("Teams: Nummer 1 bis " + MAX + ", nicht " + n);
        }
        int wert = n.intValue();
        return wert == KEIN_TEAM ? null : wert;
    }
}
