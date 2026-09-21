package mtgplayer.forge;

import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.storage.IStorage;
import mtgplayer.protocol.Messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Forges mitgelieferte Commander-Precons (res/quest/commanderprecons). */
public final class Precons {

    private static volatile List<Messages.DeckInfo> cached;

    private Precons() { }

    private static IStorage<Deck> storage() {
        return FModel.getDecks().getCommanderPrecons();
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>(storage().getItemNames());
        Collections.sort(names);
        return names;
    }

    public static Deck load(String name) {
        Deck d = storage().get(name);
        if (d == null) {
            throw new IllegalArgumentException("unbekanntes Precon: " + name);
        }
        return d;
    }

    /** Alle Precons als {@link Messages.DeckInfo}, nach Name sortiert; einmal berechnet und gecacht. */
    public static List<Messages.DeckInfo> infos() {
        List<Messages.DeckInfo> out = cached;
        if (out == null) {
            List<Messages.DeckInfo> infos = new ArrayList<>();
            for (String n : names()) {
                infos.add(info(n, load(n), null));
            }
            out = List.copyOf(infos);
            cached = out;
        }
        return out;
    }

    /** Commander aus {@code deck.getCommanders()}: Name und Bildschluessel ({@code getImageKey(false)}). */
    public static Messages.DeckInfo info(String name, Deck deck, String archidekt) {
        List<Messages.Commander> commanders = new ArrayList<>();
        for (PaperCard pc : deck.getCommanders()) {
            commanders.add(new Messages.Commander(pc.getName(), pc.getImageKey(false)));
        }
        return new Messages.DeckInfo(name, List.copyOf(commanders), archidekt);
    }
}
