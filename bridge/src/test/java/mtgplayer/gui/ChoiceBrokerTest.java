package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class ChoiceBrokerTest {

    private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final ChoiceBroker broker = new ChoiceBroker(m -> sent.add(Json.toJson(m)));

    @Test
    void askSendetChoiceUndBlockiertBisAnswer() throws Exception {
        List<Messages.Option> opts = List.of(new Messages.Option(0, "A", null, null), new Messages.Option(1, "B", null, null));
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "Titel", "Frage", opts, 1, 1, null));

        JsonNode msg = Json.parse(sent.poll(5, TimeUnit.SECONDS));
        assertEquals("choice", msg.get("type").asText());
        assertEquals("one", msg.get("kind").asText());
        assertEquals(2, msg.get("options").size());
        int id = msg.get("id").asInt();
        assertFalse(result.isDone(), "muss auf die Antwort warten");
        assertEquals(1, broker.pending().size());

        assertTrue(broker.answer(id, Json.parse("1")));
        assertEquals(1, result.get(5, TimeUnit.SECONDS).asInt());
        assertTrue(broker.pending().isEmpty());
    }

    @Test
    void doppelteOderUnbekannteAntwortWirdIgnoriert() throws Exception {
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("confirm", "T", "?", List.of(), 0, 0, null));
        int id = Json.parse(sent.poll(5, TimeUnit.SECONDS)).get("id").asInt();
        assertFalse(broker.answer(id + 99, Json.parse("true")));
        assertTrue(broker.answer(id, Json.parse("true")));
        assertFalse(broker.answer(id, Json.parse("false")), "zweite Antwort auf dieselbe id");
        assertTrue(result.get(5, TimeUnit.SECONDS).asBoolean());
    }

    @Test
    void cancelAllLoestOffeneFragenMitNullAuf() throws Exception {
        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "T", "?", List.of(), 1, 1, null));
        sent.poll(5, TimeUnit.SECONDS);
        broker.cancelAll();
        assertTrue(result.get(5, TimeUnit.SECONDS).isNull());
    }

    @Test
    void pendingEnthaeltMehrereGleichzeitigOffeneFragen() throws Exception {
        CompletableFuture<JsonNode> r1 = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "T1", "?", List.of(), 1, 1, null));
        int id1 = Json.parse(sent.poll(5, TimeUnit.SECONDS)).get("id").asInt();

        CompletableFuture<JsonNode> r2 = CompletableFuture.supplyAsync(
                () -> broker.ask("one", "T2", "?", List.of(), 1, 1, null));
        int id2 = Json.parse(sent.poll(5, TimeUnit.SECONDS)).get("id").asInt();

        assertEquals(2, broker.pending().size());

        assertTrue(broker.answer(id2, Json.parse("1")));
        assertEquals(1, r2.get(5, TimeUnit.SECONDS).asInt());
        assertEquals(1, broker.pending().size());
        assertEquals(id1, broker.pending().get(0).id());

        assertTrue(broker.answer(id1, Json.parse("1")));
        assertEquals(1, r1.get(5, TimeUnit.SECONDS).asInt());
        assertTrue(broker.pending().isEmpty());
    }

    @Test
    void notifySendetRevealUndLandetNichtInPending() throws Exception {
        List<Messages.Option> opts = List.of(new Messages.Option(0, "A", null, null), new Messages.Option(1, "B", null, null));
        broker.notify("Titel", "Frage", opts, null);

        JsonNode msg = Json.parse(sent.poll(5, TimeUnit.SECONDS));
        assertEquals("choice", msg.get("type").asText());
        assertEquals("reveal", msg.get("kind").asText());
        assertEquals(0, msg.get("min").asInt());
        assertEquals(0, msg.get("max").asInt());
        assertTrue(broker.pending().isEmpty());
    }
}
