package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import forge.item.PaperCard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class ImageKeys2ScryfallTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void kartenKeyWirdZuSetUndSammlernummer() {
        // Felothar hat keine Rückseite – Forges Builder haengt dann kein &face an, dafuer aber
        // (bei leerem langCode) einen abschliessenden "/" vor dem "?".
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        String key = felothar.getImageKey(false);
        Optional<String> url = ImageKeys2Scryfall.url(key);
        assertTrue(url.isPresent(), key);
        assertEquals("https://api.scryfall.com/cards/tdc/" + felothar.getCollectorNumber() + "/?format=image&version=normal", url.get());
    }

    @Test
    void rueckseiteBekommtFaceBack() {
        // Fuer ein tatsaechliches Transform-Pair (Delver of Secrets // Insectile Aberration)
        // haengt Forges Builder &face=back/front an, bei Felothar (keine Rueckseite) nicht.
        PaperCard delver = StaticData.instance().getCommonCards().getCard("Delver of Secrets");
        String frontKey = delver.getImageKey(false);
        assertTrue(ImageKeys2Scryfall.url(frontKey).get().endsWith("&face=front"));
        assertTrue(ImageKeys2Scryfall.url(frontKey + "$alt").get().endsWith("&face=back"));
    }

    @Test
    void tokensUndUnbekanntesSindLeer() {
        assertTrue(ImageKeys2Scryfall.url("t:goblin_r_1_1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url("c:Gibtsnicht|XXX|1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url(null).isEmpty());
        assertTrue(ImageKeys2Scryfall.url("").isEmpty());
    }
}
