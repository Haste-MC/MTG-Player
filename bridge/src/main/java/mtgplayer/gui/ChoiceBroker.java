package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import mtgplayer.protocol.Messages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchrone Forge-Dialoge auf dem Game-Thread werden hier zu einer {@code choice}-Nachricht
 * und blockieren, bis {@link #answer} mit derselben id kommt. Antworten sind idempotent.
 * Zwei Threads können gleichzeitig in {@link #ask} stecken (z. B. UI-Thread im Concede-Confirm,
 * Game-Thread in einem Dialog) – deshalb hält {@link #pending()} alle offenen Fragen, nicht nur eine.
 */
public final class ChoiceBroker {

    /** Eine offene Frage samt der Future, die auf ihre Antwort wartet. */
    private record Open(Messages.Choice choice, CompletableFuture<JsonNode> future) { }

    private final Transport out;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, Open> pending = new ConcurrentHashMap<>();

    public ChoiceBroker(Transport out) {
        this.out = out;
    }

    public JsonNode ask(String kind, String title, String message, List<Messages.Option> options, int min, int max, Integer card) {
        int id = seq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        Messages.Choice c = new Messages.Choice(id, kind, title, message, options, min, max, card);
        pending.put(id, new Open(c, f));
        out.send(c);
        try {
            // join() ist nicht unterbrechbar – cancelAll() (aus finishGame) ist der Weg, diesen Aufruf freizugeben.
            return f.join();
        } finally {
            pending.remove(id);
        }
    }

    /**
     * Rein informative Anzeige ohne Antwort (z. B. Forges "reveal", min == max == -1 in
     * {@code getChoices}) – wird nur geschickt, blockiert den Game-Thread nicht und landet
     * nicht in {@link #pending()}.
     */
    public void notify(String kind, String title, String message, List<Messages.Option> options, Integer card) {
        int id = seq.incrementAndGet();
        out.send(new Messages.Choice(id, kind, title, message, options, 0, 0, card));
    }

    /** @return true, wenn eine offene Frage mit dieser id beantwortet wurde. */
    public boolean answer(int id, JsonNode value) {
        Open o = pending.get(id);
        if (o == null) return false;
        return o.future().complete(value == null ? NullNode.getInstance() : value);
    }

    /** Alle aktuell offenen Fragen (aufsteigend nach id), damit ein neu verbundener Browser sie erneut bekommt. */
    public List<Messages.Choice> pending() {
        List<Messages.Choice> out = new ArrayList<>();
        for (Open o : pending.values()) out.add(o.choice());
        out.sort(Comparator.comparingInt(Messages.Choice::id));
        return out;
    }

    /** Beendet alle offenen Fragen mit {@code null} – z. B. bei Spielende. */
    public void cancelAll() {
        for (Open o : pending.values()) {
            o.future().complete(NullNode.getInstance());
        }
    }
}
