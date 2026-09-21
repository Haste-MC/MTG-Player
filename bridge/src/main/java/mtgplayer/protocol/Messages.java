package mtgplayer.protocol;

import mtgplayer.ai.AiConfig;

import java.util.List;

/** Alle Nachrichten außer {@link Snapshot}. Jedes Record trägt sein {@code type} selbst. */
public final class Messages {

    private Messages() { }

    /** imageKey: Forges Bildschluessel ({@code c:Name|SET|art}), siehe {@code PaperCard.getImageKey(false)}. */
    public record Commander(String name, String imageKey) { }

    /** archidekt: Archidekt-Deck-Id, null wenn das Deck kein Archidekt-Import ist. */
    public record DeckInfo(String name, List<Commander> commanders, String archidekt) { }

    public record Lobby(String type, List<DeckInfo> precons, List<DeckInfo> decks,
                        List<String> aiModes, List<String> aiProfiles, int aiTimeout) {
        public Lobby(List<DeckInfo> precons, List<DeckInfo> decks) {
            this("lobby", precons, decks, AiConfig.modes(), AiConfig.profiles(), AiConfig.DEFAULT_TIMEOUT);
        }
    }

    /**
     * detail: Kartendaten (sichtbarkeitsgefiltert) für Auswahlen, deren Karten nicht im Snapshot sind (Tutor).
     * max/lethal: für amount/damage. movable: für cardlist.
     */
    public record Option(int index, String label, Integer card, Integer player,
                         Snapshot.CardSnap detail, Integer max, Integer lethal, Boolean movable) {
        public Option(int index, String label, Integer card, Integer player) {
            this(index, label, card, player, null, null, null, null);
        }
    }

    /**
     * kind: one | many | order | confirm | number | text | ability | entities | reveal | damage | amount | cardlist
     * value der Antwort: Index (one/ability), Index-Liste (many/entities/order/cardlist), bool (confirm),
     * Zahl (number), String (text), Zahlen-Liste je Option (damage/amount). reveal erwartet keine Antwort.
     * amount/atLeastOne nur bei damage/amount. flags nur bei cardlist: erlaubte Richtungen
     * ("anywhere" oder eine Teilmenge aus "top"/"bottom"), null bei Antworten von einer alten Bridge.
     */
    public record Choice(String type, int id, String kind, String title, String message,
                         List<Option> options, int min, int max, Integer card, Integer amount, Boolean atLeastOne,
                         List<String> flags) {
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max, Integer card) {
            this("choice", id, kind, title, message, options, min, max, card, null, null, null);
        }
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max,
                      Integer card, Integer amount, Boolean atLeastOne) {
            this("choice", id, kind, title, message, options, min, max, card, amount, atLeastOne, null);
        }
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max,
                      Integer card, Integer amount, Boolean atLeastOne, List<String> flags) {
            this("choice", id, kind, title, message, options, min, max, card, amount, atLeastOne, flags);
        }
    }

    /**
     * kind = GameLogEntryType-Name (null für Bridge-eigene Zeilen), card = Quellkarte falls bekannt.
     * id: fortlaufende Nummer je Partie (siehe WebGuiGame.remember) – macht den Reconnect-Replay
     * idempotent; null für Zeilen, die (noch) keine id bekommen haben.
     */
    public record LogLine(String type, String text, String kind, Integer card, Integer id) {
        public LogLine(String text) { this("log", text, null, null, null); }
        public LogLine(String text, String kind, Integer card) { this("log", text, kind, card, null); }
        public LogLine withId(int id) { return new LogLine(type, text, kind, card, id); }
    }

    public record GameOver(String type, String winner) {
        public GameOver(String winner) { this("gameOver", winner); }
    }

    public record ErrorMsg(String type, String text) {
        public ErrorMsg(String text) { this("error", text); }
    }

    /** Phasen, in denen angehalten wird (Namen der PhaseType-Konstanten), je eigener/gegnerischer Zug. */
    public record StopsMsg(List<String> own, List<String> opp) { }
}
