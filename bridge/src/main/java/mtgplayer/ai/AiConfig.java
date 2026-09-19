package mtgplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import java.util.*;

/** KI-Einstellungen eines Sitzes: Forge-Modus (Standard/Hybrid/Voll-Simulation) und Profil (res/ai/*.ai). */
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
        if (!profiles().contains(profile)) throw new IllegalArgumentException("Unbekanntes KI-Profil: " + profile);
    }

    public static List<String> profiles() {
        List<String> all = new ArrayList<>(AiProfileUtil.getAvailableProfiles());
        Collections.sort(all);
        if (all.remove("Default")) all.add(0, "Default");
        return all;
    }
    public static List<String> modes() { return Arrays.stream(Mode.values()).map(Mode::json).toList(); }

    public static AiConfig parse(String spec) {
        String[] parts = spec.split(":", 2);
        return new AiConfig(Mode.parse(parts[0]), parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "Default");
    }
    public static AiConfig fromJson(JsonNode ai) {
        if (ai == null || ai.isMissingNode() || ai.isNull()) return DEFAULT;
        return new AiConfig(Mode.parse(ai.path("mode").asText("standard")), ai.path("profile").asText("Default"));
    }
    public static int timeout(JsonNode msg) {
        int t = msg.path("aiTimeout").asInt(DEFAULT_TIMEOUT);
        if (t < MIN_TIMEOUT || t > MAX_TIMEOUT) throw new IllegalArgumentException("KI-Bedenkzeit muss 1–60 s sein");
        return t;
    }
    public LobbyPlayerAi newLobbyPlayer(String name) {
        Set<AIOption> opts = switch (mode) {
            case STANDARD -> null;
            case HYBRID -> Set.of(AIOption.USE_HYBRID_SIMULATION);
            case SIM -> Set.of(AIOption.USE_FULL_SIMULATION);
        };
        LobbyPlayerAi lp = new LobbyPlayerAi(name, opts);
        lp.setAiProfile(profile);
        return lp;
    }
    public String spec() { return mode.json() + ":" + profile; }
}
