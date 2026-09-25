package mtgplayer.decks;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Kartenvorschlaege zu den Luecken eines Decks (Spec 2026-09-25-kartenvorschlaege §4/§5/§7).
 *  Kandidaten kommen aus der EDHREC-Seite des Commanders und werden mit denselben Regeln eingeordnet wie
 *  das Deck selbst (DeckAnalysis.categoriesOf) - Luecke und Vorschlag sprechen so dieselbe Sprache. Fuehrt
 *  EDHREC den Commander nicht ({@code page == null}), kommen die Kandidaten stattdessen aus Forges
 *  Kartendatenbank (siehe {@link #of}). */
public final class Suggestions {

    /** Hoechstens so viele Vorschlaege je angefragter Rolle. */
    public static final int PER_ROLE = 5;
    /** Hoechstens so viele Vorschlaege insgesamt, egal wie viele Rollen angefragt sind. */
    public static final int MAX_ITEMS = 15;
    /** Schwelle aus Spec §7 Regel 6: darunter ist eine Karte "fast niemandes" Wahl, darueber nur die
     *  schwaechste eines insgesamt gut gespielten Feldes - beides verdient einen anderen Satz. */
    private static final double LOW_SHARE_THRESHOLD = 0.10;

    /** Kartentext, den Kevin liest, wenn er den Vorschlag statt einer anderen Karte einbauen will;
     *  {@code reason} nennt nur, was tatsaechlich geprueft wurde (Spec §7). */
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
     * @param page   EDHREC-Kandidaten des Commanders, oder {@code null}, wenn EDHREC den Commander nicht
     *               fuehrt - dann kommen die Kandidaten aus Forges Kartendatenbank (Result.source() "db")
     */
    public static Result of(Deck deck, List<String> roles, Integer bracket, Edhrec.Page page) {
        if (roles.isEmpty()) {
            // Ohne Luecke gibt es nichts zu erledigen - eine leere Rollenliste ist kein Fehler.
            return new Result(page == null ? "db" : "edhrec", "Keine Lücke gefunden.", List.of());
        }
        // Entdupliziert, sonst wuerde z.B. List.of("ramp", "ramp") die Kandidatenliste zweimal durchlaufen
        // und bis zu 2*PER_ROLE statt hoechstens PER_ROLE Vorschlaege fuer dieselbe Rolle liefern.
        // LinkedHashSet haelt dabei die angefragte Reihenfolge fest (wichtig fuer "erste Rolle gewinnt" unten).
        List<String> requestedRoles = List.copyOf(new LinkedHashSet<>(roles));

        ColorSet identity = commanderIdentity(deck);
        Set<String> inDeck = namesInDeck(deck);
        List<PaperCard> mainCards = mainNonLandCards(deck);
        boolean dropGameChangers = bracket != null && (bracket == 1 || bracket == 2);
        boolean noteGameChangers = bracket != null && bracket == 3;

        // Verhindert, dass dieselbe Karte zweimal auftaucht, wenn sie mehrere angefragte Rollen trifft.
        // Eine Karte gilt erst dann als "vergeben" (landet in chosen), wenn sie fuer eine Rolle tatsaechlich
        // in die Top PER_ROLE aufgenommen wurde - trifft sie eine frueher angefragte Rolle, schafft es dort
        // aber nicht in die Top 5 (weil andere Kandidaten einen hoeheren share/niedrigeren cmc haben), kann
        // sie bei einer spaeter angefragten Rolle noch erscheinen. Das ist eine bewusste Lesart von "die
        // zuerst angefragte Rolle gewinnt": gemeint ist die erste Rolle, in der die Karte tatsaechlich
        // vorgeschlagen wird, nicht die erste Rolle, die sie ueberhaupt trifft. Gilt genauso im Rueckfall
        // ohne EDHREC weiter unten.
        Set<String> chosen = new LinkedHashSet<>();
        // Merkzettel der schon als Schnitt vergebenen Kartennamen (kleingeschrieben) - dieselbe Karte darf
        // nicht zwei verschiedenen Vorschlaegen als Schnittkandidat dienen (Spec §7).
        Set<String> cutChosen = new LinkedHashSet<>();
        List<Item> items = new ArrayList<>();
        boolean anyGameChangerIncluded = false;

        if (page != null) {
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
                    anyGameChangerIncluded |= candidate.gameChanger();
                    addWithCut(candidate, mainCards, page, chosen, cutChosen, items);
                    takenForRole++;
                    if (items.size() >= MAX_ITEMS) {
                        break;
                    }
                }
            }
        } else {
            // Rueckfall ohne EDHREC (Spec §7): Kandidaten kommen aus der gesamten Kartendatenbank - der
            // teuerste Pfad dieser Klasse. Genau ein Durchlauf ueber getUniqueCards() je Aufruf, unabhaengig
            // davon, wie viele Rollen angefragt sind - die Zuordnung zu Rollen passiert dabei gleich mit,
            // statt fuer jede Rolle noch einmal ueber die ganze Datenbank zu laufen.
            Map<String, List<Item>> byRole = new LinkedHashMap<>();
            for (String role : requestedRoles) {
                byRole.put(role, new ArrayList<>());
            }
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                String lname = pc.getName().toLowerCase(Locale.ROOT);
                if (inDeck.contains(lname)) {
                    continue;
                }
                CardRules rules = pc.getRules();
                if (rules == null || rules.getType().isLand()) {
                    continue;
                }
                if (!rules.getColorIdentity().hasNoColorsExcept(identity)) {
                    continue;
                }
                if (!DeckFormat.Commander.isLegalCard(pc)) {
                    continue;
                }
                List<String> cats = DeckAnalysis.categoriesOf(rules);
                for (String role : requestedRoles) {
                    if (cats.contains(role)) {
                        byRole.get(role).add(new Item(pc.getName(), role, rules.getManaCost().getShortString(),
                                rules.getManaCost().getCMC(), null, false, pc.getImageKey(false),
                                rules.getOracleText(), null));
                    }
                }
            }
            for (String role : requestedRoles) {
                if (items.size() >= MAX_ITEMS) {
                    break;
                }
                List<Item> candidates = new ArrayList<>();
                for (Item i : byRole.get(role)) {
                    if (!chosen.contains(i.name().toLowerCase(Locale.ROOT))) {
                        candidates.add(i);
                    }
                }
                // Ohne EDHREC gibt es keinen Anteil - Manabetrag aufsteigend, dann Name.
                candidates.sort(Comparator.comparingInt(Item::cmc).thenComparing(Item::name));

                int takenForRole = 0;
                for (Item candidate : candidates) {
                    if (takenForRole >= PER_ROLE) {
                        break;
                    }
                    addWithCut(candidate, mainCards, null, chosen, cutChosen, items);
                    takenForRole++;
                    if (items.size() >= MAX_ITEMS) {
                        break;
                    }
                }
            }
        }

        String source = page == null ? "db" : "edhrec";
        String note;
        if (page == null) {
            note = "Ohne EDHREC-Daten: Vorschläge nur aus der Kartendatenbank.";
        } else {
            note = (noteGameChangers && anyGameChangerIncluded)
                    ? "Game Changer sind in Bracket 3 auf drei Karten begrenzt." : null;
        }
        return new Result(source, note, List.copyOf(items));
    }

    /** Haengt an einen Vorschlagskandidaten seinen Schnittkandidaten und schreibt beide Merkzettel fort. */
    private static void addWithCut(Item candidate, List<PaperCard> mainCards, Edhrec.Page page,
                                    Set<String> chosen, Set<String> cutChosen, List<Item> items) {
        Cut cut = cutCandidate(mainCards, candidate.role(), page, cutChosen);
        items.add(new Item(candidate.name(), candidate.role(), candidate.manaCost(), candidate.cmc(),
                candidate.share(), candidate.gameChanger(), candidate.imageKey(), candidate.text(), cut));
        chosen.add(candidate.name().toLowerCase(Locale.ROOT));
        if (cut != null) {
            cutChosen.add(cut.name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Schnittkandidat fuer einen Vorschlag der Rolle {@code role} (Spec §7, Regeln 1-4): zuerst Karten der
     * Hauptsektion mit derselben Rolle, sonst Karten, die keine der neun Rollen treffen, jeweils ohne
     * Laender/Commander (schon durch {@code mainCards}) und ohne bereits vergebene Karten.
     */
    private static Cut cutCandidate(List<PaperCard> mainCards, String role, Edhrec.Page page,
                                     Set<String> cutChosen) {
        List<PaperCard> sameRole = new ArrayList<>();
        List<PaperCard> noRole = new ArrayList<>();
        for (PaperCard pc : mainCards) {
            if (cutChosen.contains(pc.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> cats = DeckAnalysis.categoriesOf(pc.getRules());
            if (cats.contains(role)) {
                sameRole.add(pc);
            } else if (cats.isEmpty()) {
                noRole.add(pc);
            }
        }
        Cut cut = pickCut(sameRole, page, false);
        if (cut != null) {
            return cut;
        }
        return pickCut(noRole, page, true);
    }

    /**
     * Waehlt aus {@code candidates} den Schnitt (Spec §7 Regel 2): mit EDHREC-Seite zuerst Karten, die die
     * Seite gar nicht fuehrt, dann aufsteigender share, dann absteigende Manakosten, dann Name; ohne Seite
     * absteigende Manakosten, dann Name. {@code noRoleCase} steuert nur die Begruendung (Regel 3).
     */
    private static Cut pickCut(List<PaperCard> candidates, Edhrec.Page page, boolean noRoleCase) {
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Edhrec.Card> byName = page == null ? Map.of() : page.byName();
        List<PaperCard> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            if (page != null) {
                boolean aListed = byName.containsKey(a.getName().toLowerCase(Locale.ROOT));
                boolean bListed = byName.containsKey(b.getName().toLowerCase(Locale.ROOT));
                int byListed = Boolean.compare(aListed, bListed);   // nicht gefuehrt (false) zuerst
                if (byListed != 0) {
                    return byListed;
                }
                double aShare = aListed ? byName.get(a.getName().toLowerCase(Locale.ROOT)).share() : 0.0;
                double bShare = bListed ? byName.get(b.getName().toLowerCase(Locale.ROOT)).share() : 0.0;
                int byShare = Double.compare(aShare, bShare);
                if (byShare != 0) {
                    return byShare;
                }
            }
            int byCmc = Integer.compare(b.getRules().getManaCost().getCMC(), a.getRules().getManaCost().getCMC());
            if (byCmc != 0) {
                return byCmc;
            }
            return a.getName().compareTo(b.getName());
        });
        PaperCard winner = sorted.get(0);
        return new Cut(winner.getName(), cutReason(winner, candidates, page, noRoleCase));
    }

    /** Begruendung aus nur Geprueftem zusammengesetzt (Spec §7 Regel 6). Die Karte trifft entweder keine
     *  Rolle (dann steht das allein - "teuerste Karte dieser Rolle" waere unsinnig ohne Rolle) oder sie
     *  trifft dieselbe Rolle wie der Vorschlag; dort haengt der Satz an der tatsaechlichen Zahl: unter der
     *  Schwelle spielt sie fast niemand, darueber ist sie nur die schwaechste eines starken Feldes - ein
     *  hoher Anteil darf nie als "fast niemand" erscheinen. */
    private static String cutReason(PaperCard winner, List<PaperCard> candidates, Edhrec.Page page,
                                     boolean noRoleCase) {
        if (noRoleCase) {
            return "trifft keine der neun Rollen";
        }
        List<String> clauses = new ArrayList<>();
        if (page != null) {
            Edhrec.Card ec = page.byName().get(winner.getName().toLowerCase(Locale.ROOT));
            if (ec == null) {
                clauses.add("führt EDHREC für diesen Commander gar nicht");
            } else {
                long percent = Math.round(ec.share() * 100);
                clauses.add(ec.share() < LOW_SHARE_THRESHOLD
                        ? "spielt in vergleichbaren Decks fast niemand (" + percent + " %)"
                        : "hat mit " + percent + " % den niedrigsten Anteil der Karten dieser Rolle in deinem Deck");
            }
        }
        int maxCmc = candidates.stream().mapToInt(pc -> pc.getRules().getManaCost().getCMC())
                .max().orElse(Integer.MIN_VALUE);
        if (winner.getRules().getManaCost().getCMC() == maxCmc) {
            clauses.add("ist mit " + winner.getRules().getManaCost().getShortString()
                    + " die teuerste Karte dieser Rolle");
        }
        return String.join(" und ", clauses);
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

    /** Nicht-Land-Karten der Hauptsektion - Grundlage der Schnittkandidaten (Spec §7 Regel 1: ohne
     *  Laender, ohne Commander - Letzteres ist schon dadurch erfuellt, dass nur DeckSection.Main gelesen wird). */
    private static List<PaperCard> mainNonLandCards(Deck deck) {
        List<PaperCard> out = new ArrayList<>();
        var pool = deck.get(DeckSection.Main);
        if (pool == null) {
            return out;
        }
        for (Map.Entry<PaperCard, Integer> e : pool) {
            PaperCard pc = e.getKey();
            CardRules rules = pc.getRules();
            if (rules == null || rules.getType().isLand()) {
                continue;
            }
            out.add(pc);
        }
        return out;
    }
}
