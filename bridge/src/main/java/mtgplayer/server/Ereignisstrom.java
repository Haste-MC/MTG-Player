package mtgplayer.server;

import com.sun.net.httpserver.HttpExchange;
import mtgplayer.forge.CrashLog;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Der Rueckfallweg auf dem HTTP-Port, fuer Browser, die keinen WebSocket herausbekommen.
 *
 * <p><b>Anlass.</b> Der eingebaute Browser einer Claude-Sitzung erreichte die App nicht: die Seite
 * laedt ueber {@code localhost:8080}, aber weder {@code 127.0.0.1:8080} noch der WebSocket auf 8081.
 * Dieser Browser hat genau EINE Freigabe - fuer Name und Port der Seite. Also muss alles ueber
 * diesen einen Port gehen.</p>
 *
 * <p>Zwei gewoehnliche HTTP-Wege ersetzen den Socket:
 * <ul>
 * <li>{@code GET /ereignisse} - ein Server-Sent-Events-Strom mit allem, was sonst der Socket
 *     braechte. Die erste Nachricht ist ein {@code hello} mit einem Token.</li>
 * <li>{@code POST /eingabe?token=…} - eine Eingabe des Browsers. Das Token ordnet sie demselben
 *     Klienten zu; eine einzelne HTTP-Anfrage traegt sonst keine Identitaet.</li>
 * </ul>
 *
 * <p>Ein offener Strom belegt dauerhaft einen Faden des HTTP-Servers - deshalb hat {@code HttpStatic}
 * einen wachsenden statt eines festen Fadenvorrats.</p>
 */
public final class Ereignisstrom implements AutoCloseable {

    /** So oft geht eine Kommentarzeile raus: haelt den Strom offen UND merkt einen stillen Abriss. */
    private static final int PING_SEKUNDEN = 20;

    private final Klienten klienten;
    private final Map<String, StromKlient> nachToken = new ConcurrentHashMap<>();
    private final ScheduledExecutorService takt =
            Executors.newSingleThreadScheduledExecutor(HttpStatic.daemonThreads("sse-ping"));

    public Ereignisstrom(Klienten klienten) {
        this.klienten = klienten;
        takt.scheduleWithFixedDelay(this::pingen, PING_SEKUNDEN, PING_SEKUNDEN, TimeUnit.SECONDS);
    }

    /** {@code GET /ereignisse} - blockiert, solange der Browser zuhoert. */
    public void ereignisse(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);          // 0 = Laenge unbekannt, Strom bleibt offen

        StromKlient k = new StromKlient(UUID.randomUUID().toString(), ex.getResponseBody());
        nachToken.put(k.token, k);
        try {
            // Zuerst das Token, dann erst anmelden: die Rollen-Nachricht aus dazu() soll nicht vor
            // dem hello ankommen, sonst weiss der Browser noch nicht, wem sie gilt.
            k.sende(Json.toJson(new Messages.Hello(k.token)));
            klienten.dazu(k);
            k.warteBisEnde();
        } catch (Exception e) {
            CrashLog.note("sse", "Strom beendet: " + e, e);
        } finally {
            nachToken.remove(k.token);
            klienten.weg(k);
            ex.close();
        }
    }

    /** {@code POST /eingabe?token=…} - dieselbe JSON-Nachricht wie ueber den Socket. */
    public void eingabe(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            StromKlient k = nachToken.get(token(ex.getRequestURI().getQuery()));
            if (k == null) {
                // Unbekanntes Token: der Browser baut daraufhin den Strom neu auf.
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String rumpf = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            klienten.empfange(k, Json.parse(rumpf));
            ex.sendResponseHeaders(204, -1);
        } catch (RuntimeException e) {
            CrashLog.note("sse", "Eingabe fehlgeschlagen: " + e, e);
            ex.sendResponseHeaders(400, -1);
        } finally {
            ex.close();
        }
    }

    /** {@code token=abc&x=1} → {@code abc}; ohne Treffer {@code ""}. */
    static String token(String query) {
        if (query == null) {
            return "";
        }
        for (String teil : query.split("&")) {
            if (teil.startsWith("token=")) {
                return teil.substring("token=".length());
            }
        }
        return "";
    }

    /** Haelt die Stroeme offen und entfernt die, die stillschweigend abgerissen sind. */
    private void pingen() {
        for (StromKlient k : nachToken.values()) {
            try {
                k.kommentar();
            } catch (Exception e) {
                k.schliesse();
                nachToken.remove(k.token);
                klienten.weg(k);
            }
        }
    }

    @Override
    public void close() {
        takt.shutdownNow();
        for (StromKlient k : nachToken.values()) {
            k.schliesse();
        }
        nachToken.clear();
    }

    /** Ein Browser am Ereignisstrom. */
    private static final class StromKlient implements Klient {
        private final String token;
        private final OutputStream out;
        private final CountDownLatch ende = new CountDownLatch(1);
        private volatile boolean offen = true;

        StromKlient(String token, OutputStream out) {
            this.token = token;
            this.out = out;
        }

        /** Synchronisiert: der Spiel-Thread und der Takt schreiben in denselben Strom. */
        @Override
        public synchronized void sende(String json) throws IOException {
            // Zeilenumbrueche im JSON gibt es nicht (Jackson schreibt kompakt) - sonst muesste jede
            // Zeile ihr eigenes "data: " bekommen, so verlangt es das Format.
            out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void kommentar() throws IOException {
            out.write(":ping\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public boolean offen() {
            return offen;
        }

        @Override
        public void schliesse() {
            offen = false;
            ende.countDown();
        }

        void warteBisEnde() throws InterruptedException {
            ende.await();
        }
    }
}
