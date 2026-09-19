package mtgplayer.bench;

import java.nio.file.Path;
import java.util.List;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;

/**
 * Parameter eines Bench-Laufs, siehe README Abschnitt "Bench (KI gegen KI)". {@code parse} braucht
 * {@code ForgeBoot.init()}, sobald Deck-Defaults ueber {@link Precons#names()} ermittelt werden muessen.
 */
public record BenchArgs(int games, AiConfig a, AiConfig b, String deckA, String deckB, int turns, int timeout,
                         long seed, Path out) {

    private static final int DEFAULT_GAMES = 40;
    private static final int DEFAULT_TURNS = 200;

    /**
     * Argumente paarweise {@code --key value}. Aus {@code Main} kommen Schluessel und Wert als zwei
     * getrennte Array-Eintraege (normales argv – Maven/die Shell trennen an Leerzeichen, ein Wert mit
     * Leerzeichen wie ein Precon-Name muss deshalb in {@code -Dexec.args} zitiert werden). Ein einzelner
     * Eintrag darf Schluessel und Wert auch schon zusammen enthalten ({@code "--deck-a precon:Foo Bar"}) –
     * so baut sich z. B. {@code BenchArgsTest} sein Test-Array bequem aus einem einzigen String zusammen.
     */
    public static BenchArgs parse(String[] args) {
        int games = DEFAULT_GAMES;
        AiConfig a = AiConfig.parse("sim");
        AiConfig b = AiConfig.DEFAULT;
        String deckA = null;
        String deckB = null;
        int turns = DEFAULT_TURNS;
        int timeout = AiConfig.DEFAULT_TIMEOUT;
        long seed = System.currentTimeMillis();
        Path out = ForgeBoot.dataDir().resolve("bench");

        int i = 0;
        while (i < args.length) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Erwarte eine Option (--…), war: " + token);
            }
            String key;
            String value;
            int sp = token.indexOf(' ');
            if (sp >= 0) {
                key = token.substring(0, sp);
                value = token.substring(sp + 1);
                i += 1;
            } else {
                key = token;
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Wert fehlt fuer " + key);
                }
                value = args[i + 1];
                i += 2;
            }
            switch (key) {
                case "--games" -> games = Integer.parseInt(value);
                case "--a" -> a = AiConfig.parse(value);
                case "--b" -> b = AiConfig.parse(value);
                case "--deck-a" -> deckA = value;
                case "--deck-b" -> deckB = value;
                case "--turns" -> turns = Integer.parseInt(value);
                case "--timeout" -> timeout = Integer.parseInt(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--out" -> out = Path.of(value);
                default -> throw new IllegalArgumentException("Unbekannte Bench-Option: " + key);
            }
        }
        if (games <= 0) {
            throw new IllegalArgumentException("--games muss > 0 sein");
        }
        if (turns <= 0) {
            throw new IllegalArgumentException("--turns muss > 0 sein");
        }
        if (timeout < AiConfig.MIN_TIMEOUT || timeout > AiConfig.MAX_TIMEOUT) {
            throw new IllegalArgumentException("--timeout muss " + AiConfig.MIN_TIMEOUT + "-" + AiConfig.MAX_TIMEOUT + " sein");
        }
        if (deckA == null || deckB == null) {
            List<String> precons = Precons.names();
            if (precons.size() < 2) {
                throw new IllegalStateException("mindestens zwei Precons noetig fuer Bench-Standarddecks");
            }
            if (deckA == null) {
                deckA = "precon:" + precons.get(0);
            }
            if (deckB == null) {
                deckB = "precon:" + precons.get(1);
            }
        }
        return new BenchArgs(games, a, b, deckA, deckB, turns, timeout, seed, out);
    }
}
