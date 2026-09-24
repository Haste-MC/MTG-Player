package mtgplayer.decks;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textliste (Archidekt-/Arena-Export, Forge-.dck-Sektionen) → Deck. Nutzt Forges DeckRecognizer;
 * davor werden Archidekt-Kategorien "[Ramp]" und Foil-Marker entfernt. Kein Commander in der
 * Liste → erster Commander-fähiger Eintrag aus dem Main wandert in die Commander-Sektion.
 * Karten, die Forge nur wegen eines nicht auflösbaren Set/Collector-Nummer-Paars als unbekannt
 * einstuft (der Kartenname selbst wurde erkannt), werden ein zweites Mal ohne Set-Angabe
 * aufgelöst, bevor sie als Problem gemeldet werden. Jede nicht interpretierbare Zeile (unbekannter
 * Text, eine erkannte, aber nicht erlaubte Sektion, eine Forge-Warnung) wird als genau ein Problem
 * gemeldet – wo Forge danach folgende Karten einsortiert, spielt keine Rolle, denn ein Import mit
 * nicht-leerer {@code problems}-Liste wird ohnehin nicht übernommen (siehe {@code DeckSource}).
 */
public final class DeckImport {

    public record Result(Deck deck, List<String> problems) { }

    private static final Pattern CATEGORY = Pattern.compile("\\s*\\[[^\\]]*\\]\\s*$");
    private static final Pattern FOIL = Pattern.compile("\\s*\\*F\\*\\s*$");
    private static final Pattern NAME_LINE = Pattern.compile("^(?:name|deck)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNKNOWN_CARD_SET_SUFFIX = Pattern.compile("^(.*) \\[[^\\]]*\\]$");

    private DeckImport() { }

    public static Result parse(String text) {
        List<String> problems = new ArrayList<>();
        Deck deck = new Deck();
        if (text == null || text.isBlank()) {
            problems.add("Leere Liste");
            return new Result(deck, problems);
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            lines[i] = clean(lines[i]);
        }
        DeckRecognizer rec = new DeckRecognizer();
        rec.setAllowedDeckSections(List.of(DeckSection.Main, DeckSection.Sideboard, DeckSection.Commander));
        rec.forceImportBannedAndRestrictedCards();
        List<Token> tokens = rec.parseCardList(lines);

        DeckSection currentSection = null;
        for (Token t : tokens) {
            TokenType type = t.getType();
            if (type == TokenType.DECK_NAME) {
                deck.setName(t.getText());
                continue;
            }
            if (type == TokenType.DECK_SECTION_NAME) {
                currentSection = DeckSection.valueOf(t.getText());
                continue;
            }
            if (type == TokenType.COMMENT) {
                continue;
            }
            if (type == TokenType.UNKNOWN_TEXT) {
                String txt = t.getText();
                if (!txt.isBlank()) {
                    problems.add("Nicht erkannt: " + txt);
                }
                continue;
            }
            if (type == TokenType.UNSUPPORTED_DECK_SECTION) {
                problems.add("Sektion nicht unterstützt: " + t.getText());
                continue;
            }
            if (type == TokenType.WARNING_MESSAGE) {
                problems.add(t.getText());
                continue;
            }
            if (t.isTokenForDeck()) {
                DeckSection section = t.getTokenSection() == null ? DeckSection.Main : t.getTokenSection();
                deck.getOrCreate(section).add(t.getCard(), t.getQuantity());
                continue;
            }
            if (type == TokenType.UNKNOWN_CARD || type == TokenType.UNSUPPORTED_CARD
                    || type == TokenType.CARD_FROM_INVALID_SET || type == TokenType.CARD_FROM_NOT_ALLOWED_SET) {
                // Auch bei bekanntem Namen im falschen/unbekannten Set nochmal ohne Set-Angabe versuchen:
                // Archidekt-Exporte tragen Set-Kuerzel, und neu zugespielte Karten liegen bei uns oft in einem
                // anderen Set als im Export (Editionsdateien hinken der Kartensynchronisierung hinterher).
                Token recovered = retryWithoutSet(rec, t, currentSection);
                if (recovered != null) {
                    DeckSection section = recovered.getTokenSection() == null
                            ? (currentSection == null ? DeckSection.Main : currentSection) : recovered.getTokenSection();
                    deck.getOrCreate(section).add(recovered.getCard(), recovered.getQuantity());
                } else {
                    problems.add("Unbekannte Karte: " + t.getText());
                }
            }
        }

        if (deck.getCommanders().isEmpty()) {
            PaperCard cmd = firstCommanderCandidate(deck);
            if (cmd == null) {
                problems.add("Kein Commander gefunden (Sektion 'Commander' oder legendäre Kreatur im Main)");
            } else {
                deck.get(DeckSection.Main).remove(cmd, 1);
                deck.getOrCreate(DeckSection.Commander).add(cmd, 1);
            }
        }
        return new Result(deck, problems);
    }

    /**
     * Forge lehnt Karten mit falschem/nicht existentem Set+Sammlernummer-Paar komplett ab (kein
     * Fallback auf den Kartennamen), selbst wenn der Name real ist. Wir extrahieren den Namen aus
     * dem UNKNOWN_CARD-Text ("Name [SET]") und lassen Forge ihn ein zweites Mal ohne Set-Angabe
     * auflösen – Forge sucht dann selbst eine unterstützte Auflage. Nur für Tokens mit qty &gt; 0,
     * denn qty == 0 bedeutet, dass Forge nicht mal den Kartennamen erkannt hat (ganze Zeile als
     * Text), ein erneuter Versuch wäre dort sinnlos.
     */
    private static Token retryWithoutSet(DeckRecognizer rec, Token unknown, DeckSection currentSection) {
        if (unknown.getQuantity() <= 0) return null;
        Matcher m = UNKNOWN_CARD_SET_SUFFIX.matcher(unknown.getText());
        String name = m.matches() ? m.group(1) : unknown.getText();
        Token retry = rec.recognizeLine(unknown.getQuantity() + " " + name, currentSection);
        if (retry != null && (retry.getType() == TokenType.LEGAL_CARD || retry.getType() == TokenType.LIMITED_CARD)) {
            return retry;
        }
        return null;
    }

    private static PaperCard firstCommanderCandidate(Deck deck) {
        if (!deck.has(DeckSection.Main)) return null;
        for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Main)) {
            if (e.getKey().getRules().canBeCommander()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** "Name: X"-Zeile, sonst Commander-Name, sonst "Import". */
    public static String suggestName(String text, Deck deck) {
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                Matcher m = NAME_LINE.matcher(line.trim());
                if (m.matches()) return m.group(1).trim();
            }
        }
        if (deck != null && !deck.getCommanders().isEmpty()) {
            return deck.getCommanders().get(0).getName();
        }
        return "Import";
    }

    private static String clean(String line) {
        String s = FOIL.matcher(CATEGORY.matcher(line).replaceAll("")).replaceAll("");
        return s.trim();
    }
}
