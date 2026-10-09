package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.player.Player;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Der Datensatz haelt die Teamnummer je Sitz - aber NUR in einer Team-Partie. Forges {@code Game}
 * vergibt jedem Sitz von sich aus eine eigene Nummer (0, 1, 2 ...), auch im Jeder-gegen-jeden; ein
 * blosses Durchreichen von {@code Player.getTeam()} wuerde also jede gewoehnliche Partie als
 * Team-Partie ausweisen. Diese Tests halten die Regel "Team nur, wenn sich mindestens zwei Sitze eine
 * Nummer teilen" fest.
 */
class MatchRecorderTeamTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static List<Integer> teams(MatchRecord r) {
        return r.seats().stream().map(MatchRecord.Seat::team).toList();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void teamPartieHaeltJedemSitzSeinTeam() {
        Scene s = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), 3);
        MatchRecord r = new MatchRecorder(s.game(), "spectate", null).finish();

        assertEquals(List.of(1, 1, 2, 2), teams(r), "die Nummern kommen aus der Engine, Sitz fuer Sitz");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void duellOhneTeamsSchreibtKeinTeam_obwohlDieEngineNummernVergibt() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        // Die Voraussetzung des Tests: die Engine hat tatsaechlich verschiedene Nummern vergeben -
        // sonst bewiese das Ergebnis unten nichts.
        List<Integer> engine = s.game().getPlayers().stream().map(Player::getTeam).toList();
        assertNotEquals(engine.get(0), engine.get(1), "Forge nummeriert die Sitze selbst durch");

        MatchRecord r = new MatchRecorder(s.game(), "spectate", null).finish();

        assertTrue(r.seats().stream().allMatch(x -> x.team() == null),
                "ohne geteilte Nummer ist es keine Team-Partie, sonst sahe jedes Duell nach Teams aus: " + teams(r));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void dreiSitzeJederGegenJedenSchreibenKeinTeam() {
        Scene s = Scene.of(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT), 3);
        // Die Voraussetzung des Tests: die Engine hat jedem Sitz eine eigene Nummer gegeben. Hoerte Forge
        // auf zu nummerieren (alle -1 oder alle gleich), bliebe das Ergebnis unten aus dem falschen Grund gruen.
        assertEquals(3, s.game().getPlayers().stream().map(Player::getTeam).collect(Collectors.toSet()).size(),
                "Forge nummeriert jeden der drei Sitze selbst durch");
        MatchRecord r = new MatchRecorder(s.game(), "spectate", null).finish();

        assertEquals(3, r.seats().size());
        assertTrue(r.seats().stream().allMatch(x -> x.team() == null), "Free-for-all: " + teams(r));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void datensatzTraegtVersion4() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecord r = new MatchRecorder(s.game(), "spectate", null).finish();

        assertEquals(4, r.v(), "das Team-Feld ist neu - die Auswertung erkennt daran, dass es existieren kann");
        assertEquals(4, MatchRecord.VERSION);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void teamSteht_imJsonNurBeiTeamPartien_undAeltereDatensaetzeLesenSichAlsKeinTeam() throws Exception {
        Scene duell = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Scene teams = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), 3);
        MatchRecord ohne = new MatchRecorder(duell.game(), "spectate", null).finish();
        MatchRecord mit = new MatchRecorder(teams.game(), "spectate", null).finish();

        String jsonOhne = Json.toJson(ohne);
        assertFalse(jsonOhne.contains("\"team\""),
                "eine gewoehnliche Partie bleibt im Datensatz byte-gleich zu vor dem Team-Modus");
        assertEquals(1, Json.parse(Json.toJson(mit)).get("seats").get(0).get("team").asInt());

        // Rueckwaerts: ein Datensatz ohne das Feld (v1-v3) liest sich als "kein Team", nicht als 0 oder -1.
        MatchRecord zurueck = Json.mapper().readValue(jsonOhne, MatchRecord.class);
        assertNull(zurueck.seats().get(0).team());
    }
}
