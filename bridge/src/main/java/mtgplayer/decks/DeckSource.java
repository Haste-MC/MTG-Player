package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import mtgplayer.forge.Precons;

/**
 * Übersetzt die Deck-Angabe aus startGame ({precon} | {saved} | {text, deckName?}) in ein Deck.
 *
 * <p>{@code name} ist bei den Gegner-Eintraegen aus startGame der Spielername (z.B. "KI 1") –
 * das ist NICHT der Speichername fuer ein {@code text}-Deck, dafuer gibt es das eigene Feld
 * {@code deckName}. Ohne {@code deckName} wird der vorgeschlagene Commander-Name genutzt.</p>
 */
public final class DeckSource {

    /** @param save speichert das Deck (bei precon/saved ein No-Op); erst NACH allen resolve()-Aufrufen ausfuehren. */
    public record Resolved(Deck deck, Runnable save) { }

    private final DeckStore store;

    public DeckSource(DeckStore store) {
        this.store = store;
    }

    public Resolved resolve(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("Deck fehlt");
        }
        if (node.hasNonNull("precon")) {
            return new Resolved(Precons.load(node.get("precon").asText()), () -> { });
        }
        if (node.hasNonNull("saved")) {
            return new Resolved(store.load(node.get("saved").asText()), () -> { });
        }
        if (node.hasNonNull("text")) {
            String text = node.get("text").asText();
            DeckImport.Result r = DeckImport.parse(text);
            if (!r.problems().isEmpty()) {
                throw new IllegalArgumentException("Deck-Import:\n" + String.join("\n", r.problems()));
            }
            String name = node.hasNonNull("deckName") && !node.get("deckName").asText().isBlank()
                    ? node.get("deckName").asText().trim() : DeckImport.suggestName(text, r.deck());
            return new Resolved(r.deck(), () -> store.save(name, r.deck()));
        }
        throw new IllegalArgumentException("Deck braucht 'precon', 'saved' oder 'text'");
    }
}
