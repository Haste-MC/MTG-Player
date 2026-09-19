package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AiConfigTest {
    @BeforeAll static void boot() { ForgeBoot.init(); }

    @Test void profileSindGeladenDefaultZuerst() {
        assertEquals("Default", AiConfig.profiles().get(0));
        assertTrue(AiConfig.profiles().containsAll(java.util.List.of("Cautious", "Reckless", "Experimental")));
    }

    @Test void parseSpecs() {
        assertEquals(new AiConfig(AiConfig.Mode.SIM, "Reckless"), AiConfig.parse("sim:Reckless"));
        assertEquals(AiConfig.DEFAULT, AiConfig.parse("std"));
        assertEquals(new AiConfig(AiConfig.Mode.HYBRID, "Cautious"), AiConfig.parse("hybrid:Cautious"));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.parse("turbo"));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.parse("sim:Nope"));
    }

    @Test void fromJsonUndTimeout() {
        JsonNode msg = Json.parse("{\"aiTimeout\":12,\"opponents\":[{\"precon\":\"x\",\"ai\":{\"mode\":\"sim\"}},{\"precon\":\"y\"}]}");
        assertEquals(new AiConfig(AiConfig.Mode.SIM, "Default"), AiConfig.fromJson(msg.path("opponents").get(0).path("ai")));
        assertEquals(AiConfig.DEFAULT, AiConfig.fromJson(msg.path("opponents").get(1).path("ai")));
        assertEquals(12, AiConfig.timeout(msg));
        assertEquals(5, AiConfig.timeout(Json.parse("{}")));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.timeout(Json.parse("{\"aiTimeout\":0}")));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.fromJson(Json.parse("{\"profile\":\"Nope\"}")));
    }

    @Test void lobbyPlayerTraegtOptionUndProfil() {
        LobbyPlayerAi lp = new AiConfig(AiConfig.Mode.SIM, "Reckless").newLobbyPlayer("KI 1");
        assertEquals("Reckless", lp.getAiProfile());
        // Option ist privat – ueber einen erzeugten Controller pruefen (createIngamePlayer braucht ein Game).
        Game game = new forge.game.Match(mtgplayer.match.CommanderRules.create(), java.util.List.of(), "t").createGame();
        Player p = lp.createIngamePlayer(game, 0);
        assertTrue(((PlayerControllerAi) p.getController()).getAi().usesFullSimulation());
        Player q = AiConfig.DEFAULT.newLobbyPlayer("KI 2").createIngamePlayer(game, 1);
        assertFalse(((PlayerControllerAi) q.getController()).getAi().usesFullSimulation());
        assertFalse(((PlayerControllerAi) q.getController()).getAi().usesHybridSimulation());
    }
}
