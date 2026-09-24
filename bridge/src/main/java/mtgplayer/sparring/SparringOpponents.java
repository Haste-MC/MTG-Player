package mtgplayer.sparring;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import mtgplayer.decks.DeckStore;
import mtgplayer.protocol.Messages;

/**
 * Gegnerwahl nach Spec §2: Kandidaten sind alle gespeicherten Decks ausser dem eigenen mit demselben
 * Bracket; sind es weniger als {@link #MIN_SAME_BRACKET}, wird auf Bracket ±1 erweitert; bleibt dann
 * keiner uebrig, bricht der Lauf mit einer Meldung ab. Ein Deck ohne Bracket spielt gegen die Decks
 * ohne Bracket ("unbekannt" gegen "unbekannt", Precons zaehlen nicht mit - sie sind nicht gespeichert).
 */
public final class SparringOpponents {

    /** Ab so vielen Kandidaten im eigenen Bracket wird nicht auf ±1 erweitert. */
    public static final int MIN_SAME_BRACKET = 3;

    private static final String HINT = ": Bracket setzen oder Decks importieren";

    private SparringOpponents() { }

    /**
     * @return die moeglichen Gegner, nach Name sortiert (wie {@link DeckStore#infos()})
     * @throws IllegalArgumentException unbekanntes Deck, oder kein Gegner uebrig
     */
    public static List<String> candidates(DeckStore store, String deck) {
        List<Messages.DeckInfo> infos = store.infos();
        Messages.DeckInfo own = infos.stream().filter(i -> i.name().equals(deck)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unbekanntes Deck: " + deck));
        Integer bracket = own.bracket();
        if (bracket == null) {
            // Spec §2: "Hat D keinen Bracket, zaehlen alle Decks ohne Bracket als Kandidaten (und die
            // Meldung sagt das)" - eine Erweiterung auf ±1 gibt es hier nicht, es gibt keine Zahl.
            List<String> out = pick(infos, deck, null, null);
            if (out.isEmpty()) {
                throw new IllegalArgumentException("keine Gegner ohne Bracket" + HINT);
            }
            return out;
        }
        List<String> same = pick(infos, deck, bracket, bracket);
        if (same.size() >= MIN_SAME_BRACKET) {
            return same;
        }
        List<String> wider = pick(infos, deck, bracket - 1, bracket + 1);
        if (wider.isEmpty()) {
            throw new IllegalArgumentException("keine Gegner im Bracket " + bracket + HINT);
        }
        return wider;
    }

    /** Zieht je Partie einen Gegner, gleichverteilt und mit Zuruecklegen (Spec §2.3). */
    public static List<String> draw(List<String> candidates, int games, Random rnd) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("keine Gegner zur Auswahl");
        }
        if (games < 1) {
            throw new IllegalArgumentException("games muss > 0 sein: " + games);
        }
        List<String> out = new ArrayList<>(games);
        for (int i = 0; i < games; i++) {
            out.add(candidates.get(rnd.nextInt(candidates.size())));
        }
        return List.copyOf(out);
    }

    /** Alle Decks ausser {@code deck} mit Bracket in [min, max]; {@code min == null}: die ohne Bracket. */
    private static List<String> pick(List<Messages.DeckInfo> infos, String deck, Integer min, Integer max) {
        List<String> out = new ArrayList<>();
        for (Messages.DeckInfo i : infos) {
            if (i.name().equals(deck)) {
                continue;
            }
            Integer b = i.bracket();
            if (min == null ? b == null : b != null && b >= min && b <= max) {
                out.add(i.name());
            }
        }
        return List.copyOf(out);
    }
}
