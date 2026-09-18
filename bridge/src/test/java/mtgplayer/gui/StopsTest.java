package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.phase.PhaseType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

class StopsTest {

    @Test
    void defaultsEntsprechenForgeDesktop() {
        Stops s = Stops.defaults();
        assertTrue(s.stopsAt(true, PhaseType.MAIN1));
        assertTrue(s.stopsAt(true, PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(s.stopsAt(true, PhaseType.MAIN2));
        assertFalse(s.stopsAt(true, PhaseType.UPKEEP));
        assertFalse(s.stopsAt(true, PhaseType.END_OF_TURN));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_BEGIN));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_DECLARE_ATTACKERS));
        assertTrue(s.stopsAt(false, PhaseType.COMBAT_DECLARE_BLOCKERS));
        assertTrue(s.stopsAt(false, PhaseType.END_OF_TURN));
        assertFalse(s.stopsAt(false, PhaseType.MAIN1));
    }

    @Test
    void withErsetztNurEineSeite() {
        Stops s = Stops.defaults().with(true, EnumSet.of(PhaseType.UPKEEP));
        assertTrue(s.stopsAt(true, PhaseType.UPKEEP));
        assertFalse(s.stopsAt(true, PhaseType.MAIN1));
        assertTrue(s.stopsAt(false, PhaseType.END_OF_TURN), "opp unverändert");
        assertEquals(Set.of(PhaseType.UPKEEP), s.own());
    }

    @Test
    void fromNamesIgnoriertUnbekannte() {
        Stops s = Stops.defaults().with(false, Stops.parse(java.util.List.of("MAIN1", "GIBTS_NICHT")));
        assertEquals(Set.of(PhaseType.MAIN1), s.opp());
    }
}
