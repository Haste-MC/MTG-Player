package mtgplayer.forge;

import forge.deck.Deck;
import forge.model.FModel;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Forges mitgelieferte Commander-Precons (res/quest/commanderprecons). */
public final class Precons {

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
}
