package mtgplayer;

import forge.deck.Deck;
import mtgplayer.app.AppMode;
import mtgplayer.app.IdleExit;
import mtgplayer.bench.Bench;
import mtgplayer.bench.BenchArgs;
import mtgplayer.bench.GameRecord;
import mtgplayer.carddata.CardDataCheck;
import mtgplayer.carddata.CardDataSweep;
import mtgplayer.carddata.Exclusions;
import mtgplayer.carddata.StromSammler;
import mtgplayer.forge.CrashLog;
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
import java.io.PrintStream;
import java.nio.file.Files;
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
 * {@code --kartendaten-pruefen}: Forges Kartendaten laden und einen Befund (JSON, eine Zeile) ausgeben, fuer den
 * woechentlichen Abgleich ({@code scripts/kartendaten-sync.sh}) - urteilt nicht.
 * {@code --kartendaten-aussortieren <befund.json> <ausschluss.txt> <neue-dateien.txt>}: die Dateien hinter einem
 * Befund entfernen und die Ausschlussliste fortschreiben, ebenfalls fuer den Abgleich.
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

            if (modus.equals("--kartendaten-pruefen")) {
                // Vom woechentlichen Abgleich gerufen (scripts/kartendaten-sync.sh) und von
                // CardDataCheckTest. Die Parse-Meldungen koennen nur WAEHREND des Ladens eingesammelt
                // werden - danach sind sie durch, deshalb haengt sich der Modus vorher an die Strome.
                // Beide Strome: Forge schreibt auf stdout beim Laden nur eine Zeile, alles Diagnostische
                // (Stacktraces, "not assigned to any set", "Upcoming set ...") geht auf stderr.
                // Durchgereicht wird weiter, damit das Ablauf-Protokoll vollstaendig bleibt.
                PrintStream echt = System.out;
                List<String> meldungen = StromSammler.einsammeln(ForgeBoot::init);
                CardDataCheck.Befund befund = CardDataCheck.pruefen(
                        ForgeBoot.assetsDir().resolve("res"), meldungen);
                // Ausdruecklich UTF-8: System.out nimmt sonst die Kodierung der Locale (LC_ALL=POSIX macht aus
                // "Joetun Grunt" ein "J?tun Grunt"), und der Schluessel passte dann zu keiner Datei mehr.
                byte[] zeileBytes = ("KARTENDATEN_BEFUND " + Json.toJson(befund) + System.lineSeparator())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                echt.write(zeileBytes, 0, zeileBytes.length);
                echt.flush();
                System.exit(0);
                return;
            }

            if (modus.equals("--kartendaten-aussortieren")) {
                // Entfernt die Dateien hinter einem Befund und schreibt die Ausschlussliste fort.
                // Getrennt vom Pruefmodus, weil der nicht urteilen soll (Spezifikation 3).
                // args: <befund.json> <ausschlussliste.txt> <neue-dateien.txt>
                Path befundDatei = Paths.get(args[1]);
                Path listenDatei = Paths.get(args[2]);
                Path neueDateien = Paths.get(args[3]);
                // Wer hier scheitert, soll das laut tun und mit != 0 enden: das aufrufende Skript bricht dann ab,
                // statt eine kaputte Kartendatei im cardsfolder liegen zu lassen (anders als --kartendaten-pruefen,
                // das immer mit 0 endet).
                PrintStream fehler = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                        true, java.nio.charset.StandardCharsets.UTF_8);
                CardDataCheck.Befund befund = Json.mapper().readValue(
                        Files.readString(befundDatei, java.nio.charset.StandardCharsets.UTF_8),
                        CardDataCheck.Befund.class);
                Path res = ForgeBoot.assetsDir().resolve("res");
                CardDataSweep.Ergebnis ergebnis;
                try {
                    ergebnis = CardDataSweep.aussortieren(befund, res, neueDateien);
                } catch (IOException | IllegalStateException e) {
                    fehler.println("KARTENDATEN_AUSSORTIEREN_FEHLER " + e.getMessage());
                    System.exit(2);
                    return;
                }
                // Nur was eine Datei hatte, kommt in die Liste - der Rest ist ungeloest.
                Exclusions.schreiben(listenDatei,
                        Exclusions.ergaenzen(Exclusions.lesen(listenDatei), ergebnis.fortzuschreiben(),
                                java.time.LocalDate.now().toString()));
                System.out.println("KARTENDATEN_AUSSORTIERT " + ergebnis.entfernt());
                if (!ergebnis.ohneDatei().isEmpty() || !ergebnis.mehrereKeineNeu().isEmpty()) {
                    if (!ergebnis.ohneDatei().isEmpty()) {
                        fehler.println("KARTENDATEN_AUSSORTIEREN_FEHLER " + ergebnis.ohneDatei().size()
                                + " Schluessel aus dem Befund haben keine Datei unter " + res
                                + " - NICHT in die Ausschlussliste, die Karte bleibt ungeloest:");
                        ergebnis.ohneDatei().forEach(k -> fehler.println("  " + k));
                    }
                    if (!ergebnis.mehrereKeineNeu().isEmpty()) {
                        fehler.println("KARTENDATEN_AUSSORTIEREN_FEHLER " + ergebnis.mehrereKeineNeu().size()
                                + " Schluessel aus dem Befund haben MEHRERE Dateien unter " + res
                                + ", keine davon steht auf der Neu-Liste - nichts geloescht, NICHT in die "
                                + "Ausschlussliste, die kaputte Karte liegt weiter im Kartenordner "
                                + "(von Hand die kaputte Datei bestimmen und entfernen):");
                        ergebnis.mehrereKeineNeu().forEach(k -> fehler.println("  " + k));
                    }
                    System.exit(3);
                    return;
                }
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
            AppMode.openWindow(AppMode.windowUrl(httpPort, wsPort));
        }

        try {
            ForgeBoot.init();
        } catch (RuntimeException | LinkageError e) {
            // Beobachtet beim ersten Windows-Paket: FModel.initialize scheiterte an Forges Profildatei
            // (siehe ForgeBoot.writeProfileFile). Das Fenster stand zu diesem Zeitpunkt schon (es geht
            // absichtlich VOR dem Kartenladen auf), der HTTP-Server lief - und weil dessen Threads die
            // JVM am Leben halten, blieb eine App uebrig, die bedient wird, aber nie eine Engine
            // bekommt: der Nutzer sah eine Oberflaeche im Leerlauf und keinerlei Grund. Ein Abbruch
            // ohne Meldung waere genauso schlecht, deshalb derselbe Weg wie beim belegten Port unten.
            CrashLog.report("Start abgebrochen", "die Spiel-Engine liess sich nicht laden", e);
            http.stop();
            if (appMode) {
                AppMode.showFatalError("MTG-Player kann die Spiel-Engine nicht laden und beendet sich.\n\n"
                        + e + "\n\nEinzelheiten stehen in " + CrashLog.file());
                return;
            }
            throw e;
        }
        printCardsLoaded(t0);

        Bridge bridge = new Bridge(wsPort);
        IdleExit idleExit = null;
        if (appMode) {
            // Das Fenster ist ein eigener Prozess (AppMode.openWindow) - ohne diese Regel liefe die
            // Bridge nach dem Schliessen unbemerkt weiter, hielte ihren Port und den App-Ordner als
            // Arbeitsverzeichnis fest (Windows verweigert dann dessen Loeschen), und der einzige
            // Ausweg waere der Task-Manager. Die Schonfrist unterscheidet "Fenster zu" von einem
            // blossen Neuladen der Seite, das die Verbindung genauso trennt.
            idleExit = new IdleExit(FENSTER_ZU_SCHONFRIST, ERSTE_VERBINDUNG_FRIST, () -> beenden(bridge, http));
            bridge.watchForIdle(idleExit);
        }
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
        System.out.println("Bereit. Browser: " + AppMode.windowUrl(httpPort, wsPort) + "  (Dev: http://localhost:5173)");
        if (idleExit != null) {
            // Erst JETZT scharf stellen: vorher gibt es noch keinen WebSocket, zu dem sich das
            // Fenster ueberhaupt verbinden koennte (Forge laedt davor die Kartenskripte).
            idleExit.awaitFirstClient();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            try { bridge.stop(); } catch (InterruptedException ignored) { }
        }));
        Thread.currentThread().join();
    }

    /** So lange darf das Fenster zu sein, bevor die App sich beendet - genug fuer ein Neuladen der
     *  Seite, kurz genug, dass niemand einen Prozess im Hintergrund vergisst. */
    private static final java.time.Duration FENSTER_ZU_SCHONFRIST = java.time.Duration.ofSeconds(15);

    /** So lange wartet die App nach dem Start auf ihr erstes Fenster, bevor sie sich fuer verwaist
     *  haelt - reichlich bemessen, weil im Notfall jemand die Adresse aus einem Dialog abtippt. */
    private static final java.time.Duration ERSTE_VERBINDUNG_FRIST = java.time.Duration.ofMinutes(2);

    /** Geordnet beenden wie im Update-Weg (Bridge.realLaunch): erst Sparring/Match/WebSocket anhalten,
     *  dann die JVM - Forge und Swing hinterlassen Threads, die ein blosses return ueberleben wuerden. */
    private static void beenden(Bridge bridge, HttpStatic http) {
        try {
            bridge.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        http.stop();
        System.exit(0);
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
