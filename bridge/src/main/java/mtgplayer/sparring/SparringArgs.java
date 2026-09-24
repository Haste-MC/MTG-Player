package mtgplayer.sparring;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.ai.AiConfig;

/**
 * Parameter eines Sparring-Laufs (Spec §3): Deck {@code deck} spielt {@code games} 1vs1-Partien gegen
 * zufaellige Gegner aus seinem Bracket, alle Sitze mit der KI-Einstellung {@code ai} und
 * {@code timeout} Sekunden Bedenkzeit je Entscheidung; {@code maxTurns} ist der Zugdeckel.
 *
 * @param games   1..{@link #MAX_GAMES}; die Oberflaeche bietet 5/10/20/50 an
 * @param timeout Bedenkzeit je KI-Entscheidung in Sekunden ({@code Game.AI_TIMEOUT})
 */
public record SparringArgs(String deck, int games, AiConfig ai, int timeout, int maxTurns) {

    public static final int DEFAULT_GAMES = 5;
    public static final int MAX_GAMES = 50;
    public static final int DEFAULT_MAX_TURNS = 60;
    public static final int MAX_MAX_TURNS = 500;

    public SparringArgs {
        if (deck == null || deck.isBlank()) {
            throw new IllegalArgumentException("deck fehlt");
        }
        if (games < 1 || games > MAX_GAMES) {
            throw new IllegalArgumentException("games muss 1-" + MAX_GAMES + " sein: " + games);
        }
        if (ai == null) {
            throw new IllegalArgumentException("ai fehlt");
        }
        if (timeout < AiConfig.MIN_TIMEOUT || timeout > AiConfig.MAX_TIMEOUT) {
            throw new IllegalArgumentException("timeout muss " + AiConfig.MIN_TIMEOUT + "-"
                    + AiConfig.MAX_TIMEOUT + " s sein: " + timeout);
        }
        if (maxTurns < 1 || maxTurns > MAX_MAX_TURNS) {
            throw new IllegalArgumentException("maxTurns muss 1-" + MAX_MAX_TURNS + " sein: " + maxTurns);
        }
    }

    /**
     * {@code {"type":"sparringStart","deck":"…","games":5,"ai":{"mode":"standard","profile":"Default"},
     * "timeout":5,"maxTurns":60}} - alles ausser {@code deck} ist optional. Braucht
     * {@code ForgeBoot.init()} (AiConfig prueft das Profil gegen Forges Profilliste).
     */
    public static SparringArgs fromJson(JsonNode msg) {
        String deck = msg.path("deck").asText("");
        JsonNode games = msg.path("games");
        if (!games.isMissingNode() && !games.isNull() && !games.isInt()) {
            throw new IllegalArgumentException("games muss eine ganze Zahl sein");
        }
        JsonNode timeout = msg.path("timeout");
        if (!timeout.isMissingNode() && !timeout.isNull() && !timeout.isInt()) {
            throw new IllegalArgumentException("timeout muss eine ganze Zahl sein");
        }
        JsonNode maxTurns = msg.path("maxTurns");
        if (!maxTurns.isMissingNode() && !maxTurns.isNull() && !maxTurns.isInt()) {
            throw new IllegalArgumentException("maxTurns muss eine ganze Zahl sein");
        }
        return new SparringArgs(deck, games.asInt(DEFAULT_GAMES), AiConfig.fromJson(msg.get("ai")),
                timeout.asInt(AiConfig.DEFAULT_TIMEOUT), maxTurns.asInt(DEFAULT_MAX_TURNS));
    }

    /** Auftrag fuer genau eine Partie gegen {@code opponent}, siehe {@link Job}. */
    public Job job(String opponent, long seed) {
        return new Job(deck, opponent, ai.spec(), timeout, maxTurns, seed);
    }

    /**
     * Was ein Kindprozess ({@code Main --sparring-one <json>}) fuer genau eine Partie braucht: die
     * beiden gespeicherten Deck-Namen, die KI-Einstellung als {@link AiConfig#spec()} (ein String statt
     * eines geschachtelten Objekts - der Kindprozess liest ihn mit {@link AiConfig#parse} zurueck),
     * Bedenkzeit, Zugdeckel und der Seed fuer {@code MyRandom}.
     */
    public record Job(String deck, String opponent, String ai, int timeout, int maxTurns, long seed) {
        public AiConfig aiConfig() {
            return AiConfig.parse(ai);
        }
    }
}
