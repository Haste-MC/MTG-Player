package mtgplayer.bench;

import com.fasterxml.jackson.databind.ObjectWriter;
import forge.deck.Deck;
import forge.util.MyRandom;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import mtgplayer.protocol.Json;

/**
 * Fuehrt {@code args.games()} KI-gegen-KI-Spiele aus, Sitz A (Config/Deck {@code args.a()}/{@code
 * args.deckA()}) gegen Sitz B, sequenziell im aufrufenden Thread – Forge haengt an globalem Zustand
 * ({@link MyRandom}, {@code FModel}, Profil-Cache), parallele Spiele gehen deshalb nicht. Schreibt am
 * Ende (oder bei Ctrl-C ueber einen Shutdown-Hook mit dem bisherigen Stand) einen Markdown- und einen
 * JSON-Bericht unter {@code args.out()}.
 */
public final class Bench {

    /** Forges Log schreibt beim ersten Zug pro Spiel genau eine Zeile "Turn 1 (&lt;Spielername&gt;)"
     *  (siehe {@code GameLogFormatter#visit(GameEventTurnBegan)}, Text-Key {@code lblLogTurnNOwnerByPlayer}
     *  = "Turn {0} ({1})"); daraus lesen wir, wer tatsaechlich zuerst dran war (Forge lost das zusaetzlich
     *  zur Sitzreihenfolge aus, s. u.). */
    private static final Pattern FIRST_TURN = Pattern.compile("Turn 1 \\((.+)\\)");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final DateTimeFormatter DISPLAY_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Bench() { }

    public static Summary run(BenchArgs args, PrintStream progress) {
        Deck deckA = loadDeck(args.deckA());
        Deck deckB = loadDeck(args.deckB());

        List<GameRecord> records = new ArrayList<>();
        AtomicBoolean written = new AtomicBoolean(false);
        Thread hook = new Thread(() -> {
            if (written.compareAndSet(false, true)) {
                List<GameRecord> snapshot;
                synchronized (records) {
                    snapshot = new ArrayList<>(records);
                }
                writeReports(args, snapshot, BenchStats.summarize(snapshot));
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);

        int winsA = 0, winsB = 0, draws = 0;
        try {
            for (int i = 0; i < args.games(); i++) {
                MyRandom.setRandom(new Random(args.seed() + i));
                // Sitzreihenfolge in der RegisteredPlayer-Liste wechselt; Forge lost den Startspieler
                // trotzdem zusaetzlich aus (s. FIRST_TURN oben) - Namen "A"/"B" bleiben an Config/Deck
                // gebunden, nur die Position in der Liste dreht sich.
                boolean swap = i % 2 != 0;
                List<Deck> decks = swap ? List.of(deckB, deckA) : List.of(deckA, deckB);
                List<String> names = swap ? List.of("B", "A") : List.of("A", "B");
                List<AiConfig> configs = swap ? List.of(args.b(), args.a()) : List.of(args.a(), args.b());

                String[] firstSeat = {null};
                long t0 = System.currentTimeMillis();
                AiMatch.Result r = AiMatch.play(decks, names, configs, args.timeout(), args.turns(), line -> {
                    if (firstSeat[0] == null) {
                        Matcher m = FIRST_TURN.matcher(line);
                        if (m.matches()) {
                            firstSeat[0] = m.group(1);
                        }
                    }
                });
                long millis = System.currentTimeMillis() - t0;

                GameRecord record = new GameRecord(i, args.seed() + i, firstSeat[0] == null ? "?" : firstSeat[0],
                        r.winner(), r.reason(), r.turns(), millis);
                synchronized (records) {
                    records.add(record);
                }
                if ("A".equals(r.winner())) {
                    winsA++;
                } else if ("B".equals(r.winner())) {
                    winsB++;
                } else {
                    draws++;
                }
                String outcome = r.winner() == null ? "Unentschieden" : r.winner() + " gewinnt";
                progress.printf("#%d %s (Zug %d, %d s)  A %d – B %d – U %d%n",
                        i + 1, outcome, r.turns(), millis / 1000, winsA, winsB, draws);
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM faehrt schon herunter - der Hook selbst schreibt in diesem Fall den bisherigen Stand.
            }
        }

        Summary summary = BenchStats.summarize(records);
        if (written.compareAndSet(false, true)) {
            writeReports(args, records, summary);
        }
        return summary;
    }

    private static Deck loadDeck(String ref) {
        if (ref.startsWith("precon:")) {
            return Precons.load(ref.substring("precon:".length()));
        }
        if (ref.startsWith("saved:")) {
            return DeckStore.standard().load(ref.substring("saved:".length()));
        }
        throw new IllegalArgumentException("Deck-Referenz muss mit 'precon:' oder 'saved:' beginnen: " + ref);
    }

    private record ArgsReport(int games, String a, String b, String deckA, String deckB, int turns, int timeout,
                               long seed, String out) { }

    private record Report(ArgsReport args, Summary summary, List<GameRecord> games) { }

    private static void writeReports(BenchArgs args, List<GameRecord> records, Summary summary) {
        try {
            Files.createDirectories(args.out());
        } catch (IOException e) {
            throw new IllegalStateException("kann Bench-Ausgabeverzeichnis nicht anlegen: " + args.out(), e);
        }
        String base = FILE_STAMP.format(LocalDateTime.now()) + "-" + args.a().spec().replace(':', '-')
                + "-vs-" + args.b().spec().replace(':', '-');
        Path md = args.out().resolve(base + ".md");
        Path json = args.out().resolve(base + ".json");
        try {
            Files.writeString(md, markdown(args, records, summary));
            ArgsReport ar = new ArgsReport(args.games(), args.a().spec(), args.b().spec(), args.deckA(), args.deckB(),
                    args.turns(), args.timeout(), args.seed(), args.out().toString());
            ObjectWriter writer = Json.mapper().writerWithDefaultPrettyPrinter();
            Files.writeString(json, writer.writeValueAsString(new Report(ar, summary, records)));
        } catch (IOException e) {
            throw new IllegalStateException("kann Bench-Bericht nicht schreiben unter " + args.out(), e);
        }
    }

    private static String markdown(BenchArgs args, List<GameRecord> records, Summary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Bench ").append(args.a().spec()).append(" vs ").append(args.b().spec()).append('\n');
        sb.append("Decks: A = ").append(args.deckA()).append(", B = ").append(args.deckB())
                .append(" · Spiele: ").append(args.games())
                .append(" · Zugdeckel: ").append(args.turns())
                .append(" · Timeout: ").append(args.timeout()).append(" s")
                .append(" · Seed: ").append(args.seed())
                .append(" · ").append(DISPLAY_STAMP.format(LocalDateTime.now()))
                .append('\n');
        sb.append("| | A | B | Unentschieden |\n|---|---|---|---|\n");
        sb.append("| Siege | ").append(summary.winsA()).append(" | ").append(summary.winsB()).append(" | ")
                .append(summary.draws()).append(" (Zugdeckel ").append(summary.drawsByTurnCap()).append(") |\n");
        sb.append("Siegquote A (entschiedene Spiele): ").append(pct(summary.winRateA()))
                .append(" % · 95-%-Intervall ").append(pct(summary.ciLow())).append('–').append(pct(summary.ciHigh()))
                .append(" % · Ø Züge ").append(num1(summary.avgTurns())).append(" (Median ").append(num(summary.medianTurns()))
                .append(") · Ø Dauer ").append(Math.round(summary.avgMillis() / 1000.0)).append(" s\n");
        sb.append("## Spiele\n| # | Seed | Erster | Sieger | Grund | Züge | Dauer s |\n|---|---|---|---|---|---|---|\n");
        for (GameRecord g : records) {
            sb.append("| ").append(g.index() + 1).append(" | ").append(g.seed()).append(" | ").append(g.firstSeat())
                    .append(" | ").append(g.winner() == null ? "–" : g.winner()).append(" | ").append(g.reason())
                    .append(" | ").append(g.turns()).append(" | ").append(g.millis() / 1000).append(" |\n");
        }
        return sb.toString();
    }

    private static String pct(double fraction) {
        return num1(fraction * 100);
    }

    private static String num1(double d) {
        return String.format(Locale.GERMANY, "%.1f", d);
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.format(Locale.GERMANY, "%.0f", d) : String.format(Locale.GERMANY, "%.1f", d);
    }
}
