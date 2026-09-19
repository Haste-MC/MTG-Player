package mtgplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.AiCache;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** KI-Einstellungen eines Sitzes: Forge-Modus (Standard/Hybrid/Voll-Simulation) und Profil (res/ai/*.ai).
 *  {@code parse}/{@code fromJson}/{@code profiles()} brauchen {@code ForgeBoot.init()}; der Konstruktor und
 *  {@code DEFAULT} nicht. */
public record AiConfig(Mode mode, String profile) {
    public enum Mode {
        STANDARD, HYBRID, SIM;
        public String json() { return name().toLowerCase(Locale.ROOT); }
        public static Mode parse(String s) {
            return switch (s == null ? "" : s.trim().toLowerCase(Locale.ROOT)) {
                case "standard", "std", "" -> STANDARD;
                case "hybrid" -> HYBRID;
                case "sim", "simulation" -> SIM;
                default -> throw new IllegalArgumentException("Unbekannter KI-Modus: " + s);
            };
        }
    }
    public static final AiConfig DEFAULT = new AiConfig(Mode.STANDARD, "Default");
    public static final int DEFAULT_TIMEOUT = 5, MIN_TIMEOUT = 1, MAX_TIMEOUT = 60;

    public AiConfig {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(profile);
    }

    /** Profilpruefung ausgelagert aus dem Konstruktor: {@code profiles()} greift auf Forges
     *  AiProfileUtil zu und braeuchte damit ForgeBoot.init() schon beim Klassenstart, weil
     *  {@link #DEFAULT} eine statische Instanz ist - ein Konstruktoraufruf vor ForgeBoot.init()
     *  (z. B. durch den blossen Zugriff auf diese Klasse) haette sonst den statischen Initialisierer
     *  mit einer NPE zum Absturz gebracht und die Klasse fuer den Rest der JVM vergiftet
     *  (ExceptionInInitializerError, danach NoClassDefFoundError bei jedem weiteren Zugriff). */
    private static AiConfig validated(AiConfig c) {
        if (!profiles().contains(c.profile())) {
            throw new IllegalArgumentException("Unbekanntes KI-Profil: " + c.profile());
        }
        return c;
    }

    public static List<String> profiles() {
        List<String> all = new ArrayList<>(AiProfileUtil.getAvailableProfiles());
        Collections.sort(all);
        if (all.remove("Default")) all.add(0, "Default");
        return all;
    }
    public static List<String> modes() { return Arrays.stream(Mode.values()).map(Mode::json).toList(); }

    public static AiConfig parse(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Unbekannter KI-Modus: null");
        }
        String[] parts = spec.split(":", 2);
        return validated(new AiConfig(Mode.parse(parts[0]), parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "Default"));
    }
    public static AiConfig fromJson(JsonNode ai) {
        if (ai == null || ai.isMissingNode() || ai.isNull()) return DEFAULT;
        if (!ai.isObject()) throw new IllegalArgumentException("ai muss ein Objekt sein");
        return validated(new AiConfig(Mode.parse(ai.path("mode").asText("standard")), ai.path("profile").asText("Default")));
    }
    public static int timeout(JsonNode msg) {
        JsonNode aiTimeout = msg.path("aiTimeout");
        if (!aiTimeout.isMissingNode() && !aiTimeout.isNull() && !aiTimeout.isInt()) {
            throw new IllegalArgumentException("KI-Bedenkzeit muss eine ganze Zahl sein");
        }
        int t = aiTimeout.asInt(DEFAULT_TIMEOUT);
        if (t < MIN_TIMEOUT || t > MAX_TIMEOUT) throw new IllegalArgumentException("KI-Bedenkzeit muss 1–60 s sein");
        return t;
    }
    public LobbyPlayerAi newLobbyPlayer(String name) {
        Set<AIOption> opts = switch (mode) {
            case STANDARD -> null;
            case HYBRID -> Set.of(AIOption.USE_HYBRID_SIMULATION);
            case SIM -> Set.of(AIOption.USE_FULL_SIMULATION);
        };
        // Forges statischer Memo-Cache AiCache haelt seine Eintraege (u. a. Game/Player-Argumente) per
        // starker Referenz und wird sonst nur einmal pro Entscheidung des ORIGINALSPIELS geleert
        // (AiController.chooseSpellAbilityToPlay). In der Voll-Simulation (sim) entstehen innerhalb
        // EINER Entscheidung tausende Spielkopien (GameSimulator/GameCopier), die der Cache dann alle
        // festhaelt - Live-Set-Spitze > 12 GB, OutOfMemoryError. Der Game-Konstruktor ruft
        // createIngamePlayer(game, id) fuer jede Kopie ueber dasselbe LobbyPlayer-Objekt auf
        // (GameCopier.clonePlayer reicht es durch); wir leeren den Cache dort bei JEDER Kopie, unabhaengig
        // von der id. Eine feste id (z. B. 0) waere kein verlaesslicher Marker fuer "das ist unser Sitz":
        // HostedMatch sortiert den Menschen auf Platz 0, und GameCopier.clonePlayer ersetzt
        // LobbyPlayerHuman in der Kopie durch eine FREMDE (Forges eigene) LobbyPlayerAi - unsere
        // Unterklasse bleibt zwar erhalten, sitzt in der Kopie aber weiterhin auf ihrer id aus dem
        // Originalspiel, also >= 1, sobald ein Mensch am Tisch sitzt (id == 0 haette dort nie gefeuert).
        // Der Cache ist global (static), das Leeren wirkt also fuer alle Sitze gleich; da der Konstruktor
        // vor jeder Bewertung dieser Kopie laeuft, ist das unproblematisch. n Aufrufe je Kopie auf einer
        // synchronisierten Map sind vernachlaessigbar und aendern keine Entscheidung (reines Memo) - siehe
        // .superpowers/sdd/sim-oom-investigation.md (Lauf 4-7: Live-Set-Spitze faellt von >12 GB auf
        // ~1,6 GB, Laufzeit unveraendert; gilt auch fuer menschliche Spiele mit Sim-KI).
        LobbyPlayerAi lp = new LobbyPlayerAi(name, opts) {
            @Override
            public Player createIngamePlayer(Game game, int id) {
                AiCache.clear();
                return super.createIngamePlayer(game, id);
            }
        };
        lp.setAiProfile(profile);
        return lp;
    }
    public String spec() { return mode.json() + ":" + profile; }
}
