package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Wer steuert den Tisch. Mit Attrappen statt Netz: die Regeln sind das Interessante, nicht der
 * Transport - und ueber beide Wege (WebSocket und Ereignisstrom) gelten dieselben.
 */
class KlientenTest {

    /** Ein Klient, der mitschreibt; {@code kaputt} laesst jedes Senden scheitern. */
    private static final class Attrappe implements Klient {
        final String name;
        final List<String> empfangen = new ArrayList<>();
        boolean offen = true;
        boolean kaputt;

        Attrappe(String name) { this.name = name; }

        @Override public void sende(String json) throws Exception {
            if (kaputt) throw new IllegalStateException("Leitung hin");
            empfangen.add(json);
        }
        @Override public boolean offen() { return offen; }
        @Override public void schliesse() { offen = false; }

        boolean steuertLautLetzterRolle() {
            for (int i = empfangen.size() - 1; i >= 0; i--) {
                if (empfangen.get(i).contains("\"type\":\"role\"")) {
                    return empfangen.get(i).contains("\"control\":true");
                }
            }
            throw new AssertionError("keine Rollen-Nachricht bei " + name + ": " + empfangen);
        }
    }

    private final List<String> gehoert = new ArrayList<>();
    private final List<String> letzterWeg = new ArrayList<>();
    private final Klienten klienten = new Klienten(m -> gehoert.add(m.path("type").asText()), () -> letzterWeg.add("x"));

    private Attrappe dazu(String name) {
        Attrappe a = new Attrappe(name);
        klienten.dazu(a);
        return a;
    }

    @Test
    void derErsteSteuertUndDieUebrigenSehenZu() {
        Attrappe eins = dazu("eins");
        assertTrue(eins.steuertLautLetzterRolle());
        Attrappe zwei = dazu("zwei");
        assertTrue(eins.steuertLautLetzterRolle(), "der erste bleibt dran");
        assertFalse(zwei.steuertLautLetzterRolle(), "der zweite sieht zu");
        assertSame(eins, klienten.steuernder());
    }

    @Test
    void nurDerSteuerndeWirdDurchgereicht() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        klienten.empfange(zwei, Json.parse("{\"type\":\"vomZuschauer\"}"));
        klienten.empfange(eins, Json.parse("{\"type\":\"vomSteuernden\"}"));
        assertEquals(List.of("vomSteuernden"), gehoert);
    }

    @Test
    void takeControlDarfJederUndDrehtDieRollenUm() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        klienten.empfange(zwei, Json.parse("{\"type\":\"takeControl\"}"));
        assertTrue(zwei.steuertLautLetzterRolle());
        assertFalse(eins.steuertLautLetzterRolle());
        assertEquals(List.of(), gehoert, "takeControl selbst geht nicht an die Bridge");

        klienten.empfange(zwei, Json.parse("{\"type\":\"jetztIch\"}"));
        assertEquals(List.of("jetztIch"), gehoert);
    }

    @Test
    void gehtDerSteuerndeRuecktDerNaechsteNach() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        klienten.weg(eins);
        assertTrue(zwei.steuertLautLetzterRolle());
        assertSame(zwei, klienten.steuernder());
        assertEquals(List.of(), letzterWeg, "einer ist noch da");
    }

    @Test
    void erstDerLetzteAbgangWirdGemeldet() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        klienten.weg(zwei);
        assertEquals(List.of(), letzterWeg);
        klienten.weg(eins);
        assertEquals(List.of("x"), letzterWeg, "jetzt ist niemand mehr da");
    }

    @Test
    void alleBekommenDieNachricht() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        klienten.sendeAllen(new mtgplayer.protocol.Messages.ErrorMsg("hallo"));
        assertTrue(eins.empfangen.stream().anyMatch(m -> m.contains("hallo")));
        assertTrue(zwei.empfangen.stream().anyMatch(m -> m.contains("hallo")));
    }

    /**
     * Beim Ereignisstrom ist ein fehlgeschlagener Schreibvorgang die EINZIGE Art, einen
     * stillschweigend abgerissenen Browser zu bemerken - also muss er den Klienten kosten, sonst
     * zaehlt er ewig als Zuschauer mit und haelt die App im App-Modus am Leben.
     */
    @Test
    void einKlientDerNichtMehrAnnimmtFliegtRaus() {
        Attrappe eins = dazu("eins");
        Attrappe zwei = dazu("zwei");
        zwei.kaputt = true;
        klienten.sendeAllen(new mtgplayer.protocol.Messages.ErrorMsg("hallo"));
        assertEquals(1, klienten.anzahl(), "der kaputte ist raus");
        assertSame(eins, klienten.steuernder());
        assertFalse(zwei.offen, "und wurde geschlossen");
    }
}
