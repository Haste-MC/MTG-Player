package mtgplayer.stats;

import java.util.List;

/**
 * Eine gespielte Partie, wie sie {@link MatchStore} speichert. v: Formatversion des Datensatzes (aktuell
 * {@link #VERSION}), damit eine spaetere Aenderung der Definition an alten Datensaetzen erkennbar bleibt;
 * Datensaetze ohne Feld (vor Einfuehrung geschrieben) gelten als v1. id: Ende der Partie (ISO, Millisekunden)
 * + "-" + 6 Hex-Zeichen, z.B. {@code "2026-09-22T19:31:02.418Z-7f3a01"}. source:
 * {@code "live" | "spectate" | "sparring"}. aiTimeout: Bedenkzeit je KI-Entscheidung in Sekunden,
 * {@code null} wenn unbekannt (Datensaetze vor Einfuehrung des Feldes). reason: Forges Spielende-Grund (z.B.
 * {@code "AllOpponentsLost"}). counted/excludeReason: nicht gewertete Partien (zu kurz, aufgegeben,
 * abgebrochen, Absturz) tragen {@code counted == false} und einen Grund; siehe {@link #withCounted}.
 */
public record MatchRecord(int v, String id, String startedAt, String endedAt, long durationMs, String source,
                          Integer aiTimeout, int turns, String reason, boolean draw, boolean counted,
                          String excludeReason, List<Seat> seats) {

    /**
     * Aktuelle Formatversion; steht als erstes Feld ({@code "v"}) in jedem geschriebenen Datensatz.
     * v2 (Runde B) hat die Vorfall-Kennzahlen je Sitz dazubekommen - rein additiv, ein v1-Datensatz
     * bleibt lesbar. Die Auswertung muss die neuen Felder in einem v1-Datensatz als "keine Daten"
     * behandeln, nicht als "0" - dort wurde nie gezaehlt.
     */
    public static final int VERSION = 2;

    /**
     * Ein fehlendes {@code v} (Jackson liefert dann 0) heisst v1: so liest {@link MatchStore} die vor
     * Einfuehrung des Feldes geschriebenen Datensaetze ohne Sonderfall. Bewusst die feste 1 und nicht
     * {@link #VERSION} - ein alter Datensatz wird durch eine neue Formatversion nicht neuer.
     */
    public MatchRecord {
        if (v <= 0) {
            v = 1;
        }
    }

    /** Neuer Datensatz in der aktuellen Version (alles ausser {@code v}). */
    public MatchRecord(String id, String startedAt, String endedAt, long durationMs, String source,
                       Integer aiTimeout, int turns, String reason, boolean draw, boolean counted,
                       String excludeReason, List<Seat> seats) {
        this(VERSION, id, startedAt, endedAt, durationMs, source, aiTimeout, turns, reason, draw, counted,
                excludeReason, seats);
    }

    /** Neuer Datensatz mit geaendertem {@code counted}/{@code excludeReason}, sonst unveraendert. */
    public MatchRecord withCounted(boolean counted, String excludeReason) {
        return new MatchRecord(v, id, startedAt, endedAt, durationMs, source, aiTimeout, turns, reason,
                draw, counted, excludeReason, seats);
    }

    /**
     * Ergebnis und Kennzahlen eines Sitzes ueber die gesamte Partie. ai: {@code null} bei einem
     * menschlichen Sitz. eliminatedTurn/firstMissedLandDrop/firstCommanderTurn: {@code null}, wenn das
     * Ereignis nicht eintrat (Sitz ueberlebte / keine verpasste Landabgabe / kein Commander-Cast).
     *
     * <p>Die Vorfall-Kennzahlen ab {@code spellsCountered} gibt es erst ab {@code v: 2}; in einem
     * v1-Datensatz stehen sie auf 0, weil nie gezaehlt wurde - siehe {@link MatchRecord#VERSION}.
     * {@code creaturesLostInCombat} und {@code creaturesLostOther} sind Teilmengen von
     * {@code permanentsLost} (nur Kreaturen), ihre Summe ist also hoechstens so gross.
     * {@code biggestSweep}/{@code sweepsSuffered} zaehlen eigene Verluste je Aufloesungsfenster
     * (Fenster = bis zur naechsten Zauber-Aufloesung bzw. zum naechsten Phasenwechsel), ein Fenster
     * ab drei Verlusten gilt als Massenentfernung.
     *
     * <p><b>Kampf und Schaden.</b> {@code attacksDeclared} zaehlt jede Deklaration eines eigenen
     * Angreifers (zwei Kampfphasen in einem Zug also doppelt), {@code attackedTurns} nur die Zuege
     * mit mindestens einem Angriff. {@code attackersFaced} sind gegnerische Angreifer, die gegen
     * diesen Sitz oder gegen einen seiner Planeswalker deklariert wurden.
     * {@code damageTakenFlying}/{@code damageTakenTrample}/{@code damageTakenOther} teilen den
     * Kampfschaden am Sitz nach dem Schluesselwort der Quelle auf (Fliegen vor Trampelschaden, eine
     * Quelle ohne beides zaehlt als "other"); zusammen mit {@code damageTakenNonCombat} ergeben sie
     * {@code damageTaken}, und {@code damageTakenFlying + damageTakenTrample + damageTakenOther}
     * ergibt {@code combatDamageTaken}. {@code commanderDamageTaken} liegt QUER dazu: es ist ein Teil
     * des Kampfschadens, kein vierter Topf. Ebenso ist {@code damageDealtCombat +
     * damageDealtNonCombat == damageDealt}.
     *
     * <p><b>timeline</b> haelt je eigenem Zug den Stand zu Zugbeginn fest und ist auf
     * {@code MatchRecorder.TIMELINE_MAX} Punkte gedeckelt (danach faellt jeder weitere Zug weg) -
     * nie {@code null}, in einem v1-Datensatz oder ohne einen einzigen Zug schlicht leer.
     *
     * <p><b>Ende, Eroeffnung, Entfernung (Nachtrag).</b> {@code handEnd} sind die Handkarten des
     * Sitzes, als er aus dem Spiel ging - gemessen beim letzten Ereignis, das der Recorder VOR dem
     * Ausscheiden gesehen hat. Spaeter nachzusehen geht nicht: {@code Game.onPlayerLost} nimmt einem
     * ausgeschiedenen Sitz in einer Mehrspieler-Partie sofort alle Karten aus dem Spiel, die Hand
     * waere dann immer leer. Ein Sitz, der die Partie ueberlebt, traegt seinen Stand am Partieende.
     * {@code openingLands} sind die Laender in der Hand, die der Sitz nach allen Mulligans behalten
     * hat, gezaehlt beim ersten {@code GameEventTurnBegan} der Partie (Mulligans sind da durch, der
     * erste Zug noch nicht gezogen) - die Grundlage fuer "behaelt Zwei-Land-Haende".
     * {@code removalCast} zaehlt eigene Zauber, deren Faehigkeitskette eine Entfernung enthaelt;
     * die Abgrenzung und ihre Grenzen stehen bei {@code MatchRecorder.classifyCast}.
     *
     * <p><b>Drei Naeherungen, die man kennen muss</b> - sie heissen anders, als ihr Name vermuten
     * laesst, und eine Auswertung, die das nicht weiss, rechnet falsch:</p>
     * <ul>
     *   <li>{@code cardsDiscarded} zaehlt JEDEN Weg Hand -&gt; Friedhof, nicht nur das Abwerfen im
     *       Wortsinn. Eine Karte, die als Kosten von der Hand in den Friedhof geht, steht hier
     *       genauso drin wie eine, die der Gegner mit einem Handkartenangriff herausgeholt hat.
     *       "Wie oft werde ich leergeraeumt" laesst sich daraus allein nicht beantworten.</li>
     *   <li><b>Infektschaden zaehlt in den Kampf-Toepfen mit, kostet aber kein Leben.</b> Forge
     *       feuert {@code GameEventPlayerDamaged} auch fuer Infekt (Feld {@code infect}); der
     *       Recorder wertet es nicht aus. {@code damageTaken} ist deshalb NICHT das verlorene Leben -
     *       gegen ein Infekt-Deck steht hier Schaden, waehrend {@code lifeEnd} unveraendert bleibt
     *       und stattdessen {@code poisonEnd} steigt. Wer Leben rechnen will, rechnet mit
     *       {@code lifeEnd}, nicht mit {@code damageTaken}.</li>
     *   <li>{@code commanderDamageTaken} ist die Summe ueber ALLE gegnerischen Commander zusammen,
     *       nicht je Commander. Die 21-Punkte-Regel (CR 903.10a) laesst sich daraus nicht ableiten:
     *       21 hier koennen 11 von dem einen und 10 von dem anderen Commander sein.</li>
     * </ul>
     */
    public record Seat(String name, String deck, boolean human, Ai ai, boolean winner, String lossReason,
                       Integer eliminatedTurn, int mulligans, int lands, List<Integer> landsByTurn,
                       int missedLandDrops, Integer firstMissedLandDrop, int spells, int spellMana,
                       int spellsCountered, int spellsFizzled, int counterspellsCast,
                       int cardsDrawn, int cardsDiscarded, int cardsMilled,
                       int permanentsLost, int creaturesLostInCombat, int creaturesLostOther,
                       int biggestSweep, int sweepsSuffered, int tokensCreated,
                       int attacksDeclared, int attackedTurns, int attackersFaced, int blocksDeclared,
                       int damageTakenFlying, int damageTakenTrample, int damageTakenOther,
                       int damageTakenNonCombat, int damageDealtCombat, int damageDealtNonCombat,
                       int commanderDamageTaken, int lifeGained, List<TurnPoint> timeline,
                       int commanderCasts, int commanderTax, Integer firstCommanderTurn,
                       int damageDealt, int damageTaken,
                       int combatDamageTaken, int lifeEnd, int poisonEnd,
                       int handEnd, int openingLands, int removalCast) {

        /**
         * {@code timeline} ist nie {@code null}: in einem v1-Datensatz fehlt das Feld ganz, und
         * Jackson legt dann {@code null} in die Komponente. Die Auswertung soll sich darauf nicht
         * einstellen muessen - eine leere Liste heisst hier "keine Daten", genau wie die 0 bei den
         * uebrigen Feldern aus Runde B.
         */
        public Seat {
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
        }
    }

    /**
     * Ein Punkt der Zeitachse: Stand zu Beginn eines eigenen Zuges. {@code turn} ist Forges GLOBALE
     * Zugnummer (wie {@code turns} und {@code eliminatedTurn}), nicht der wievielte eigene Zug es war
     * - den gibt die Position in der Liste ohnehin her, die globale Nummer dagegen erlaubt es, die
     * Kurven mehrerer Sitze und den Zeitpunkt des Ausscheidens nebeneinander zu legen.
     * {@code lands}/{@code creatures} sind die eigenen Laender bzw. Kreaturen IM SPIEL (Bestand,
     * nicht kumulierte Abgaben - dafuer gibt es {@code landsByTurn}), {@code life} das Leben und
     * {@code hand} die Zahl der Handkarten.
     *
     * <p>{@code spells} faellt aus der Reihe: die anderen vier sind ein Stand ZU Zugbeginn,
     * {@code spells} ist die Zahl der Zauber, die der Sitz in dem Zug-ABSCHNITT gewirkt hat, den
     * dieser Punkt eroeffnet - vom Beginn seines eigenen Zuges bis zum Beginn seines naechsten
     * eigenen Zuges. Ein Blitz im Zug eines Gegners zaehlt damit zum vorangegangenen eigenen Zug
     * ("was habe ich in diesem Zugzyklus gewirkt"). Zwei Folgen davon: Zauber vor dem ersten eigenen
     * Zug haben keinen Punkt und fehlen in der Zeitachse, und hinter dem Deckel
     * ({@code MatchRecorder.TIMELINE_MAX}) zaehlt gar nichts mehr - der letzte Punkt sammelt nicht
     * den Rest der Partie ein. Die Gesamtzahl steht unabhaengig davon in {@code Seat.spells}. In
     * einem v1-Datensatz (und in jedem vor dieser Aenderung geschriebenen v2-Datensatz) fehlt das
     * Feld und liest sich als 0.
     */
    public record TurnPoint(int turn, int lands, int creatures, int life, int hand, int spells) { }

    /** KI-Konfiguration eines Sitzes (mode/profile, siehe {@code mtgplayer.ai.AiConfig}). */
    public record Ai(String mode, String profile) { }
}
