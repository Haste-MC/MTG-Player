package mtgplayer;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import mtgplayer.server.Bridge;
import mtgplayer.server.HttpStatic;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Standard: Bridge-Server für den Browser (WebSocket 8081, HTTP 8080).
 * {@code --ai-demo [seed]}: vier zufällige Precons spielen headless (M1-Verhalten).
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

        int wsPort = Integer.getInteger("mtgplayer.wsPort", 8081);
        int httpPort = Integer.getInteger("mtgplayer.httpPort", 8080);
        Path web = Paths.get(System.getProperty("mtgplayer.web", "../web/dist")).toAbsolutePath().normalize();

        HttpStatic http = new HttpStatic(httpPort, web);
        http.start();
        Bridge bridge = new Bridge(wsPort);
        try {
            bridge.start();
        } catch (RuntimeException e) {
            http.stop();
            throw e;
        }
        System.out.println("Bereit. Browser: http://127.0.0.1:" + httpPort + "  (Dev: http://localhost:5173)");
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
