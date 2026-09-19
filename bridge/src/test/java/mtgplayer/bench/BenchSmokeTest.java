package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class BenchSmokeTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void zweiSpieleSchreibenMarkdownUndJson(@TempDir Path tmp) throws Exception {
        // hybrid statt sim: USE_FULL_SIMULATION (sim) treibt bei diesem Precon-Matchup den Heap zuverlaessig
        // in ein OutOfMemoryError (reproduziert mit --turns 10/30, --timeout 1/2, mehreren Seeds, auch mit
        // direkten AiMatch.play-Aufrufen ohne Bench-Code) - siehe task-2-report.md "Abweichungen". hybrid
        // (USE_HYBRID_SIMULATION) simuliert nur die Zauberauswahl, nicht Angriff/Block, und bleibt genauso
        // eine echte nicht-Standard-AiConfig fuer den Smoke-Test.
        BenchArgs args = new BenchArgs(2, AiConfig.parse("hybrid:Default"), AiConfig.parse("std:Default"),
                "precon:Abzan Armor [TDC] [2025]", "precon:Adaptive Enchantment [C18] [2018]",
                30, 2, 1, tmp);

        Summary summary = Bench.run(args, new PrintStream(OutputStream.nullOutputStream()));

        assertEquals(2, summary.games());
        List<String> names;
        try (var files = Files.list(tmp)) {
            names = files.map(p -> p.getFileName().toString()).toList();
        }
        assertTrue(names.stream().anyMatch(n -> n.endsWith(".md")), "erwarte .md, war " + names);
        assertTrue(names.stream().anyMatch(n -> n.endsWith(".json")), "erwarte .json, war " + names);
        String mdName = names.stream().filter(n -> n.endsWith(".md")).findFirst().orElseThrow();
        String content = Files.readString(tmp.resolve(mdName));
        assertTrue(content.contains("Siegquote"), "Markdown sollte 'Siegquote' enthalten, war:\n" + content);
    }
}
