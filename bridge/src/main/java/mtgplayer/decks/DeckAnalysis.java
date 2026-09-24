package mtgplayer.decks;

import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Was in einem Deck steckt - rein aus Forges Kartendatenbank (Typen, Manakosten, Orakeltext), ohne
 * eine einzige gespielte Partie. Damit kann das Board Saetze wie "du verlierst oft an Massenentfernung
 * und hast 0 Karten, die davor schuetzen" mit dem Deckinhalt verbinden.
 *
 * <p><b>Grenzen - bitte ernst nehmen.</b> Die Einordnung in Kategorien ist <em>Textmustererkennung,
 * keine Semantik</em>. Es wird im kleingeschriebenen Orakeltext nach Wendungen gesucht; die Zahlen sind
 * Groessenordnungen, keine Wahrheit. Bekannte Schwaechen:</p>
 * <ul>
 *   <li>Umschreibungen werden nicht erkannt: "draw cards equal to ..." zaehlt nicht als {@code draw},
 *       "creatures you control gain reach" nicht als {@code flyerDefense}.</li>
 *   <li>Wer Mana erzeugt, ist {@code ramp} - auch ein Ritual oder eine Karte, die dem Gegner Mana gibt.</li>
 *   <li>Wer die Bibliothek durchsucht, ist {@code tutors} - auch Landsuche. Deshalb zaehlt Cultivate
 *       absichtlich doppelt ({@code ramp} und {@code tutors}).</li>
 *   <li>Fliegende Kreaturen zaehlen als {@code flyerDefense}, obwohl sie nur blocken <em>koennen</em> -
 *       so gewollt: sie koennen es.</li>
 *   <li>Zweiseitige Karten: der Orakeltext <em>aller</em> Seiten wird gelesen
 *       ({@link CardRules#getAllFaces()}), Manakosten und Kurve stammen aber von der Vorderseite. Ein
 *       Modal-DFC mit Land-Rueckseite (Agadeem's Awakening) zaehlt als <em>Land</em> - siehe
 *       {@code landType} - und taucht deshalb nicht in der Kurve auf; ein Pathway-Land liefert die
 *       Farben <em>beider</em> Seiten als Quellen, obwohl es nur eine davon sein kann.</li>
 *   <li>Farbquellen: nur Laender mit Grundlandtyp oder "Add"-Text. Fetchlands (Evolving Wilds) und
 *       Mana-Artefakte (Sol Ring, Signets) sind <em>keine</em> Farbquellen in dieser Zaehlung - die
 *       stecken in {@code ramp}.</li>
 * </ul>
 *
 * <p>Erinnerungstext (der Klammertext hinter Schluesselwoertern) wird vor der Musterpruefung
 * <em>weggeschnitten</em>, siehe {@link #text}: sonst waere jede Karte mit Cycling
 * ("Cycling {2} (..., Discard this card: Draw a card.)") ein Kartenzieher.</p>
 *
 * @param cards       Karten in Haupt- und Kommandeursektion (Commander-Deck: 100)
 * @param lands       davon Laender (inklusive Modal-DFC mit Land-Rueckseite)
 * @param basics      davon Standardlaender
 * @param avgCmc      Ø Manabetrag der Nicht-Laender, auf eine Nachkommastelle gerundet (wie
 *                    {@link forge.ai.AiDeckStatistics#averageCMC})
 * @param curve       Nicht-Laender je Manabetrag, Schluessel "0".."6" und "7+" (immer alle vorhanden)
 * @param sources     Farbquellen je Farbe ("W","U","B","R","G") plus "any"; ein Land kann mehrfach zaehlen
 * @param identity    Farbidentitaet der Kommandeure in WUBRG-Reihenfolge (leer ohne Kommandeur)
 * @param categories  Treffer je Regel aus {@link #rules()}, in deren Reihenfolge (immer alle vorhanden)
 * @param unclassified <em>Nicht-Laender</em>, die keine einzige Regel getroffen haben. Laender haben mit
 *                    {@code lands}/{@code basics}/{@code sources} ihren eigenen Ausweis; zaehlte man sie
 *                    hier mit, bestuende die Zahl bei einem Commander-Deck zur Haelfte aus Laendern und
 *                    saegte nichts mehr aus
 */
public record DeckAnalysis(int cards, int lands, int basics, double avgCmc,
                           Map<String, Integer> curve, Map<String, Integer> sources,
                           List<String> identity, Map<String, Integer> categories, int unclassified) {

    /** Eine Einordnungsregel: Name (Schluessel in {@link #categories()}) und Praedikat auf den Kartenregeln. */
    public record Rule(String name, Predicate<CardRules> test) { }

    private static final String[] COLORS = { "W", "U", "B", "R", "G" };
    /** Grundlandtyp je Farbe, gleiche Reihenfolge wie {@link #COLORS}. */
    private static final String[] BASIC_TYPES = { "Plains", "Island", "Swamp", "Mountain", "Forest" };

    // --- Muster der Regeln. Alle greifen auf kleingeschriebenen Orakeltext zu (siehe text(CardRules)).
    // "[^.\\n]*" heisst durchweg: innerhalb eines Satzes bleiben - weder ueber einen Punkt noch ueber
    // einen Zeilenumbruch hinweg duerfen zwei Halbsaetze zu einem Treffer zusammenfinden.
    /** "{T}: Add {G}" und "Add one mana of any color" - "adds" (der Gegner) faellt durch die Wortgrenze raus. */
    private static final Pattern PRODUCES_MANA = Pattern.compile("\\badd\\b[^.\\n]*(\\{|mana)");
    /** "search your library for up to two basic land cards" - Landsuche, ohne Satzgrenze zu ueberschreiten. */
    private static final Pattern LAND_SEARCH = Pattern.compile("search your library for [^.\\n]*\\bland\\b");
    /** "draw a card", "you draw two cards" - nur die Befehlsform: "each opponent draws" hat ein "s". */
    private static final Pattern DRAW = Pattern.compile(
            "\\bdraw (a|one|two|three|four|five|six|seven|x|\\d+) cards?\\b");
    private static final Pattern DESTROY_TARGET = Pattern.compile(
            "(destroy|exile) target [^.\\n]*\\b(creature|permanent|artifact|enchantment|planeswalker|battle|land)s?\\b");
    private static final Pattern DAMAGE_TARGET = Pattern.compile(
            "damage to (any target|target creature)");
    /**
     * "Destroy all creatures", "Exile all nonland permanents" - das Substantiv muss mit, sonst ist
     * "exile all cards from target player's graveyard" (Bojuka Bog) eine Massenentfernung.
     */
    private static final Pattern DESTROY_ALL = Pattern.compile(
            "(destroy|exile) all [^.\\n]*\\b(creature|permanent|artifact|enchantment|planeswalker|battle|land|token)s?\\b");
    /** "sacrifices all other creatures they control" (Slaughter the Strong). */
    private static final Pattern SACRIFICE_ALL = Pattern.compile("sacrifices? all");
    /**
     * "deals 13 damage to each creature" (Blasphemous Act), auch "... to each creature and each
     * planeswalker". Fog ("prevent all combat damage") faellt raus: kein Schaden auf jede Kreatur.
     */
    private static final Pattern DAMAGE_EACH = Pattern.compile("damage to each (creature|other creature)");
    private static final Pattern COUNTER_SPELL = Pattern.compile("counter target [^.\\n]*spell");
    private static final Pattern RETURN_TO_PLAY = Pattern.compile(
            "return [^.\\n]*from (your|a|all|their|target player's) [^.\\n]*graveyards? to the battlefield");
    private static final Pattern FROM_GRAVEYARD = Pattern.compile(
            "from (your|a|all|their|target player's|each player's) [^.\\n]*graveyards?");
    private static final Pattern RECURSION_VERB = Pattern.compile("\\b(return|returns|put|puts|cast|casts|play)\\b");
    /** Erinnerungstext: alles in runden Klammern (nicht geschachtelt, so schreibt Forge es). */
    private static final Pattern REMINDER = Pattern.compile("\\([^()]*\\)");

    /**
     * Die Regeln in der Reihenfolge, in der sie im Board erscheinen. Jede Regel steht fuer sich und ist
     * einzeln testbar (siehe {@code DeckAnalysisTest}); eine Karte darf mehrere Regeln treffen.
     */
    private static final List<Rule> RULES = List.of(
            // Nicht-Land, das Mana erzeugt oder ein Land aus der Bibliothek holt.
            new Rule("ramp", c -> landType(c) == null
                    && (PRODUCES_MANA.matcher(text(c)).find() || LAND_SEARCH.matcher(text(c)).find())),
            // Kartenzug fuer den Beherrscher - "each opponent draws a card" faellt durch die Verbform raus.
            new Rule("draw", c -> DRAW.matcher(text(c)).find()),
            // Punktuelle Entfernung: zerstoeren/exilieren eines Ziels oder Schaden auf eine Kreatur.
            new Rule("removal", c -> DESTROY_TARGET.matcher(text(c)).find()
                    || DAMAGE_TARGET.matcher(text(c)).find()),
            // Massenentfernung. Satzweise geprueft, sonst zaehlt jede Karte mit "each creature ..." in
            // Satz 1 und "sacrifice ..." in Satz 3 (z. B. Felothar the Steadfast) als Wipe.
            new Rule("wipes", c -> anySentence(c, s -> DESTROY_ALL.matcher(s).find()
                    || SACRIFICE_ALL.matcher(s).find()
                    || DAMAGE_EACH.matcher(s).find()
                    || (s.contains("each creature") && (s.contains("destroy") || s.contains("sacrific"))))),
            new Rule("counters", c -> COUNTER_SPELL.matcher(text(c)).find()),
            // Eigene Karten, die Flieger aufhalten koennen.
            new Rule("flyerDefense", c -> hasKeyword(c, "Flying") || hasKeyword(c, "Reach")
                    || text(c).contains("can block creatures with flying")),
            // Schutz gegen Massenentfernung bzw. Wiederaufbau danach. "They can't be regenerated"
            // (Wrath of God) ist das Gegenteil davon und darf nicht zaehlen.
            new Rule("wipeProtection", c -> text(c).contains("indestructible") || text(c).contains("hexproof")
                    || anySentence(c, s -> s.contains("regenerate") && !s.contains("can't be regenerated"))
                    || RETURN_TO_PLAY.matcher(text(c)).find()),
            // Karten zurueck aus dem Friedhof - "aus dem Friedhof" und ein holendes Verb im selben Satz;
            // "put ... into your graveyard" (Mill) und "exile target card from a graveyard" fallen raus.
            new Rule("recursion", c -> anySentence(c, s -> FROM_GRAVEYARD.matcher(s).find()
                    && RECURSION_VERB.matcher(s).find())),
            new Rule("tutors", c -> text(c).contains("search your library for")));

    /** Die Einordnungsregeln, in Board-Reihenfolge. */
    public static List<Rule> rules() {
        return RULES;
    }

    /** Alle Kategorien, die diese Karte trifft (leer = {@code unclassified}). */
    public static List<String> categoriesOf(CardRules card) {
        List<String> out = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.test().test(card)) {
                out.add(r.name());
            }
        }
        return List.copyOf(out);
    }

    /** Analyse ueber Haupt- und Kommandeursektion; andere Sektionen (Sideboard, ...) bleiben aussen vor. */
    public static DeckAnalysis of(Deck deck) {
        Map<String, Integer> curve = new LinkedHashMap<>();
        for (int i = 0; i <= 6; i++) {
            curve.put(String.valueOf(i), 0);
        }
        curve.put("7+", 0);
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (String c : COLORS) {
            sources.put(c, 0);
        }
        sources.put("any", 0);
        Map<String, Integer> categories = new LinkedHashMap<>();
        for (Rule r : RULES) {
            categories.put(r.name(), 0);
        }

        int cards = 0;
        int lands = 0;
        int basics = 0;
        int nonLands = 0;
        int totalCmc = 0;
        int unclassified = 0;
        for (Map.Entry<PaperCard, Integer> entry : counted(deck)) {
            CardRules rules = entry.getKey().getRules();
            int n = entry.getValue();
            if (rules == null) {
                continue;   // Forge kennt die Karte nicht (unsupported) - nicht mitzaehlen statt abstuerzen
            }
            cards += n;
            CardType land = landType(rules);
            if (land != null) {
                lands += n;
                if (land.isBasicLand()) {
                    basics += n;
                }
                for (String color : colorSources(rules, land)) {
                    sources.merge(color, n, Integer::sum);
                }
            } else {
                int cmc = rules.getManaCost().getCMC();
                nonLands += n;
                totalCmc += cmc * n;
                curve.merge(cmc >= 7 ? "7+" : String.valueOf(cmc), n, Integer::sum);
            }
            List<String> hits = categoriesOf(rules);
            if (hits.isEmpty() && land == null) {
                unclassified += n;   // Laender haben ihren eigenen Ausweis, siehe Record-Javadoc
            }
            for (String hit : hits) {
                categories.merge(hit, n, Integer::sum);
            }
        }

        double avgCmc = nonLands == 0 ? 0 : Math.round(totalCmc * 10.0 / nonLands) / 10.0;
        // Collections.unmodifiableMap statt Map.copyOf: Map.copyOf gibt die Reihenfolge preis, und
        // Kurve/Farbquellen/Kategorien sollen im JSON (und im Board) in ihrer Reihenfolge stehen.
        return new DeckAnalysis(cards, lands, basics, avgCmc,
                Collections.unmodifiableMap(curve), Collections.unmodifiableMap(sources),
                identity(deck), Collections.unmodifiableMap(categories), unclassified);
    }

    /** Haupt- und Kommandeursektion als {@code (Karte, Anzahl)} - wie {@link forge.ai.AiDeckStatistics#fromDeck}. */
    private static List<Map.Entry<PaperCard, Integer>> counted(Deck deck) {
        List<Map.Entry<PaperCard, Integer>> out = new ArrayList<>();
        for (DeckSection section : List.of(DeckSection.Main, DeckSection.Commander)) {
            CardPool pool = deck.get(section);
            if (pool == null) {
                continue;
            }
            for (Map.Entry<PaperCard, Integer> e : pool) {
                out.add(e);
            }
        }
        return out;
    }

    /** Farbidentitaet der Kommandeure, vereinigt, in WUBRG-Reihenfolge. */
    private static List<String> identity(Deck deck) {
        byte mask = 0;
        for (PaperCard pc : deck.getCommanders()) {
            if (pc.getRules() != null) {
                mask |= pc.getRules().getColorIdentity().getColor();
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < COLORS.length; i++) {
            if ((mask & MagicColor.WUBRG[i]) != 0) {
                out.add(COLORS[i]);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Die Landseite dieser Karte, sonst null: die Vorderseite, wenn sie ein Land ist, sonst die
     * Rueckseite eines Modal-DFC ("Agadeem's Awakening // Agadeem, the Undercrypt"). So eine Karte wird
     * als Land gespielt, also zaehlt sie hier als Land - und taucht dafuer nicht in der Kurve auf.
     * Damit weicht {@code lands()} bewusst von {@link forge.ai.AiDeckStatistics#numLands} ab, das nur
     * die Vorderseite kennt.
     */
    private static CardType landType(CardRules rules) {
        if (rules.getType().isLand()) {
            return rules.getType();
        }
        if (rules.getSplitType() == CardSplitType.Modal) {
            ICardFace back = rules.getOtherPart();
            if (back != null && back.getType() != null && back.getType().isLand()) {
                return back.getType();
            }
        }
        return null;
    }

    /**
     * Farben, die dieses Land liefert: Grundlandtyp (Plains, ...) der Landseite oder ein Manasymbol
     * hinter "add" im selben Satz ("{T}: Add {U} or {R}" zaehlt beide). "any color"/"any one color"
     * wird zu "any". Ein Land zaehlt je Farbe nur einmal, auch wenn Typ und Text dasselbe sagen.
     * Hier zaehlt der <em>rohe</em> Orakeltext: bei einem Grundland steht die Manafaehigkeit komplett
     * im Klammertext ("({T}: Add {U}.)"), den {@link #text} wegschneidet.
     */
    private static Set<String> colorSources(CardRules rules, CardType type) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < COLORS.length; i++) {
            if (type.hasSubtype(BASIC_TYPES[i])) {
                out.add(COLORS[i]);
            }
        }
        for (String sentence : sentences(rawText(rules))) {
            int at = sentence.indexOf("add ");
            if (at < 0) {
                continue;
            }
            String tail = sentence.substring(at);
            for (int i = 0; i < COLORS.length; i++) {
                if (tail.contains("{" + COLORS[i].toLowerCase(Locale.ROOT) + "}")) {
                    out.add(COLORS[i]);
                }
            }
            if (tail.contains("any color") || tail.contains("any one color") || tail.contains("mana of any type")) {
                out.add("any");
            }
        }
        return out;
    }

    private static boolean hasKeyword(CardRules rules, String keyword) {
        if (rules.hasKeyword(keyword)) {
            return true;
        }
        for (ICardFace face : rules.getAllFaces()) {
            if (face != null && rules.hasStartOfKeyword(keyword, face)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anySentence(CardRules rules, Predicate<String> test) {
        for (String s : sentences(text(rules))) {
            if (test.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** Saetze (bzw. Zeilen) des Orakeltexts - Muster sollen nicht ueber Satzgrenzen hinweg zusammenfinden. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("[.\n]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Orakeltext fuer die Regeln: wie {@link #rawText}, aber <b>ohne Klammerausdruecke</b>. Forge schreibt
     * Erinnerungstext (die Erklaerung eines Schluesselworts) genau so - "Cycling {2} ({2}, Discard this
     * card: Draw a card.)" waere sonst Kartenzug, "Reach (This creature can block creatures with flying.)"
     * doppelt gezaehlt. Geschnitten werden <em>alle</em> Klammern, nicht nur die hinter Schluesselwoertern:
     * Forge markiert Erinnerungstext nicht eigens, und was in Klammern steht, ist im Regeltext immer nur
     * eine Wiederholung dessen, was das Schluesselwort ohnehin sagt. Was dabei verloren geht - die
     * Manafaehigkeit eines Grundlands steht komplett in Klammern ("({T}: Add {U}.)") - holt die
     * Farbquellenzaehlung ueber {@link #rawText} und den Grundlandtyp wieder herein.
     */
    private static String text(CardRules rules) {
        return REMINDER.matcher(rawText(rules)).replaceAll(" ");
    }

    /**
     * Orakeltext aller Seiten, kleingeschrieben und mit echten Zeilenumbruechen (Forge speichert "\n"
     * als zwei Zeichen im Kartenskript). Wird je Regel neu gebaut - bei 100 Karten mal 9 Regeln ist das
     * nicht der Rede wert und erspart einen Cache mit Lebensdauerfragen.
     */
    private static String rawText(CardRules rules) {
        StringBuilder sb = new StringBuilder();
        for (ICardFace face : rules.getAllFaces()) {
            if (face == null || face.getOracleText() == null) {
                continue;
            }
            sb.append(face.getOracleText()).append('\n');
        }
        return sb.toString()
                .replace("\\n", "\n")
                .replace("\r", "\n")
                .replace('’', '\'')
                .toLowerCase(Locale.ROOT);
    }
}
