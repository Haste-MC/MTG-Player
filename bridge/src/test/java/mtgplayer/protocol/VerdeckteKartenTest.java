package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * Was unter einer verdeckten Karte liegt, und wer es sehen darf.
 *
 * <p>Kevins Befund vom 2026-10-08: unter einer verdeckten Karte war nichts zu erkennen, auch bei
 * den eigenen. Die Bridge hat sie nie verborgen - sie hat den falschen Zustand uebertragen. Bei
 * einer verdeckten Karte IST {@code getCurrentState()} die Rueckseite: Name leer, Bildschluessel
 * {@code t:hidden}. Die echte Karte liegt in {@code getAlternateState()}.</p>
 *
 * <p>Geprueft wird deshalb am Schnappschuss, nicht an Forges Interna: genau das bekommt der
 * Browser zu sehen.</p>
 */
class VerdeckteKartenTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static Snapshot.CardSnap sicht(Card c, Player betrachter) {
        return StateSerializer.cardSnap(c.getView(), ViewContext.plain(betrachter.getView()));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void eigeneVerdeckteKreaturZeigtMirIhreIdentitaetUndDemGegnerNicht() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player ich = s.player(0), gegner = s.player(1);
        Card morph = s.card("Hill Giant", ich, ZoneType.Battlefield);
        morph.turnFaceDown(true);

        Snapshot.CardSnap meine = sicht(morph, ich);
        assertTrue(meine.faceDown(), "sie liegt verdeckt und soll als Rueckseite erscheinen");
        assertNotNull(meine.verdeckt(), "ich weiss, was meine eigene verdeckte Kreatur ist");
        assertEquals("Hill Giant", meine.verdeckt().name());
        assertNotNull(meine.verdeckt().imageKey(), "mit Bild, sonst nuetzt die Vorschau nichts");

        Snapshot.CardSnap fremde = sicht(morph, gegner);
        assertTrue(fremde.faceDown());
        assertNull(fremde.verdeckt(), "der Gegner erfaehrt nicht, was darunter liegt");
    }

    /**
     * Der Fall aus Kevins Partie: Spinerock Knoll (Hideaway) verbannt verdeckt, und wer sie verbannt
     * hat, darf hinsehen. Die Erlaubnis selbst setzt Forge ueber eine statische Faehigkeit; hier
     * wird sie direkt gesetzt, damit der Test die SERIALISIERUNG prueft und nicht Forges Hideaway.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void verdecktImExilZeigtSichNurMitErlaubnis() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player ich = s.player(0), gegner = s.player(1);
        Card versteckt = s.card("Grizzly Bears", ich, ZoneType.Exile);
        versteckt.turnFaceDown(true);

        Snapshot.CardSnap ohne = sicht(versteckt, ich);
        assertTrue(ohne.faceDown());
        assertNull(ohne.verdeckt(), "ohne Erlaubnis sehe auch ich nichts");
        assertNull(ohne.name(), "und die Karte selbst bleibt ganz verborgen");

        versteckt.addMayLookTemp(ich);
        Snapshot.CardSnap mit = sicht(versteckt, ich);
        assertTrue(mit.faceDown(), "sie bleibt verdeckt - der Tisch soll nicht luegen");
        assertNotNull(mit.verdeckt(), "mit Erlaubnis sehe ich, was darunter liegt");
        assertEquals("Grizzly Bears", mit.verdeckt().name());

        assertNull(sicht(versteckt, gegner).verdeckt(), "der Gegner weiterhin nicht");
    }

    /** Eine offene Karte aendert sich durch all das nicht. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void offeneKartenBleibenWieSieWaren() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player ich = s.player(0);
        Card offen = s.card("Grizzly Bears", ich, ZoneType.Battlefield);

        Snapshot.CardSnap snap = sicht(offen, ich);
        assertEquals(false, snap.faceDown());
        assertNull(snap.verdeckt(), "kein Zusatz, wo nichts verdeckt ist");
        assertEquals("Grizzly Bears", snap.name());
    }
}
