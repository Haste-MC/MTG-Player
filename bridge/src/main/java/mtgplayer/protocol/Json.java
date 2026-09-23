package mtgplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    /**
     * Ein unbekanntes Feld wird UEBERLESEN, nicht als Fehler behandelt. Sonst kostet ein Rueckschritt
     * auf einen aelteren Bridge-Stand die ganze Partien-Historie: der wirft beim Lesen einer von einer
     * neueren Fassung geschriebenen {@code matches.json}, {@link mtgplayer.stats.MatchStore#all()}
     * meldet daraufhin "kaputte Datei" und liefert eine LEERE Liste - und der naechste Schreibvorgang
     * ersetzt die Datei. Vorwaerts (altes Feld fehlt) liest Jackson ohnehin schon stillschweigend als
     * Standardwert; rueckwaerts genauso zu lesen ist die Gegenrichtung derselben Regel.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() { }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("nicht serialisierbar: " + value.getClass(), e);
        }
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("kein JSON: " + text, e);
        }
    }
}
