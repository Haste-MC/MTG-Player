package mtgplayer.stats;

import java.util.List;

/**
 * Eine gespielte Partie, wie sie {@link MatchStore} speichert. v: Formatversion des Datensatzes (aktuell
 * {@link #VERSION}), damit eine spaetere Aenderung der Definition an alten Datensaetzen erkennbar bleibt;
 * Datensaetze ohne Feld (vor Einfuehrung geschrieben) gelten als v1. id: Ende der Partie (ISO, Millisekunden)
 * + "-" + 6 Hex-Zeichen, z.B. {@code "2026-09-22T19:31:02.418Z-7f3a01"}. source:
 * {@code "live" | "spectate" | "sparring"}. reason: Forges Spielende-Grund (z.B.
 * {@code "AllOpponentsLost"}). counted/excludeReason: nicht gewertete Partien (zu kurz, aufgegeben,
 * abgebrochen, Absturz) tragen {@code counted == false} und einen Grund; siehe {@link #withCounted}.
 */
public record MatchRecord(int v, String id, String startedAt, String endedAt, long durationMs, String source,
                          int turns, String reason, boolean draw, boolean counted, String excludeReason,
                          List<Seat> seats) {

    /** Aktuelle Formatversion; steht als erstes Feld ({@code "v"}) in jedem geschriebenen Datensatz. */
    public static final int VERSION = 1;

    /**
     * Ein fehlendes {@code v} (Jackson liefert dann 0) heisst v1: so liest {@link MatchStore} die vor
     * Einfuehrung des Feldes geschriebenen Datensaetze ohne Sonderfall.
     */
    public MatchRecord {
        if (v <= 0) {
            v = VERSION;
        }
    }

    /** Neuer Datensatz in der aktuellen Version (alles ausser {@code v}). */
    public MatchRecord(String id, String startedAt, String endedAt, long durationMs, String source,
                       int turns, String reason, boolean draw, boolean counted, String excludeReason,
                       List<Seat> seats) {
        this(VERSION, id, startedAt, endedAt, durationMs, source, turns, reason, draw, counted,
                excludeReason, seats);
    }

    /** Neuer Datensatz mit geaendertem {@code counted}/{@code excludeReason}, sonst unveraendert. */
    public MatchRecord withCounted(boolean counted, String excludeReason) {
        return new MatchRecord(v, id, startedAt, endedAt, durationMs, source, turns, reason, draw,
                counted, excludeReason, seats);
    }

    /**
     * Ergebnis und Kennzahlen eines Sitzes ueber die gesamte Partie. ai: {@code null} bei einem
     * menschlichen Sitz. eliminatedTurn/firstMissedLandDrop/firstCommanderTurn: {@code null}, wenn das
     * Ereignis nicht eintrat (Sitz ueberlebte / keine verpasste Landabgabe / kein Commander-Cast).
     */
    public record Seat(String name, String deck, boolean human, Ai ai, boolean winner, String lossReason,
                       Integer eliminatedTurn, int mulligans, int lands, List<Integer> landsByTurn,
                       int missedLandDrops, Integer firstMissedLandDrop, int spells, int spellMana,
                       int commanderCasts, int commanderTax, Integer firstCommanderTurn,
                       int damageDealt, int damageTaken,
                       int combatDamageTaken, int lifeEnd, int poisonEnd) { }

    /** KI-Konfiguration eines Sitzes (mode/profile, siehe {@code mtgplayer.ai.AiConfig}). */
    public record Ai(String mode, String profile) { }
}
