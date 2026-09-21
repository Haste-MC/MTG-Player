package mtgplayer.decks;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Messages;

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

    /** Deck-Tag (Forge {@code Tags=} im .dck), das die Archidekt-Deck-Id eines Imports festhaelt. */
    public static final String ARCHIDEKT_TAG = "archidekt:";
    /** Deck-Tag mit Archidekts {@code updatedAt} (ISO-String) zum Zeitpunkt des Imports/Resyncs. */
    public static final String ARCHIDEKT_UPDATED_TAG = "archidekt-updated:";

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

    /**
     * Alle gespeicherten Decks als {@link Messages.DeckInfo}, nach Name sortiert. Eine Datei, die sich
     * nicht laden laesst (kaputte .dck, unbekannte Karte, ...), wird mit Hinweis auf stderr uebersprungen -
     * ein einzelnes defektes Deck darf nicht die ganze "lobby"-Nachricht (und damit die Lobby) verhindern.
     */
    public List<Messages.DeckInfo> infos() {
        List<Messages.DeckInfo> out = new ArrayList<>();
        for (String name : names()) {
            try {
                Deck d = load(name);
                out.add(Precons.info(name, d, archidektId(d), archidektUpdated(d)));
            } catch (RuntimeException e) {
                System.err.println("DeckStore: ueberspringe " + fileName(name) + ": " + e);
            }
        }
        return out;
    }

    /** @return Archidekt-Deck-Id aus dem Tag {@link #ARCHIDEKT_TAG}, oder null (unbekanntes Deck / kein Import) */
    public String archidektId(String name) {
        try {
            return archidektId(load(name));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return gespeicherter Name des Decks mit Tag {@code archidekt:<id>}, oder null. Laeuft ueber
     *         {@link #infos()} (unlesbare Decks werden dort uebersprungen).
     */
    public String byArchidektId(String id) {
        if (id == null) return null;
        for (Messages.DeckInfo info : infos()) {
            if (id.equals(info.archidekt())) {
                return info.name();
            }
        }
        return null;
    }

    private static String archidektId(Deck deck) {
        return tagValue(deck, ARCHIDEKT_TAG);
    }

    private static String archidektUpdated(Deck deck) {
        return tagValue(deck, ARCHIDEKT_UPDATED_TAG);
    }

    private static String tagValue(Deck deck, String prefix) {
        for (String tag : deck.getTags()) {
            if (tag.startsWith(prefix)) {
                return tag.substring(prefix.length());
            }
        }
        return null;
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

    /**
     * Loescht die Datei des gespeicherten Decks (Suche wie {@link #load}, ueber die
     * {@code Name=}-Zeile).
     *
     * @throws IllegalArgumentException unbekanntes Deck ("unbekanntes Deck: &lt;name&gt;")
     * @throws IllegalStateException Verzeichnis nicht lesbar oder Datei nicht loeschbar
     */
    public void delete(String name) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("unbekanntes Deck: " + name);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".dck")).toList()) {
                if (name.equals(readDisplayName(p))) {
                    Files.delete(p);
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck nicht loeschen: " + name, e);
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
