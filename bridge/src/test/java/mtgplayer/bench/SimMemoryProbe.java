package mtgplayer.bench;

import com.sun.management.GarbageCollectionNotificationInfo;
import forge.deck.Deck;
import forge.game.Game;
import forge.util.MyRandom;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;

/**
 * Wegwerf-Sonde fuer die OOM-Untersuchung (sim-Modus, zwei Spiele in einer JVM). Nicht Teil der Suite.
 * Aufruf: {@code cd bridge && mvn -q test -Dtest=SimMemoryProbe -Dprobe.run=true} (bei Bedarf mehr Heap
 * ueber {@code _JAVA_OPTIONS=-Xmx8g}, da die feste {@code -Xmx4g}-`argLine` in der POM sonst greift).
 * Steuerung per System-Properties: probe.games (2), probe.mode (sim), probe.turns (20), probe.timeout (2),
 * probe.histo (Pfad-Prefix fuer jmap -histo:live nach jedem Spiel, leer = aus).
 */
@EnabledIfSystemProperty(named = "probe.run", matches = "true")
class SimMemoryProbe {

    private static final AtomicLong PEAK_AFTER_GC = new AtomicLong();
    private static final AtomicLong PEAK_BEFORE_GC = new AtomicLong();
    private static final AtomicLong GC_COUNT = new AtomicLong();

    @Test
    void probe() throws Exception {
        int games = Integer.getInteger("probe.games", 2);
        String mode = System.getProperty("probe.mode", "sim");
        int turns = Integer.getInteger("probe.turns", 20);
        int timeout = Integer.getInteger("probe.timeout", 2);
        String histo = System.getProperty("probe.histo", "");
        boolean gcBetween = Boolean.parseBoolean(System.getProperty("probe.gcBetween", "true"));

        installGcListener();
        Thread sampler = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (true) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
                Runtime rt = Runtime.getRuntime();
                System.out.printf("[probe-sample] t=%ds used=%d MB total=%d MB peakAfterGC=%d MB gcs=%d%n",
                        (System.currentTimeMillis() - start) / 1000, (rt.totalMemory() - rt.freeMemory()) >> 20,
                        rt.totalMemory() >> 20, PEAK_AFTER_GC.get() >> 20, GC_COUNT.get());
                maybeWalk(PEAK_AFTER_GC.get() >> 20);
            }
        }, "probe-sampler");
        sampler.setDaemon(true);
        sampler.start();
        ForgeBoot.init();
        Deck a = Precons.load("Abzan Armor [TDC] [2025]");
        Deck b = Precons.load("Adaptive Enchantment [C18] [2018]");
        AiConfig cfgA = AiConfig.parse(mode + ":Default");
        AiConfig cfgB = AiConfig.parse("std:Default");
        gcAndReport("nach ForgeBoot.init");
        if (!histo.isEmpty()) histo(histo + "-boot.txt");

        for (int i = 0; i < games; i++) {
            MyRandom.setRandom(new Random(1 + i));
            PEAK_AFTER_GC.set(0); PEAK_BEFORE_GC.set(0); GC_COUNT.set(0);
            boolean swap = i % 2 != 0;
            long[] lines = {0};
            long[] copyErrors = {0};
            long t0 = System.currentTimeMillis();
            report("vor Spiel " + (i + 1));
            AiMatch.Result r = playCapturing(swap ? List.of(b, a) : List.of(a, b), swap ? List.of("B", "A") : List.of("A", "B"),
                    swap ? List.of(cfgB, cfgA) : List.of(cfgA, cfgB), timeout, turns, line -> {
                        lines[0]++;
                        if (line.contains("copy error")) copyErrors[0]++;
                    });
            long ms = System.currentTimeMillis() - t0;
            System.out.printf("[probe] Spiel %d fertig: %s nach %d Zuegen in %d s, %d Logzeilen, %d copy-error-Zeilen%n",
                    i + 1, r, r.turns(), ms / 1000, lines[0], copyErrors[0]);
            System.out.printf("[probe] Spiel %d Kopien=%d AiCache-Purges=%d%n", i + 1, COPIES.get(), PURGES.get());
            System.out.printf("[probe] Spiel %d GCs=%d, max Heap vor GC=%d MB, max Heap NACH GC (Live-Set-Spitze)=%d MB%n",
                    i + 1, GC_COUNT.get(), PEAK_BEFORE_GC.get() >> 20, PEAK_AFTER_GC.get() >> 20);
            report("nach Spiel " + (i + 1));
            if (gcBetween) gcAndReport("nach Spiel " + (i + 1) + " + System.gc()");
            threads("nach Spiel " + (i + 1));
            if (!histo.isEmpty()) histo(histo + "-game" + (i + 1) + ".txt");
        }
    }

    static volatile Game MAIN_GAME;
    static volatile List<forge.LobbyPlayer> LOBBY;
    private static final java.util.concurrent.atomic.AtomicBoolean WALKED = new java.util.concurrent.atomic.AtomicBoolean();

    /** Kopie von AiMatch.play, nur um das Game-Objekt fuer den Walker zu behalten. */
    @SuppressWarnings("deprecation")
    private static AiMatch.Result playCapturing(List<Deck> decks, List<String> names, List<AiConfig> configs, int aiTimeout,
                                                int maxTurns, java.util.function.Consumer<String> log) {
        List<forge.game.player.RegisteredPlayer> players = new java.util.ArrayList<>();
        List<forge.LobbyPlayer> lobby = new java.util.ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            forge.game.player.RegisteredPlayer rp = forge.game.player.RegisteredPlayer.forCommander(decks.get(i));
            forge.LobbyPlayer lp = newLobby(configs.get(i), names.get(i));
            lobby.add(lp);
            rp.setPlayer(lp);
            players.add(rp);
        }
        forge.game.GameRules rules = mtgplayer.match.CommanderRules.create();
        forge.game.Match match = new forge.game.Match(rules, players, "AI Commander");
        Game game = match.createGame();
        game.AI_TIMEOUT = aiTimeout;
        MAIN_GAME = game;
        LOBBY = lobby;
        int[] seen = {0};
        java.util.Observer observer = (o, arg) -> {
            List<forge.game.GameLogEntry> all = game.getGameLog().getAllEntries();
            for (; seen[0] < all.size(); seen[0]++) log.accept(all.get(seen[0]).message());
        };
        game.getGameLog().addObserver(observer);
        game.subscribeToEvents(new Object() {
            @com.google.common.eventbus.Subscribe
            public void onTurnEnded(forge.game.event.GameEventTurnEnded e) {
                if (!game.isGameOver() && game.getPhaseHandler().getTurn() >= maxTurns) {
                    for (forge.game.player.Player p : game.getPlayers()) p.intentionalDraw();
                    game.setGameOver(forge.game.GameEndReason.Draw);
                }
            }
        });
        try {
            match.startGame(game);
        } finally {
            game.getGameLog().deleteObserver(observer);
        }
        forge.game.GameOutcome outcome = game.getOutcome();
        String winner = outcome == null || outcome.getWinCondition() == forge.game.GameEndReason.Draw || outcome.getWinningPlayer() == null
                ? null : outcome.getWinningPlayer().getPlayer().getName();
        String reason = outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition());
        int t = outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber();
        // Wegwerf-Sonde: turnCapped wird hier nicht nachverfolgt (die Sonde wertet nur Speicher/Laufzeit
        // aus, siehe .superpowers/sdd/sim-oom-investigation.md), false ist unschaedlich.
        return new AiMatch.Result(winner, reason, t, false);
    }

    static final java.util.concurrent.atomic.AtomicLong COPIES = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong PURGES = new java.util.concurrent.atomic.AtomicLong();

    /** Hypothesen-Test: LobbyPlayerAi-Unterklasse, die bei jeder (Kopie-)Spielerzeugung AiCache leert. */
    private static forge.LobbyPlayer newLobby(AiConfig cfg, String name) {
        int every = Integer.getInteger("probe.purgeEvery", 0); // 0 = aus (Original-Verhalten)
        java.util.Set<forge.ai.AIOption> opts = switch (cfg.mode()) {
            case STANDARD -> null;
            case HYBRID -> java.util.Set.of(forge.ai.AIOption.USE_HYBRID_SIMULATION);
            case SIM -> java.util.Set.of(forge.ai.AIOption.USE_FULL_SIMULATION);
        };
        forge.ai.LobbyPlayerAi lp = every <= 0 ? new forge.ai.LobbyPlayerAi(name, opts) : new forge.ai.LobbyPlayerAi(name, opts) {
            @Override
            public forge.game.player.Player createIngamePlayer(Game game, int id) {
                if (MAIN_GAME != null && game != MAIN_GAME && id == 0) {
                    long n = COPIES.incrementAndGet();
                    if (n % every == 0) { forge.ai.AiCache.clear(); PURGES.incrementAndGet(); }
                }
                return super.createIngamePlayer(game, id);
            }
        };
        lp.setAiProfile(cfg.profile());
        return lp;
    }

    private static void maybeWalk(long usedMb) {
        int at = Integer.getInteger("probe.walkAtMB", -1);
        if (at < 0 || usedMb < at || MAIN_GAME == null || !WALKED.compareAndSet(false, true)) return;
        System.out.println("[probe] starte RetentionWalker bei used=" + usedMb + " MB");
        List<Object> roots = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        roots.add(MAIN_GAME); names.add("MAIN_GAME");
        roots.add(MAIN_GAME.getMatch()); names.add("MAIN_MATCH");
        for (forge.LobbyPlayer lp : LOBBY) { roots.add(lp); names.add("LOBBY:" + lp.getName()); }
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            try {
                java.lang.reflect.Field f = Thread.class.getDeclaredField("threadLocals");
                f.setAccessible(true);
                Object tl = f.get(t);
                if (tl != null) { roots.add(tl); names.add("THREADLOCALS:" + t.getName()); }
            } catch (Throwable ignored) { }
        }
        RetentionWalker.walk(MAIN_GAME, roots, names, SimMemoryProbe.class.getClassLoader(), 3);
    }

    private static void installGcListener() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            ((NotificationEmitter) gc).addNotificationListener((n, h) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                long before = 0, after = 0;
                for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageBeforeGc().entrySet()) {
                    if (isHeap(e.getKey())) before += e.getValue().getUsed();
                }
                for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                    if (isHeap(e.getKey())) after += e.getValue().getUsed();
                }
                GC_COUNT.incrementAndGet();
                PEAK_BEFORE_GC.accumulateAndGet(before, Math::max);
                // Nur volle/Old-Sammlungen sagen etwas ueber den Live-Set aus; Young-GCs lassen Old unangetastet.
                // Wir nehmen trotzdem jede Sammlung: "nach GC" ist immer eine obere Schranke des Live-Sets zu dem Zeitpunkt.
                PEAK_AFTER_GC.accumulateAndGet(after, Math::max);
            }, null, null);
        }
    }

    private static boolean isHeap(String pool) {
        return pool.contains("Eden") || pool.contains("Survivor") || pool.contains("Old") || pool.contains("Tenured");
    }

    private static void report(String label) {
        Runtime rt = Runtime.getRuntime();
        System.out.printf("[probe] %s: used=%d MB total=%d MB max=%d MB%n", label,
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20);
    }

    private static void gcAndReport(String label) throws InterruptedException {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(300); }
        report(label);
    }

    private static void threads(String label) {
        StringBuilder sb = new StringBuilder("[probe] Threads " + label + ":");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isDaemon() && t.getName().startsWith("ForkJoinPool")) continue;
            sb.append(' ').append(t.getName()).append('(').append(t.getState()).append(')');
        }
        System.out.println(sb);
    }

    private static void histo(String file) throws IOException, InterruptedException {
        long pid = ProcessHandle.current().pid();
        Process p = new ProcessBuilder("jmap", "-histo:live", Long.toString(pid))
                .redirectOutput(Path.of(file).toFile()).redirectErrorStream(true).start();
        p.waitFor();
        List<String> top = Files.readAllLines(Path.of(file));
        System.out.println("[probe] jmap -histo:live -> " + file + " (" + top.size() + " Zeilen), Top 12:");
        top.stream().limit(15).forEach(l -> System.out.println("    " + l));
        top.stream().filter(l -> l.contains("forge.game.Game ") || l.endsWith("forge.game.Game")
                || l.contains("forge.game.card.Card ") || l.endsWith("forge.game.card.Card")
                || l.contains("forge.game.player.Player ") || l.endsWith("forge.game.player.Player"))
                .forEach(l -> System.out.println("    " + l));
    }
}
