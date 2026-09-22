package mtgplayer.stats;

import java.util.List;

/**
 * Eine gespielte Partie, wie sie {@link MatchStore} speichert. id: {@code Instant.now()} (ISO, Sekunden)
 * + "-" + 4 Hex-Zeichen aus {@code ThreadLocalRandom}, z.B. {@code "2026-09-22T19:31:02Z-7f3a"}. source:
 * {@code "live" | "spectate" | "sparring"}. reason: Forges Spielende-Grund (z.B.
 * {@code "AllOpponentsLost"}). counted/excludeReason: nicht gewertete Partien (zu kurz, aufgegeben,
 * Absturz) tragen {@code counted == false} und einen Grund; siehe {@link #withCounted}.
 */
public record MatchRecord(String id, String startedAt, String endedAt, long durationMs, String source,
                          int turns, String reason, boolean draw, boolean counted, String excludeReason,
                          List<Seat> seats) {

    /** Neuer Datensatz mit geaendertem {@code counted}/{@code excludeReason}, sonst unveraendert. */
    public MatchRecord withCounted(boolean counted, String excludeReason) {
        return new MatchRecord(id, startedAt, endedAt, durationMs, source, turns, reason, draw,
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
