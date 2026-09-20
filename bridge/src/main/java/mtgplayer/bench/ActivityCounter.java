package mtgplayer.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Zaehlt je Sitz gespielte Laender und gewirkte Zauber aus Forges Logzeilen und meldet "Nichtstun"-Sitze
 * (docs/bench/2026-09-20-stufe-2-nichtstun.md): mindestens {@link #MIN_LANDS} Laender gespielt, aber hoechstens
 * {@link #MAX_SPELLS} Zauber gewirkt - der Sitz hatte Mana, aber nichts Bezahlbares (Farb- oder Kurvenmangel).
 * Zeilenformat aus {@code GameEventLandPlayed} ("&lt;Spieler&gt; played &lt;Land&gt;") und
 * {@code GameEventSpellAbilityCast} ("&lt;Spieler&gt; cast &lt;Zauber&gt;"; Faehigkeiten heissen dort
 * "activated"/"triggered"). Nur der Zeilenanfang zaehlt, Kartentexte mit " cast " mitten in der Zeile nicht.
 */
final class ActivityCounter {
    static final int MIN_LANDS = 5;
    static final int MAX_SPELLS = 2;
    private static final String PLAYED = " played ";
    private static final String CAST = " cast ";

    private final Map<String, Integer> lands = new HashMap<>();
    private final Map<String, Integer> spells = new HashMap<>();

    void see(String line) {
        count(line, PLAYED, lands);
        count(line, CAST, spells);
    }

    private static void count(String line, String verb, Map<String, Integer> into) {
        int at = line.indexOf(verb);
        // Sitznamen sind kurz ("A", "B"); ein Kartenname mit Leerzeichen vor dem Verb ist kein Sitz.
        if (at <= 0 || line.indexOf(' ') < at) {
            return;
        }
        into.merge(line.substring(0, at), 1, Integer::sum);
    }

    /** Sitze mit Nichtstun, alphabetisch verkettet ("", "A", "B", "AB"). */
    String fewSpells() {
        TreeSet<String> seats = new TreeSet<>();
        for (Map.Entry<String, Integer> e : lands.entrySet()) {
            if (e.getValue() >= MIN_LANDS && spells.getOrDefault(e.getKey(), 0) <= MAX_SPELLS) {
                seats.add(e.getKey());
            }
        }
        return String.join("", seats);
    }
}
