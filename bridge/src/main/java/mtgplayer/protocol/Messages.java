package mtgplayer.protocol;

import java.util.List;

/** Alle Nachrichten außer {@link Snapshot}. Jedes Record trägt sein {@code type} selbst. */
public final class Messages {

    private Messages() { }

    public record Lobby(String type, List<String> precons) {
        public Lobby(List<String> precons) { this("lobby", precons); }
    }

    public record Option(int index, String label, Integer card, Integer player) { }

    /**
     * kind: one | many | order | confirm | number | text | ability | entities | reveal
     * options: Auswahl mit Index; value der Antwort ist je nach kind
     * Index (one/ability), Index-Liste (many/entities/order), bool (confirm), Zahl (number), String (text).
     * reveal erwartet keine Antwort (min == max == 0, rein informativ).
     */
    public record Choice(String type, int id, String kind, String title, String message,
                         List<Option> options, int min, int max, Integer card) {
        public Choice(int id, String kind, String title, String message, List<Option> options, int min, int max, Integer card) {
            this("choice", id, kind, title, message, options, min, max, card);
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
