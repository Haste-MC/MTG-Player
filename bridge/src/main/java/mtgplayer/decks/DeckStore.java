package mtgplayer.decks;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import mtgplayer.forge.ForgeBoot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gespeicherte eigene Decks als Forge-.dck unter einem Verzeichnis. Anzeigename = Deck-Name aus der
 * Datei. Der Anzeigename wird roh aus der "Name="-Zeile gelesen statt über
 * {@code DeckSerializer.fromFile(...).getName()}: Forges {@code DeckBase(String)}-Konstruktor, den
 * {@code DeckSerializer} beim Laden intern aufruft, ersetzt "/" unwiderruflich durch "_"
 * (Forge-Decks werden u.a. als "verzeichnis/name" referenziert) – für den reinen Anzeigenamen
 * wollen wir diese Sanitisierung nicht.
 */
public final class DeckStore {

    private static final String NAME_PREFIX = "Name=";

    private final Path dir;

    public DeckStore(Path dir) {
        this.dir = dir;
    }

    public static DeckStore standard() {
        return new DeckStore(ForgeBoot.dataDir().resolve("decks"));
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(p -> {
                String name = readDisplayName(p);
                if (name != null) out.add(name);
            });
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht lesen: " + dir, e);
        }
        Collections.sort(out);
        return out;
    }

    public Deck load(String name) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("unbekanntes Deck: " + name);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".dck")).toList()) {
                if (name.equals(readDisplayName(p))) {
                    Deck d = DeckSerializer.fromFile(p.toFile());
                    if (d != null) {
                        // DeckSerializer baut das Deck intern über new Deck(rawName), und
                        // DeckBase(String)  ersetzt "/" durch "_" – siehe Klassen-Javadoc. Der
                        // Anzeigename (roh, ohne diese Sanitisierung) wird hier nachträglich
                        // wiederhergestellt, damit load(name).getName() == name gilt.
                        d.setName(name);
                        return d;
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht lesen: " + dir, e);
        }
        throw new IllegalArgumentException("unbekanntes Deck: " + name);
    }

    private static String readDisplayName(Path p) {
        try {
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith(NAME_PREFIX)) {
                    return line.substring(NAME_PREFIX.length());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Datei nicht lesen: " + p, e);
        }
        return null;
    }

    public void save(String name, Deck deck) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht anlegen: " + dir, e);
        }
        deck.setName(name);
        File f = dir.resolve(fileName(name)).toFile();
        DeckSerializer.writeDeck(deck, f);
    }

    static String fileName(String name) {
        return name.replaceAll("[^A-Za-z0-9 _\\-\\[\\]().]", "_") + ".dck";
    }
}
