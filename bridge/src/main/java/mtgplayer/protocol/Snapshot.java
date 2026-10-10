package mtgplayer.protocol;

import java.util.List;
import java.util.Map;

/**
 * Vollständiger Spielzustand aus Sicht eines Sitzes. Zonen halten nur Karten-IDs,
 * {@code cards} ist das flache Wörterbuch dazu. Nicht sichtbare Karten haben
 * {@code faceDown = true} und sonst nur {@code null}-Felder.
 */
public record Snapshot(
        String type,
        int turn,
        String phase,
        Integer activePlayer,
        Integer priorityPlayer,
        Integer me,
        boolean gameOver,
        List<PlayerSnap> players,
        List<StackSnap> stack,
        Map<Integer, CardSnap> cards,
        Messages.StopsMsg stops,
        boolean fullControl,
        PromptSnap prompt,
        Boolean spectator,
        /** Eine Zeile je Angreifer, solange ein Kampf laeuft; sonst null (siehe StateSerializer). */
        List<AttackSnap> combat) {

    public static final String TYPE = "state";

    public record PlayerSnap(
            int id,
            String name,
            boolean isAi,
            int life,
            Map<String, Integer> counters,
            Map<Integer, Integer> commanderDamage,
            List<Integer> hand,
            int librarySize,
            List<Integer> graveyard,
            List<Integer> exile,
            List<Integer> command,
            List<Integer> battlefield,
            Map<String, Integer> manaPool,
            boolean hasPriority,
            Boolean highlighted,
            Boolean targetable,
            /** Teamnummer des Sitzes; {@code null} (Feld fehlt im JSON) = die Partie hat keine Teams. */
            Integer team,
            /** true, sobald der Sitz ausgeschieden ist (Forge: PlayerView.getHasLost). In einer Teampartie laeuft
             *  die Partie dann weiter; der Browser schaltet am eigenen Sitz auf Zuschauen um. Steht immer im JSON. */
            boolean lost) { }

    public record CardSnap(
            int id,
            boolean faceDown,
            String name,
            String imageKey,
            Integer controller,
            Integer owner,
            String zone,
            Boolean tapped,
            Boolean sick,
            Integer power,
            Integer toughness,
            Integer damage,
            Map<String, Integer> counters,
            Integer attachedTo,
            List<Integer> attachments,
            /** Id des verzauberten Spielers (CardView.getEnchantedPlayer), sonst {@code null}. */
            Integer attachedToPlayer,
            String text,
            String typeLine,
            String manaCost,
            Boolean attacking,
            Boolean blocking,
            Boolean token,
            Boolean selectable,
            Boolean actionable,
            Boolean highlighted,
            /** {@code true} nur fuer Commander (CardView.isCommander), sonst {@code null}. */
            Boolean commander,
            /** {@code true} nur fuer Forges Effekt-Hilfskarten (CardView.isImmutable, GamePieceType.EFFECT), sonst {@code null}. */
            Boolean effect,
            /** {@code true} nur fuer Embleme (CardView.isEmblem), sonst {@code null}. */
            Boolean emblem,
            /**
             * Was unter einer VERDECKTEN Karte liegt - nur gefuellt, wenn der Betrachter sie ansehen
             * darf (eigener Morph/Manifest, per Hideaway verbanntes Exil, Foretell ...). Die uebrigen
             * Felder bleiben die des BRETTZUSTANDS: eine verdeckte Kreatur kaempft als 2/2, und genau
             * das soll der Tisch auch zeigen.
             */
            Verdeckt verdeckt) {

        public static CardSnap hidden(int id) {
            return new CardSnap(id, true, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Die Identitaet unter einer verdeckten Karte (Forges {@code CardView.getAlternateState()}).
     *
     * <p>Eigenes Feld statt ueberschriebener Hauptfelder: der Tisch zeigt weiter die Rueckseite und
     * den Brettzustand, nur die Vorschau und das Detail-Panel greifen hierauf zu. Siehe
     * {@code docs/superpowers/specs/2026-10-08-verdeckte-karten-design.md}.</p>
     */
    public record Verdeckt(String name, String imageKey, String typeLine, String manaCost, String text) { }

    /** Ein Angreifer mit seinem Ziel und seinen Blockern. Genau eines von {@code defenderPlayer} und
     *  {@code defenderCard} ist gesetzt (Forges Verteidiger ist ein Spieler ODER eine Karte - Planeswalker,
     *  Battle) - beide null kommt hier praktisch nicht vor: {@code CombatView.addAttackingBand} kehrt bei
     *  {@code defender == null} sofort zurueck, der Angreifer steht dann gar nicht erst in der View. Die
     *  Sammelgruppe "none" im Web-UI (combat.ts) ist damit heute ein toter Verteidigungspfad. */
    public record AttackSnap(
            int attacker,
            Integer defenderPlayer,
            Integer defenderCard,
            /** Blocker dieses Angreifers, null wenn keiner zugeteilt ist. */
            List<Integer> blockers) { }

    public record StackSnap(
            int index,
            String text,
            Integer sourceCard,
            Integer controller,
            List<Integer> targetCards,
            List<Integer> targetPlayers) { }

    public record PromptSnap(
            String message,
            Integer card,
            String okLabel,
            String cancelLabel,
            boolean okEnabled,
            boolean cancelEnabled,
            int seq) {

        public static final PromptSnap EMPTY = new PromptSnap("", null, "OK", "Cancel", false, false, 0);

        public PromptSnap withSeq(int newSeq) {
            return new PromptSnap(message, card, okLabel, cancelLabel, okEnabled, cancelEnabled, newSeq);
        }
    }
}
