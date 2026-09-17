package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import mtgplayer.protocol.Messages;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchrone Forge-Dialoge auf dem Game-Thread werden hier zu einer {@code choice}-Nachricht
 * und blockieren, bis {@link #answer} mit derselben id kommt. Antworten sind idempotent.
 */
public final class ChoiceBroker {

    private final Transport out;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Messages.Choice current;

    public ChoiceBroker(Transport out) {
        this.out = out;
    }

    public JsonNode ask(String kind, String title, String message, List<Messages.Option> options, int min, int max, Integer card) {
        int id = seq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        Messages.Choice c = new Messages.Choice(id, kind, title, message, options, min, max, card);
        current = c;
        out.send(c);
        try {
            return f.join();
        } finally {
            pending.remove(id);
            if (current != null && current.id() == id) {
                current = null;
            }
        }
    }

    /** @return true, wenn eine offene Frage mit dieser id beantwortet wurde. */
    public boolean answer(int id, JsonNode value) {
        CompletableFuture<JsonNode> f = pending.get(id);
        if (f == null) return false;
        return f.complete(value == null ? NullNode.getInstance() : value);
    }

    /** Die aktuell offene Frage, damit ein neu verbundener Browser sie erneut bekommt. */
    public Optional<Messages.Choice> pending() {
        return Optional.ofNullable(current);
    }

    /** Beendet alle offenen Fragen mit {@code null} – z. B. bei Spielende. */
    public void cancelAll() {
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.complete(NullNode.getInstance());
        }
    }
}
