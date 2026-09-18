package mtgplayer.images;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * GET/HEAD /img/{urlencoded imageKey} → JPEG aus dem ImageCache, sonst 404.
 *
 * <p>Threading: der JDK-{@code HttpServer} hat pro Server genau einen Executor (siehe
 * {@link mtgplayer.server.HttpStatic}), der auch die statischen {@code /}-Requests bedient. Ein
 * langsamer Scryfall-Download darf diesen Pool nicht blockieren, sonst hungert er die restliche
 * Seite aus. Deshalb reicht {@link #handle} die {@link HttpExchange} sofort an einen eigenen,
 * fest dimensionierten {@link ExecutorService} weiter und kehrt selbst zurück; der Server-Thread
 * ist damit sofort wieder frei. Der eigentliche Request (inkl. eventuellem Download über
 * {@link ImageCache#get}) läuft im Pool-Thread, der die Exchange auch abschließt – das ist beim
 * JDK-{@code HttpServer} zulässig, die Exchange muss nicht vom Server-Thread selbst beendet
 * werden.</p>
 *
 * <p>Der Pool laeuft als Daemon-Threads, damit ein fehlgeschlagener Start die JVM nicht am
 * Leben haelt; {@link #close()} faehrt ihn trotzdem sauber herunter (wird von
 * {@link mtgplayer.server.HttpStatic#stop()} fuer alle {@link AutoCloseable}-Handler aufgerufen).</p>
 */
public final class ImageHandler implements HttpHandler, AutoCloseable {

    private static final ThreadFactory DAEMON_THREADS = r -> {
        Thread t = new Thread(r, "image-handler-" + System.identityHashCode(r));
        t.setDaemon(true);
        return t;
    };

    private final ImageCache cache;
    private final ExecutorService imagePool = Executors.newFixedThreadPool(8, DAEMON_THREADS);

    public ImageHandler(ImageCache cache) {
        this.cache = cache;
    }

    @Override
    public void close() {
        imagePool.shutdownNow();
        try {
            imagePool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void handle(HttpExchange ex) {
        imagePool.submit(() -> serve(ex));
    }

    private void serve(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                ex.sendResponseHeaders(405, -1);
                ex.close();
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            String raw = path.length() > 5 ? path.substring(5) : "";
            // Der Client kodiert mit JS encodeURIComponent, das ein woertliches '+' im Key als
            // "%2B" ueberträgt (encodeURIComponent laesst '+' NICHT unangetastet) - ein roher,
            // unkodierter '+' im Pfad kann also nur von einem anderen Absender stammen. URLDecoder
            // wuerde so ein rohes '+' faelschlich als Leerzeichen lesen, deshalb wird es vorsorglich
            // zu "%2B" escaped, bevor decodiert wird.
            String key = raw.isEmpty() ? "" : URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            Optional<byte[]> img = key.isBlank() ? Optional.empty() : cache.get(key);
            if (img.isEmpty()) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.getResponseHeaders().add("Cache-Control", "public, max-age=604800");
            if ("HEAD".equals(method)) {
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, img.get().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(img.get());
            }
        } catch (IOException e) {
            System.err.println("[images] " + ex.getRequestURI() + ": " + e);
            ex.close();
        } catch (RuntimeException e) {
            e.printStackTrace();
            try {
                ex.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // Verbindung vermutlich schon weg – nichts mehr zu tun
            } finally {
                ex.close();
            }
        }
    }
}
