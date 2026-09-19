package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BenchArgsTest {
    @BeforeAll static void boot() { ForgeBoot.init(); }
    @Test void defaultsUndParsing() {
        BenchArgs d = BenchArgs.parse(new String[0]);
        assertEquals(40, d.games()); assertEquals(AiConfig.parse("sim"), d.a()); assertEquals(AiConfig.DEFAULT, d.b());
        assertEquals(200, d.turns()); assertEquals(5, d.timeout());
        BenchArgs p = BenchArgs.parse("--games 3 --a hybrid:Cautious --b std:Reckless --deck-a precon:Abzan Armor [TDC] [2025] --turns 30 --timeout 2 --seed 7 --out /tmp/x".split(" (?=--)"));
        assertEquals(3, p.games()); assertEquals("precon:Abzan Armor [TDC] [2025]", p.deckA()); assertEquals(7, p.seed()); assertEquals(Path.of("/tmp/x"), p.out());
        assertThrows(IllegalArgumentException.class, () -> BenchArgs.parse(new String[] {"--games", "0"}));
    }
}
