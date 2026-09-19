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
        assertEquals(30, d.gameTimeoutMinutes()); assertEquals(false, d.inProcess());
        BenchArgs p = BenchArgs.parse("--games 3 --a hybrid:Cautious --b std:Reckless --deck-a precon:Abzan Armor [TDC] [2025] --turns 30 --timeout 2 --seed 7 --out /tmp/x --game-timeout 10 --in-process".split(" (?=--)"));
        assertEquals(3, p.games()); assertEquals("precon:Abzan Armor [TDC] [2025]", p.deckA()); assertEquals(7, p.seed()); assertEquals(Path.of("/tmp/x"), p.out());
        assertEquals(10, p.gameTimeoutMinutes()); assertEquals(true, p.inProcess());
        assertThrows(IllegalArgumentException.class, () -> BenchArgs.parse(new String[] {"--games", "0"}));
        assertThrows(IllegalArgumentException.class, () -> BenchArgs.parse(new String[] {"--game-timeout", "0"}));
    }
}
