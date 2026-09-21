package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import mtgplayer.forge.Precons;

/**
 * Übersetzt die Deck-Angabe aus startGame ({precon} | {saved} | {text, deckName?} |
 * {archidekt, deckName?}) in ein Deck.
 *
 * <p>{@code name} ist bei den Gegner-Eintraegen aus startGame der Spielername (z.B. "KI 1") –
 * das ist NICHT der Speichername fuer ein {@code text}- oder {@code archidekt}-Deck, dafuer gibt
 * es das eigene Feld {@code deckName}. Ohne {@code deckName} wird der vorgeschlagene
 * Commander-Name (bei {@code archidekt}: der Deckname von Archidekt) genutzt.</p>
 */
public final class DeckSource {

    /** @param save speichert das Deck (bei precon/saved ein No-Op); erst NACH allen resolve()-Aufrufen ausfuehren. */
    public record Resolved(Deck deck, Runnable save) { }

    private final DeckStore store;
    private final Archidekt archidekt;

    public DeckSource(DeckStore store) {
        this(store, Archidekt.standard());
    }

    public DeckSource(DeckStore store, Archidekt archidekt) {
        this.store = store;
        this.archidekt = archidekt;
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
        if (node.hasNonNull("archidekt")) {
            String id = String.valueOf(Archidekt.parseDeckId(node.get("archidekt").asText()));
            Archidekt.Result r = archidekt.fetch(id);
            String name = node.hasNonNull("deckName") && !node.get("deckName").asText().isBlank()
                    ? node.get("deckName").asText().trim() : r.name();
            return resolveText(r.text(), name, id);
        }
        if (node.hasNonNull("text")) {
            String text = node.get("text").asText();
            String name = node.hasNonNull("deckName") && !node.get("deckName").asText().isBlank()
                    ? node.get("deckName").asText().trim() : null;
            return resolveText(text, name, null);
        }
        throw new IllegalArgumentException("Deck braucht 'precon', 'saved', 'text' oder 'archidekt'");
    }

    /**
     * Holt ein gespeichertes Archidekt-Deck erneut von Archidekt (Id aus dem Tag
     * {@link DeckStore#ARCHIDEKT_TAG}); {@code save()} ueberschreibt es unter demselben Namen.
     *
     * @throws IllegalArgumentException "Resync &lt;name&gt;: …" – kein Archidekt-Deck, Fetch- oder Import-Fehler
     */
    public Resolved resync(String name) {
        String id = store.archidektId(name);
        if (id == null) {
            throw new IllegalArgumentException("Resync " + name + ": kein Archidekt-Deck");
        }
        try {
            return resolveText(archidekt.fetch(id).text(), name, id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Resync " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Gemeinsame Textlisten-Verarbeitung fuer {@code text} und {@code archidekt}: parse → Probleme →
     * Resolved mit Save. {@code archidektId} (oder null) wird als Tag ins Deck geschrieben.
     */
    private Resolved resolveText(String text, String deckNameOrNull, String archidektId) {
        DeckImport.Result r = DeckImport.parse(text);
        if (!r.problems().isEmpty()) {
            throw new IllegalArgumentException("Deck-Import:\n" + String.join("\n", r.problems()));
        }
        if (archidektId != null) {
            r.deck().getTags().add(DeckStore.ARCHIDEKT_TAG + archidektId);
        }
        String name = deckNameOrNull != null ? deckNameOrNull : DeckImport.suggestName(text, r.deck());
        return new Resolved(r.deck(), () -> store.save(name, r.deck()));
    }
}
