package mtgplayer;

import forge.deck.Deck;
import mtgplayer.app.AppMode;
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
import mtgplayer.sparring.SparringArgs;
import mtgplayer.sparring.SparringRun;
import mtgplayer.server.HttpStatic;
import mtgplayer.stats.MatchRecord;

import java.io.IOException;
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
 * {@code --sparring-one <json>}: genau eine Sparring-Partie, fuer den internen Aufruf durch
 * {@code mtgplayer.sparring.SubprocessGameRunner} - nicht fuer den direkten Gebrauch gedacht.
 * {@code --trimmed-check}: Kartenzahl, Precons und eine KI-Partie pruefen, fuer den internen Aufruf
 * durch {@code mtgplayer.forge.TrimmedResTest} - nicht fuer den direkten Gebrauch gedacht.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        boolean appMode = AppMode.enabled();

        // App-Modus (-Dmtgplayer.app=true, siehe AppMode): Schreibpruefung ganz vorn, noch vor jedem
        // Forge-Zugriff, damit die Meldung kommt, bevor Forge eine halbe Minute lang Kartenskripte
        // laedt. Ohne die Property bleibt dieser Zweig weg - Kevins Entwicklungs-Bridge nimmt den Weg
        // von heute unveraendert.
        if (appMode) {
            String problem = AppMode.writableOrNull(ForgeBoot.assetsDir());
            if (problem != null) {
                AppMode.showFatalError(problem);
                return;
            }
        }

        if (args.length > 0) {
            String modus = args[0];

            if (modus.equals("--ai-demo")) {
                ForgeBoot.init();
                printCardsLoaded(t0);
                aiDemo(args.length > 1 ? Long.parseLong(args[1]) : System.currentTimeMillis());
                return;
            }

            if (modus.equals("--bench-one")) {
                // Von SubprocessRunner aufgerufen: genau ein Spiel, Ergebnis als JSON-Zeile auf stdout - siehe
                // README Abschnitt "Bench". args[1] ist der Spielindex, args[2..] dieselben Optionen wie bei
                // --bench (siehe SubprocessRunner.cliArgs).
                ForgeBoot.init();
                printCardsLoaded(t0);
                int i = Integer.parseInt(args[1]);
                BenchArgs benchArgs = BenchArgs.parse(Arrays.copyOfRange(args, 2, args.length));
                GameRecord record = Bench.playOne(benchArgs, i, System.out::println);
                System.out.println("BENCH_RESULT " + Json.mapper().writeValueAsString(record));
                // Forge/Swing koennen nicht-daemon Threads hinterlassen, die ein blosses return ueberleben
                // wuerden - der Kindprozess soll aber zuverlaessig enden, sobald sein einziges Spiel fertig ist.
                System.exit(0);
                return;
            }

            if (modus.equals("--sparring-one")) {
                // Von SubprocessGameRunner aufgerufen: genau eine Partie, Datensatz als JSON-Zeile auf
                // stdout (siehe Spec, Abschnitt 3). args[1] ist ein JSON-SparringArgs.Job.
                if (args.length < 2) {
                    throw new IllegalArgumentException("--sparring-one braucht den Auftrag als JSON");
                }
                ForgeBoot.init();
                printCardsLoaded(t0);
                SparringArgs.Job job = Json.mapper().readValue(args[1], SparringArgs.Job.class);
                MatchRecord record = SparringRun.playOne(job, System.out::println);
                System.out.println("SPARRING_RESULT " + Json.mapper().writeValueAsString(record));
                // wie bei --bench-one: Forge/Swing koennen nicht-daemon Threads hinterlassen, die ein
                // blosses return ueberleben wuerden.
                System.exit(0);
                return;
            }

            if (modus.equals("--trimmed-check")) {
                // Von TrimmedResTest aufgerufen (eigener Kindprozess mit -Dmtgplayer.assets auf ein
                // ausgeduenntes res-Verzeichnis, siehe scripts/trim-res.sh): belegt, dass Kartenladen,
                // saemtliche Precons und eine volle KI-Partie auch mit dem ausgeduennten Umfang
                // funktionieren - nicht fuer den direkten Gebrauch gedacht. Ergebniszeile als Klartext
                // (kein eigener Record noetig fuer eine Handvoll Zahlen), von ChildJvm eingesammelt.
                ForgeBoot.init();
                printCardsLoaded(t0);
                List<String> names = Precons.names();
                for (String n : names) {
                    Precons.load(n); // wirft mit dem gesuchten Pfad, wenn ein Precon im ausgeduennten res fehlt
                }
                List<Deck> zweiDecks = List.of(Precons.load(names.get(0)), Precons.load(names.get(1)));
                AiMatch.Result r = AiMatch.play(zweiDecks, List.of("KI 1", "KI 2"), 40, l -> { });
                System.out.println("TRIMMED_RESULT cards=" + ForgeBoot.cardCount() + " precons=" + names.size()
                        + " turns=" + r.turns() + " capped=" + r.turnCapped());
                System.exit(0);
                return;
            }

            if (modus.equals("--bench")) {
                ForgeBoot.init();
                printCardsLoaded(t0);
                Bench.run(BenchArgs.parse(Arrays.copyOfRange(args, 1, args.length)), System.out);
                return;
            }

            // Blocker 1c (Review-Befund): jedes andere erste Argument abbrechen, statt es
            // stillschweigend zu ignorieren und unten in den normalen Server-Zweig zu fallen. Genau
            // das droht, wenn (trotz der Fixes in ChildJvm/package.sh) JAVA_BIN doch einmal auf den
            // gepackten Starter selbst zeigt: der jpackage-Starter reicht unbekannte Kommandozeilen-
            // Optionen als PROGRAMMARGUMENTE weiter (args[0] waere dann z.B. "-Xmx4g", keins der oben
            // erkannten Flags) - ohne diesen Zweig wuerde pro Sparring-/Bench-Partie eine zweite
            // komplette App samt eigenem Fenster hochfahren.
            System.err.println("Main: unbekanntes erstes Argument \"" + modus
                    + "\" - breche ab, statt versehentlich einen zweiten Server zu starten.");
            return;
        }

        // Normalfall: Bridge-Server. Blocker 3 (Review-Befund, Spec §3): HTTP-Server + Fenster ZUERST,
        // Forges Kartendatenbank ERST DANACH - beim Doppelklick soll sofort ein Fenster mit
        // Verbindungshinweis erscheinen (der Client verbindet sich per WebSocket neu, bis die Bridge
        // bereit ist, siehe web/src/ws.ts) statt minutenlang gar nichts (Forge braucht 20-60 s zum
        // Laden der Kartenskripte). Der HTTP-Teil fasst dafuer KEINEN Forge-Zustand an: HttpStatic
        // liefert nur web/dist von der Platte, und ImageCache.standard() braucht bloss
        // ForgeBoot.dataDir() (ein reiner Pfad, kein FModel/StaticData-Zugriff) - anders als
        // `new Bridge(wsPort)` weiter unten, dessen WebGuiGame/GuiBase.getInterface() erst nach
        // ForgeBoot.init() existiert.
        int wsWunsch = Integer.getInteger("mtgplayer.wsPort", 8081);
        int httpWunsch = Integer.getInteger("mtgplayer.httpPort", 8080);
        int wsPort;
        int httpPort;
        Path web;
        if (appMode) {
            // Mehrere App-Starts (oder ein belegter Standardport) sollen sich nicht gegenseitig
            // blockieren - AppMode.freePort weicht dann auf einen freien Port aus.
            try {
                wsPort = AppMode.freePort(wsWunsch);
                httpPort = AppMode.freePort(httpWunsch);
            } catch (IOException e) {
                AppMode.showFatalError("Kein freier Port gefunden: " + e.getMessage());
                return;
            }
            // Vorgabe fuer web/dist im App-Modus: <App-Ordner>/app/web. mtgplayer.assets zeigt auf
            // <App-Ordner>/assets (Spec "App-Paket" §2: assets/ und app/web/ liegen im App-Ordner
            // nebeneinander) - darum hier der Elternordner von assetsDir(), NICHT assetsDir()
            // selbst, sonst landet man eine Ebene zu tief unter assets/app/web statt app/web.
            Path appDir = ForgeBoot.assetsDir().getParent();
            String webOverride = System.getProperty("mtgplayer.web");
            web = webOverride != null ? Paths.get(webOverride) : appDir.resolve("app").resolve("web");
        } else {
            wsPort = wsWunsch;
            httpPort = httpWunsch;
            web = Paths.get(System.getProperty("mtgplayer.web", "../web/dist"));
        }
        web = web.toAbsolutePath().normalize();

        HttpStatic http;
        if (appMode) {
            // Trotz freePort bleibt ein kleines Rennfenster (siehe AppMode.freePort) - ein
            // Bindefehler hier ist dann ein gemeldeter Fehler, kein Absturz mit Stacktrace, wie
            // ihn ein Nutzer der gepackten App nie zu sehen bekommen soll.
            try {
                http = new HttpStatic(httpPort, web);
            } catch (IOException e) {
                AppMode.showFatalError("Port " + httpPort + " ist belegt; laeuft MTG-Player schon?");
                return;
            }
        } else {
            http = new HttpStatic(httpPort, web);
        }
        http.addContext("/img/", new ImageHandler(ImageCache.standard()));
        http.start();
        if (appMode) {
            // Vor ForgeBoot.init() (Blocker 3): das Fenster erscheint sofort, die Seite zeigt den
            // Verbindungshinweis (".lobby-head .connecting"), waehrend Forge im Hintergrund laedt.
            AppMode.openWindow("http://localhost:" + httpPort);
        }

        ForgeBoot.init();
        printCardsLoaded(t0);

        Bridge bridge = new Bridge(wsPort);
        try {
            bridge.start();
        } catch (RuntimeException e) {
            http.stop();
            if (appMode) {
                // Gleiches Rennfenster wie beim HTTP-Server oben - WsServer.start() (siehe dort)
                // wirft eine IllegalStateException, wenn das Binden scheitert (z.B. zweiter
                // App-Start auf demselben Port). Ohne diesen Zweig wuerde die Exception bis nach
                // main() durchschlagen und der Nutzer saehe einen rohen Java-Stacktrace.
                AppMode.showFatalError("Port " + wsPort + " ist belegt; laeuft MTG-Player schon?");
                return;
            }
            throw e;
        }
        System.out.println("Bereit. Browser: http://localhost:" + httpPort + "  (Dev: http://localhost:5173)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            try { bridge.stop(); } catch (InterruptedException ignored) { }
        }));
        Thread.currentThread().join();
    }

    private static void printCardsLoaded(long t0) {
        System.out.printf("%d Karten geladen in %.1f s%n", ForgeBoot.cardCount(), (System.currentTimeMillis() - t0) / 1000.0);
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
