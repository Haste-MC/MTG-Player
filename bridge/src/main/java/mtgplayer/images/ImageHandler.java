package mtgplayer.images;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** GET /img/{urlencoded imageKey} → JPEG aus dem ImageCache, sonst 404. */
public final class ImageHandler implements HttpHandler {

    private final ImageCache cache;

    public ImageHandler(ImageCache cache) {
        this.cache = cache;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String key = path.length() > 5 ? URLDecoder.decode(path.substring(5), StandardCharsets.UTF_8) : "";
        Optional<byte[]> img = key.isBlank() ? Optional.empty() : cache.get(key);
        if (img.isEmpty()) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/jpeg");
        ex.getResponseHeaders().add("Cache-Control", "public, max-age=604800");
        ex.sendResponseHeaders(200, img.get().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(img.get());
        }
    }
}
