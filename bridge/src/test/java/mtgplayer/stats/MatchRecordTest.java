package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

/**
 * {@link MatchRecord#withoutTimeline()}: fuer die schlanke "matches"-Liste (siehe
 * {@code mtgplayer.server.Bridge}, Task 3) muss die Zeitachse je Sitz beim Serialisieren komplett
 * fehlen - nicht als leere Liste dastehen, das hiesse "geladen, aber leer" statt "nicht geladen".
 */
class MatchRecordTest {

    private static MatchRecord record(String id) {
        MatchRecord.Seat seat = new MatchRecord.Seat("Du", "Titania, Gaea Incarnate", true, null, true, null,
                null, 1, 7, List.of(0, 1, 2, 2, 3), 2, 4, 12, 34,
                1, 0, 2, 9, 3, 6, 5, 2, 3, 4, 1, 2,
                8, 5, 6, 3, 4, 2, 6, 6, 15, 6, 4, 7,
                List.of(new MatchRecord.TurnPoint(1, 1, 0, 40, 6, 2),
                        new MatchRecord.TurnPoint(3, 2, 1, 38, 5, 1)),
                2, 2, 4, 21, 18, 12, 22, 0, 5, 2, 3);
        return new MatchRecord(id, "2026-09-22T19:00:00Z", "2026-09-22T19:13:32Z", 812345, "live",
                5, 14, "AllOpponentsLost", false, true, null, List.of(seat));
    }

    @Test
    void withoutTimelineMachtDieZeitachseNullStattLeer() {
        MatchRecord voll = record("m1");
        assertFalse(voll.seats().get(0).timeline().isEmpty(), "Testdaten haben eine nicht-leere Zeitachse");

        MatchRecord ohne = voll.withoutTimeline();
        assertNull(ohne.seats().get(0).timeline(),
                "null, nicht leer - der Client soll daran 'nicht geladen' von 'geladen, aber leer' unterscheiden");
        // Der urspruengliche Datensatz bleibt unveraendert (Record ist unveraenderlich, withoutTimeline
        // baut einen neuen).
        assertFalse(voll.seats().get(0).timeline().isEmpty());
    }

    @Test
    void withoutTimelineLaesstAlleAnderenFelderUnveraendert() {
        MatchRecord voll = record("m1");
        MatchRecord ohne = voll.withoutTimeline();
        assertEquals(voll.id(), ohne.id());
        assertEquals(voll.v(), ohne.v());
        assertEquals(voll.turns(), ohne.turns());
        assertEquals(voll.counted(), ohne.counted());
        assertEquals(1, ohne.seats().size());

        MatchRecord.Seat vs = voll.seats().get(0);
        MatchRecord.Seat os = ohne.seats().get(0);
        assertEquals(vs.name(), os.name());
        assertEquals(vs.spells(), os.spells());
        assertEquals(vs.lifeEnd(), os.lifeEnd());
        assertEquals(vs.landsByTurn(), os.landsByTurn());
    }

    @Test
    void ohneTimelineFaelltDasFeldBeimSerialisierenGanzWeg() {
        MatchRecord ohne = record("m1").withoutTimeline();

        String json = Json.toJson(ohne);
        assertFalse(json.contains("timeline"), "das Feld darf im JSON gar nicht erst auftauchen: " + json);

        JsonNode tree = Json.mapper().valueToTree(ohne);
        assertFalse(tree.at("/seats/0").has("timeline"), tree.toString());
    }

    @Test
    void mitTimelineBleibtSieBeimSerialisierenErhalten() {
        MatchRecord voll = record("m1");
        JsonNode tree = Json.mapper().valueToTree(voll);
        assertTrue(tree.at("/seats/0").has("timeline"), tree.toString());
        assertEquals(2, tree.at("/seats/0/timeline").size(), tree.toString());
    }
}
