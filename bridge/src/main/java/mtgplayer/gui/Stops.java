package mtgplayer.gui;

import forge.game.phase.PhaseType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Phasen, in denen der Spieler angehalten wird – getrennt für eigene und gegnerische Züge. Unveränderlich. */
public final class Stops {

    private final EnumSet<PhaseType> own;
    private final EnumSet<PhaseType> opp;

    private Stops(EnumSet<PhaseType> own, EnumSet<PhaseType> opp) {
        this.own = own;
        this.opp = opp;
    }

    /** Forges Desktop-Defaults (PHASE_HUMAN_* / PHASE_AI_* in ForgePreferences). */
    public static Stops defaults() {
        return new Stops(
                EnumSet.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2),
                EnumSet.of(PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN));
    }

    public boolean stopsAt(boolean ownTurn, PhaseType phase) {
        return (ownTurn ? own : opp).contains(phase);
    }

    public Stops with(boolean ownTurn, Set<PhaseType> phases) {
        EnumSet<PhaseType> copy = phases.isEmpty() ? EnumSet.noneOf(PhaseType.class) : EnumSet.copyOf(phases);
        return ownTurn ? new Stops(copy, opp) : new Stops(own, copy);
    }

    public Set<PhaseType> own() { return Collections.unmodifiableSet(own); }
    public Set<PhaseType> opp() { return Collections.unmodifiableSet(opp); }

    /** Namen der PhaseType-Konstanten → Set; unbekannte Namen werden ignoriert. */
    public static Set<PhaseType> parse(List<String> names) {
        EnumSet<PhaseType> out = EnumSet.noneOf(PhaseType.class);
        if (names == null) return out;
        for (String n : names) {
            try {
                out.add(PhaseType.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // unbekannte Phase vom Client – ignorieren
            }
        }
        return out;
    }

    public static List<String> names(Set<PhaseType> phases) {
        List<String> out = new ArrayList<>();
        for (PhaseType p : PhaseType.values()) {
            if (phases.contains(p)) out.add(p.name());
        }
        return out;
    }
}
