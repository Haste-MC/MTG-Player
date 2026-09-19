package mtgplayer;

import forge.deck.Deck;
import mtgplayer.bench.Bench;
import mtgplayer.bench.BenchArgs;
import mtgplayer.bench.GameRecord;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.images.ImageCache;
import mtgplayer.images.ImageHandler;
import mtgplayer.match.AiMatch;
import mtgplayer.protocol.Json;
import mtgplayer.server.Bridge;
import mtgplayer.server.HttpStatic;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Standard: Bridge-Server für den Browser (WebSocket 8081, HTTP 8080).
 * {@code --ai-demo [seed]}: vier zufällige Precons spielen headless (M1-Verhalten).
 * {@code --bench [Optionen]}: N Spiele KI gegen KI headless, siehe README Abschnitt "Bench".
 * {@code --bench-one <i> [Optionen]}: genau ein Bench-Spiel, fuer den internen Aufruf durch
 * {@code SubprocessRunner} - nicht fuer den direkten Gebrauch gedacht.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        ForgeBoot.init();
        System.out.printf("%d Karten geladen in %.1f s%n", ForgeBoot.cardCount(), (System.currentTimeMillis() - t0) / 1000.0);

        if (args.length > 0 && args[0].equals("--ai-demo")) {
            aiDemo(args.length > 1 ? Long.parseLong(args[1]) : System.currentTimeMillis());
            return;
        }

        if (args.length > 0 && args[0].equals("--bench-one")) {
            // Von SubprocessRunner aufgerufen: genau ein Spiel, Ergebnis als JSON-Zeile auf stdout - siehe
            // README Abschnitt "Bench". args[1] ist der Spielindex, args[2..] dieselben Optionen wie bei
            // --bench (siehe SubprocessRunner.cliArgs).
            int i = Integer.parseInt(args[1]);
            BenchArgs benchArgs = BenchArgs.parse(Arrays.copyOfRange(args, 2, args.length));
            GameRecord record = Bench.playOne(benchArgs, i, System.out::println);
            System.out.println("BENCH_RESULT " + Json.mapper().writeValueAsString(record));
            // Forge/Swing koennen nicht-daemon Threads hinterlassen, die ein blosses return ueberleben
            // wuerden - der Kindprozess soll aber zuverlaessig enden, sobald sein einziges Spiel fertig ist.
            System.exit(0);
            return;
        }

        if (args.length > 0 && args[0].equals("--bench")) {
            Bench.run(BenchArgs.parse(Arrays.copyOfRange(args, 1, args.length)), System.out);
            return;
        }

        int wsPort = Integer.getInteger("mtgplayer.wsPort", 8081);
        int httpPort = Integer.getInteger("mtgplayer.httpPort", 8080);
        Path web = Paths.get(System.getProperty("mtgplayer.web", "../web/dist")).toAbsolutePath().normalize();

        HttpStatic http = new HttpStatic(httpPort, web);
        http.addContext("/img/", new ImageHandler(ImageCache.standard()));
        http.start();
        Bridge bridge = new Bridge(wsPort);
        try {
            bridge.start();
        } catch (RuntimeException e) {
            http.stop();
            throw e;
        }
        System.out.println("Bereit. Browser: http://localhost:" + httpPort + "  (Dev: http://localhost:5173)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            try { bridge.stop(); } catch (InterruptedException ignored) { }
        }));
        Thread.currentThread().join();
    }

    private static void aiDemo(long seed) {
        List<String> names = new ArrayList<>(Precons.names());
        Collections.shuffle(names, new Random(seed));
        List<String> chosen = names.subList(0, 4);
        List<Deck> decks = chosen.stream().map(Precons::load).toList();
        System.out.println("Seed " + seed + ", Decks: " + chosen);
        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"), 60, System.out::println);
        System.out.println("=== Ergebnis: " + (r.winner() == null ? "unentschieden" : r.winner() + " gewinnt")
                + " (" + r.reason() + ") nach " + r.turns() + " Zuegen");
    }
}
