package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.CrashLog;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Wer haengt am Tisch, und wer steuert ihn.
 *
 * <p>Alle verbundenen Browser sehen denselben Zustand, genau EINER darf klicken: der zuerst
 * verbundene. Wer spaeter dazukommt, sieht zu - das ist der Fall, um den es geht (eine Sitzung
 * spielt, man setzt sich daneben). Geht der Steuernde, rueckt der am laengsten wartende Zuschauer
 * nach; ein Zuschauer kann die Steuerung ausdruecklich holen ({@code takeControl}), denn wer zuerst
 * verbunden hat, ist nicht zwingend der, der spielen will.</p>
 *
 * <p>Bewusst NICHT "der Neueste steuert": bis zum 2026-10-08 warf eine neue Verbindung die alte
 * raus, und weil der Browser nach einer Sekunde wiederkommt, warfen sich zwei Tabs endlos
 * gegenseitig raus.</p>
 *
 * <p>Diese Buchhaltung liegt hier und nicht im {@code WsServer}, weil es seit dem Rueckfallweg
 * ueber den HTTP-Port zwei Arten von Klienten gibt - mit zwei Listen gaebe es zwei Wahrheiten
 * darueber, wer steuert.</p>
 */
public final class Klienten {

    /** In Eintreffens-Reihenfolge; der erste steuert. */
    private final List<Klient> liste = new CopyOnWriteArrayList<>();
    private final Consumer<JsonNode> inbound;
    private final Runnable onLetzterWeg;

    /**
     * @param inbound      bekommt, was der STEUERNDE schickt
     * @param onLetzterWeg laeuft, wenn der letzte Klient weg ist (im App-Modus: Fenster zu, siehe
     *                     {@code IdleExit})
     */
    public Klienten(Consumer<JsonNode> inbound, Runnable onLetzterWeg) {
        this.inbound = inbound;
        this.onLetzterWeg = onLetzterWeg;
    }

    public void dazu(Klient k) {
        liste.add(k);                            // hinten dran: der erste bleibt der Steuernde
        rollenMelden();
    }

    public void weg(Klient k) {
        if (!liste.remove(k)) {
            return;                              // kannten wir nicht (doppelte Meldung)
        }
        if (liste.isEmpty()) {
            try {
                onLetzterWeg.run();
            } catch (RuntimeException e) {
                CrashLog.note("klienten", "onLetzterWeg fehlgeschlagen: " + e, e);
            }
            return;
        }
        rollenMelden();                          // ging der Steuernde, rueckt der naechste nach
    }

    /** An alle; ein abgerissener Klient darf die uebrigen nicht um ihre Nachricht bringen. */
    public void sendeAllen(Object message) {
        String json = Json.toJson(message);
        for (Klient k : liste) {
            sendeAn(k, json);
        }
    }

    /**
     * Eine Nachricht eines Klienten. {@code takeControl} darf jeder - sonst kaeme man je nach
     * Reihenfolge des Verbindens nie an die Steuerung. Alles andere nur vom Steuernden; die
     * Oberflaeche sperrt es schon, aber darauf verlassen kann sich der Server nicht.
     */
    public void empfange(Klient k, JsonNode msg) {
        if ("takeControl".equals(msg.path("type").asText())) {
            uebernehmen(k);
            return;
        }
        if (k != steuernder()) {
            return;
        }
        inbound.accept(msg);
    }

    /** Der Steuernde, oder {@code null} wenn niemand verbunden ist. */
    public Klient steuernder() {
        return liste.isEmpty() ? null : liste.get(0);
    }

    public int anzahl() {
        return liste.size();
    }

    private void uebernehmen(Klient k) {
        if (k == steuernder() || !liste.contains(k)) {
            return;
        }
        liste.remove(k);
        liste.add(0, k);
        rollenMelden();
    }

    /** Jedem seine Rolle; der Steuernde erfaehrt dabei, wie viele zusehen. */
    private void rollenMelden() {
        Klient chef = steuernder();
        int zuschauer = Math.max(0, liste.size() - 1);
        for (Klient k : liste) {
            sendeAn(k, Json.toJson(new Messages.Role(k == chef, zuschauer)));
        }
    }

    /**
     * Schicken und, wenn es scheitert, den Klienten vergessen. Beim Ereignisstrom ist ein
     * fehlgeschlagener Schreibvorgang die EINZIGE Art, einen stillschweigend abgerissenen Browser
     * zu bemerken - anders als beim Socket gibt es dort kein onClose.
     */
    private void sendeAn(Klient k, String json) {
        if (!k.offen()) {
            weg(k);
            return;
        }
        try {
            k.sende(json);
        } catch (Exception e) {
            CrashLog.note("klienten", "Senden fehlgeschlagen, Klient faellt raus: " + e, e);
            k.schliesse();
            weg(k);
        }
    }
}
