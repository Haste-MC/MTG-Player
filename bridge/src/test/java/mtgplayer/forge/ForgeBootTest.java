package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import org.junit.jupiter.api.Test;

class ForgeBootTest {

    @Test
    void initLaedtKartendatenbank() {
        ForgeBoot.init();
        assertTrue(StaticData.instance() != null, "StaticData muss nach init existieren");
        int n = ForgeBoot.cardCount();
        assertTrue(n > 20000, "erwartet > 20000 Karten, war " + n);
    }

    @Test
    void initIstIdempotent() {
        ForgeBoot.init();
        StaticData before = StaticData.instance();
        ForgeBoot.init();
        assertSame(before, StaticData.instance(), "zweiter init() darf StaticData nicht neu aufbauen");
    }
}
