package mtgplayer.protocol;

import java.util.List;

/** Alle Nachrichten außer {@link Snapshot}. Jedes Record trägt sein {@code type} selbst. */
public final class Messages {

    private Messages() { }

    public record Lobby(String type, List<String> precons) {
        public Lobby(List<String> precons) { this("lobby", precons); }
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
     * amount/atLeastOne nur bei damage/amount.
     */
    public record Choice(String type, int id, String kind, String title, String message,
                         List<Option> options, int min, int max, Integer card, Integer amount, Boolean atLeastOne) {
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max, Integer card) {
            this("choice", id, kind, title, message, options, min, max, card, null, null);
        }
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max,
                      Integer card, Integer amount, Boolean atLeastOne) {
            this("choice", id, kind, title, message, options, min, max, card, amount, atLeastOne);
        }
    }

    public record LogLine(String type, String text) {
        public LogLine(String text) { this("log", text); }
    }

    public record GameOver(String type, String winner) {
        public GameOver(String winner) { this("gameOver", winner); }
    }

    public record ErrorMsg(String type, String text) {
        public ErrorMsg(String text) { this("error", text); }
    }
}
