package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import mtgplayer.forge.Precons;

/** Übersetzt die Deck-Angabe aus startGame ({precon} | {saved} | {text, name?}) in ein Deck. */
public final class DeckSource {

    private final DeckStore store;

    public DeckSource(DeckStore store) {
        this.store = store;
    }

    public Deck resolve(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("Deck fehlt");
        }
        if (node.hasNonNull("precon")) {
            return Precons.load(node.get("precon").asText());
        }
        if (node.hasNonNull("saved")) {
            return store.load(node.get("saved").asText());
        }
        if (node.hasNonNull("text")) {
            String text = node.get("text").asText();
            DeckImport.Result r = DeckImport.parse(text);
            if (!r.problems().isEmpty()) {
                throw new IllegalArgumentException("Deck-Import:\n" + String.join("\n", r.problems()));
            }
            String name = node.hasNonNull("name") && !node.get("name").asText().isBlank()
                    ? node.get("name").asText().trim() : DeckImport.suggestName(text, r.deck());
            store.save(name, r.deck());
            return r.deck();
        }
        throw new IllegalArgumentException("Deck braucht 'precon', 'saved' oder 'text'");
    }
}
