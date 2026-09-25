package mtgplayer.protocol;

import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckAnalysis;
import mtgplayer.decks.Suggestions;
import mtgplayer.stats.MatchRecord;

import java.util.List;

/** Alle Nachrichten außer {@link Snapshot}. Jedes Record trägt sein {@code type} selbst. */
public final class Messages {

    private Messages() { }

    /** imageKey: Forges Bildschluessel ({@code c:Name|SET|art}), siehe {@code PaperCard.getImageKey(false)}. */
    public record Commander(String name, String imageKey) { }

    /**
     * archidekt: Archidekt-Deck-Id, null wenn das Deck kein Archidekt-Import ist. archidektUpdated:
     * Archidekts {@code updatedAt} zum Zeitpunkt des Imports (null ohne Tag) – der Client vergleicht es
     * mit dem Stand aus {@link ArchidektDecks}. bracket: Commander-Bracket (1-5), null wenn unbekannt
     * (Precons und Decks ohne gesetzten Bracket).
     */
    public record DeckInfo(String name, List<Commander> commanders, String archidekt, String archidektUpdated,
                           Integer bracket) { }

    /** Eintrag der Konto-Deckliste von Archidekt (nur Commander-Decks); art: Bild-URL oder null. */
    public record ArchidektEntry(long id, String name, String updatedAt, String art) { }

    /** Antwort auf {@code archidektList}. */
    public record ArchidektDecks(String type, String username, List<ArchidektEntry> decks) {
        public ArchidektDecks(String username, List<ArchidektEntry> decks) { this("archidektDecks", username, decks); }
    }

    /**
     * Fortschritt eines {@code archidektImport}-Laufs: vor jedem Deck mit {@code current} = Name, zum
     * Schluss mit {@code done == total} und {@code current == null}; errors: "&lt;Name&gt;: &lt;Grund&gt;" je
     * fehlgeschlagenem Deck.
     */
    public record ArchidektProgress(String type, int done, int total, String current, List<String> errors) {
        public ArchidektProgress(int done, int total, String current, List<String> errors) {
            this("archidektProgress", done, total, current, errors);
        }
    }

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

    /**
     * Die Bridge rechnet und der Browser haette sonst nichts zu zeigen: {@code seconds} ist die
     * Dauer der bisherigen Stille, {@code player} der Sitz mit Prioritaet ({@code null} wenn
     * unbekannt). {@code seconds == 0} beendet die Anzeige (siehe {@link mtgplayer.gui.ThinkingTicker}).
     */
    public record Thinking(String type, Integer player, int seconds) {
        public Thinking(Integer player, int seconds) { this("thinking", player, seconds); }
    }

    public record GameOver(String type, String winner) {
        public GameOver(String winner) { this("gameOver", winner); }
    }

    public record ErrorMsg(String type, String text) {
        public ErrorMsg(String text) { this("error", text); }
    }

    /** Phasen, in denen angehalten wird (Namen der PhaseType-Konstanten), je eigener/gegnerischer Zug. */
    public record StopsMsg(List<String> own, List<String> opp) { }

    /**
     * Antwort auf {@code {"type":"analyzeDeck","deck":"&lt;Name&gt;"}}: was in dem Deck steckt, rein aus
     * der Kartendatenbank (siehe {@link DeckAnalysis} - Textmustererkennung, keine Semantik).
     * deck: der angefragte Name (gespeichertes Deck oder Precon).
     */
    public record DeckAnalysisMsg(String type, String deck, DeckAnalysis analysis) {
        public DeckAnalysisMsg(String deck, DeckAnalysis analysis) { this("deckAnalysis", deck, analysis); }
    }

    /** Antwort auf {"type":"suggestCards"} (Spec 2026-09-25-kartenvorschlaege §2). */
    public record CardSuggestionsMsg(String type, String deck, String source, String note,
                                     List<Suggestions.Item> suggestions) {
        public CardSuggestionsMsg(String deck, Suggestions.Result r) {
            this("cardSuggestions", deck, r.source(), r.note(), r.items());
        }
    }

    /**
     * Bei Verbindung (nach {@link Lobby}) und nach jeder Aenderung (siehe {@link mtgplayer.server.Bridge}):
     * die neuesten 300 Partien, aelteste zuerst, je Datensatz OHNE {@code timeline}
     * (siehe {@link MatchRecord#withoutTimeline()} - das Feld wird dabei {@code null}, und
     * {@code Json.MAPPER} laesst ein {@code null}-Feld beim Serialisieren komplett weg statt einer
     * leeren Liste, der Client erkennt daran "nicht geladen"). {@code total}: die tatsaechliche
     * Gesamtzahl gespeicherter Partien, auch wenn mehr als 300 vorliegen. Die volle Zeitachse einer
     * Partie liefert erst {@link MatchMsg} auf {@code matchDetail}.
     */
    public record Matches(String type, List<MatchRecord> matches, int total) {
        public Matches(List<MatchRecord> matches, int total) { this("matches", matches, total); }
    }

    /**
     * Fortschritt eines Sparring-Laufs (Spec §3): einmal zu Beginn mit {@code done == 0} und nach jedem
     * Spielende. {@code current} ist der Gegner der gerade laufenden Partie ({@code null}, sobald keine
     * mehr folgt), {@code errors} sammelt "&lt;Gegner&gt;: &lt;Grund&gt;" je gescheiterter Partie (sie
     * zählt trotzdem in {@code done} mit), und {@code running} ist genau in der letzten Nachricht eines
     * Laufs {@code false} - auch nach {@code sparringCancel}.
     */
    public record SparringProgress(String type, int done, int total, String current, List<String> errors,
                                   boolean running) {
        public SparringProgress(int done, int total, String current, List<String> errors, boolean running) {
            this("sparringProgress", done, total, current, errors, running);
        }
    }

    /** Antwort auf {@code {"type":"matchDetail","id":"..."}}: der vollstaendige Datensatz inkl. Zeitachse. */
    public record MatchMsg(String type, MatchRecord match) {
        public MatchMsg(MatchRecord match) { this("match", match); }
    }
}
