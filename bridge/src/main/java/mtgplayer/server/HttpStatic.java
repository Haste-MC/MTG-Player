package mtgplayer.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Liefert das gebaute Frontend (web/dist). Unbekannte Pfade fallen auf index.html zurück (SPA).
 *
 * <p>Threading: der JDK-{@code HttpServer} nutzt einen einzigen Executor für alle Contexts eines
 * Servers. {@code /img/} (siehe {@link mtgplayer.images.ImageHandler}) gibt Requests sofort an
 * einen eigenen Pool ab, damit langsame Scryfall-Downloads diesen Server-Pool nicht belegen; der
 * Pool hier ist trotzdem auf 8 Threads angehoben, damit `/`-Requests immer einen freien Thread
 * finden, auch wenn der Image-Pool gleichzeitig ausgelastet ist.</p>
 */
public final class HttpStatic {

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript", "css", "text/css",
            "json", "application/json", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    private final HttpServer server;
    private final Path dir;

    public HttpStatic(int port, Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress(bindAddress(), port), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String rel = p.length() > 1 ? p.substring(1) : "";
            Path f = this.dir.resolve(rel).normalize();
            if (!f.startsWith(this.dir) || !Files.isRegularFile(f)) {
                f = this.dir.resolve("index.html");
            }
            if (!Files.isRegularFile(f)) {
                byte[] msg = "web/dist fehlt – erst `npm run build` in web/ oder Vite-Dev-Server auf :5173 nutzen".getBytes();
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            String name = f.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            ex.getResponseHeaders().add("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
            byte[] body = Files.readAllBytes(f);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
    }

    /** Bind-Adresse: -Dmtgplayer.bind, Standard 0.0.0.0 – noetig, damit Windows unter WSL2 den Server per localhost erreicht. */
    static String bindAddress() {
        return System.getProperty("mtgplayer.bind", "0.0.0.0");
    }

    public void addContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler);
    }

    public void start() {
        server.start();
        System.out.println("HTTP auf http://" + bindAddress() + ":" + server.getAddress().getPort() + " (" + dir + ")");
    }

    public void stop() {
        server.stop(0);
    }
}
