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
        // hybrid statt sim: das urspruengliche OutOfMemoryError bei sim (USE_FULL_SIMULATION) ist seit
        // AiConfig.newLobbyPlayer (AiCache.clear() je Spielkopie) behoben (siehe
        // .superpowers/sdd/sim-oom-investigation.md, "Fix-Runde 1" in task-2-report.md) - der Speicher
        // ist also kein Grund mehr, hier bei hybrid zu bleiben. Der Grund jetzt: ein sim-Spiel dauert,
        // so lange die groesste Einzelentscheidung dauert (--timeout wirkt im sim-Pfad nicht, siehe
        // README), das waren in der Untersuchung 84 s bis 572 s *pro Spiel* - zu lang fuer einen
        // Smoke-Test, der in @Timeout(6, MINUTES) zuverlaessig durchlaufen soll. hybrid
        // (USE_HYBRID_SIMULATION) simuliert nur die Zauberauswahl, ist in Sekunden statt Minuten fertig
        // und bleibt eine echte nicht-Standard-AiConfig fuer den Smoke-Test. Der sim-Pfad selbst wird
        // durch SimMemoryProbe abgedeckt (manuell, -Dprobe.run=true, siehe dort).
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
