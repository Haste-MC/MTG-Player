package mtgplayer.decks;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Kartenvorschlaege zu den Luecken eines Decks (Spec 2026-09-25-kartenvorschlaege §4/§5).
 *  Kandidaten kommen aus der EDHREC-Seite des Commanders und werden mit denselben Regeln eingeordnet wie
 *  das Deck selbst (DeckAnalysis.categoriesOf) - Luecke und Vorschlag sprechen so dieselbe Sprache. */
public final class Suggestions {

    /** Hoechstens so viele Vorschlaege je angefragter Rolle. */
    public static final int PER_ROLE = 5;
    /** Hoechstens so viele Vorschlaege insgesamt, egal wie viele Rollen angefragt sind. */
    public static final int MAX_ITEMS = 15;

    /** Bleibt in diesem Task immer null - Schnitte kommen erst in Task 3. */
    public record Cut(String name, String reason) { }

    public record Item(String name, String role, String manaCost, int cmc, Double share, boolean gameChanger,
                        String imageKey, String text, Cut cut) { }

    public record Result(String source, String note, List<Item> items) { }

    private Suggestions() { }

    /**
     * @param deck   das zu ergaenzende Deck (Haupt- und Kommandeursektion zaehlen)
     * @param roles  angefragte Luecken, in der Reihenfolge aus DeckAnalysis.rules() - bei Ueberschneidung
     *               gewinnt die zuerst angefragte Rolle
     * @param bracket 1-5 oder null; steuert nur, ob EDHRECs Game Changer mitgenommen werden
     * @param page   EDHREC-Kandidaten des Commanders; in diesem Task nie null (Rueckfall kommt in Task 3)
     */
    public static Result of(Deck deck, List<String> roles, Integer bracket, Edhrec.Page page) {
        if (roles.isEmpty()) {
            // Ohne Luecke gibt es nichts zu erledigen - eine leere Rollenliste ist kein Fehler.
            return new Result("edhrec", "Keine Lücke gefunden.", List.of());
        }
        // Entdupliziert, sonst wuerde z.B. List.of("ramp", "ramp") die Kandidatenliste zweimal durchlaufen
        // und bis zu 2*PER_ROLE statt hoechstens PER_ROLE Vorschlaege fuer dieselbe Rolle liefern.
        // LinkedHashSet haelt dabei die angefragte Reihenfolge fest (wichtig fuer "erste Rolle gewinnt" unten).
        List<String> requestedRoles = List.copyOf(new LinkedHashSet<>(roles));

        ColorSet identity = commanderIdentity(deck);
        Set<String> inDeck = namesInDeck(deck);
        boolean dropGameChangers = bracket != null && (bracket == 1 || bracket == 2);
        boolean noteGameChangers = bracket != null && bracket == 3;

        // Verhindert, dass dieselbe Karte zweimal auftaucht, wenn sie mehrere angefragte Rollen trifft.
        // Eine Karte gilt erst dann als "vergeben" (landet in chosen), wenn sie fuer eine Rolle tatsaechlich
        // in die Top PER_ROLE aufgenommen wurde - trifft sie eine frueher angefragte Rolle, schafft es dort
        // aber nicht in die Top 5 (weil andere Kandidaten einen hoeheren share/niedrigeren cmc haben), kann
        // sie bei einer spaeter angefragten Rolle noch erscheinen. Das ist eine bewusste Lesart von "die
        // zuerst angefragte Rolle gewinnt": gemeint ist die erste Rolle, in der die Karte tatsaechlich
        // vorgeschlagen wird, nicht die erste Rolle, die sie ueberhaupt trifft. Festgehalten fuer Task 3,
        // damit der Rueckfall ohne EDHREC dieselbe Regel benutzt.
        Set<String> chosen = new LinkedHashSet<>();
        List<Item> items = new ArrayList<>();
        boolean anyGameChangerIncluded = false;

        for (String role : requestedRoles) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            List<Item> candidates = new ArrayList<>();
            for (Edhrec.Card ec : page.cards()) {
                String lname = ec.name().toLowerCase(Locale.ROOT);
                if (chosen.contains(lname) || inDeck.contains(lname)) {
                    continue;
                }
                PaperCard pc = StaticData.instance().getCommonCards().getCard(ec.name());
                if (pc == null) {
                    continue;   // Forge kennt die Karte nicht - nicht mitzaehlen statt abstuerzen
                }
                CardRules rules = pc.getRules();
                if (rules == null || rules.getType().isLand()) {
                    continue;
                }
                if (!rules.getColorIdentity().hasNoColorsExcept(identity)) {
                    continue;
                }
                if (!DeckAnalysis.categoriesOf(rules).contains(role)) {
                    continue;
                }
                if (dropGameChangers && ec.gameChanger()) {
                    continue;
                }
                candidates.add(new Item(pc.getName(), role, rules.getManaCost().getShortString(),
                        rules.getManaCost().getCMC(), ec.share(), ec.gameChanger(), pc.getImageKey(false),
                        rules.getOracleText(), null));
            }
            // share absteigend, dann Manabetrag aufsteigend, dann Name - so vergleichbar wie das Board selbst.
            candidates.sort(Comparator.comparingDouble((Item i) -> -i.share())
                    .thenComparingInt(Item::cmc)
                    .thenComparing(Item::name));

            int takenForRole = 0;
            for (Item candidate : candidates) {
                if (takenForRole >= PER_ROLE) {
                    break;
                }
                items.add(candidate);
                chosen.add(candidate.name().toLowerCase(Locale.ROOT));
                anyGameChangerIncluded |= candidate.gameChanger();
                takenForRole++;
                if (items.size() >= MAX_ITEMS) {
                    break;
                }
            }
        }

        String note = (noteGameChangers && anyGameChangerIncluded)
                ? "Game Changer sind in Bracket 3 auf drei Karten begrenzt." : null;
        return new Result("edhrec", note, List.copyOf(items));
    }

    /** Vereinigung der Farbidentitaet aller Kommandeure; ohne Kommandeur gilt keine Einschraenkung. */
    private static ColorSet commanderIdentity(Deck deck) {
        List<PaperCard> commanders = deck.getCommanders();
        if (commanders.isEmpty()) {
            return ColorSet.fromMask(MagicColor.ALL_COLORS);
        }
        byte mask = 0;
        for (PaperCard pc : commanders) {
            if (pc.getRules() != null) {
                mask |= pc.getRules().getColorIdentity().getColor();
            }
        }
        return ColorSet.fromMask(mask);
    }

    /** Kleingeschriebene Namen aus Haupt- und Kommandeursektion - "schon im Deck" ist ein reiner Namensabgleich. */
    private static Set<String> namesInDeck(Deck deck) {
        Set<String> out = new LinkedHashSet<>();
        for (DeckSection section : List.of(DeckSection.Main, DeckSection.Commander)) {
            var pool = deck.get(section);
            if (pool == null) {
                continue;
            }
            for (Map.Entry<PaperCard, Integer> e : pool) {
                out.add(e.getKey().getName().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
