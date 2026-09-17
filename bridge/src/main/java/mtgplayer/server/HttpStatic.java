package mtgplayer.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Liefert das gebaute Frontend (web/dist). Unbekannte Pfade fallen auf index.html zurück (SPA). */
public final class HttpStatic {

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript", "css", "text/css",
            "json", "application/json", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    private final HttpServer server;
    private final Path dir;

    public HttpStatic(int port, Path dir) throws IOException {
        this.dir = dir;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            Path f = dir.resolve(p.substring(1)).normalize();
            if (!f.startsWith(dir) || !Files.isRegularFile(f)) {
                f = dir.resolve("index.html");
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

    public void start() {
        server.start();
        System.out.println("HTTP auf http://127.0.0.1:" + server.getAddress().getPort() + " (" + dir + ")");
    }

    public void stop() {
        server.stop(0);
    }
}
