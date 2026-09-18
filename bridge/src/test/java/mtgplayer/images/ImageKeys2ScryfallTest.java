package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        String key = felothar.getImageKey(false);
        Optional<String> url = ImageKeys2Scryfall.url(key);
        assertTrue(url.isPresent(), key);
        assertEquals("https://api.scryfall.com/cards/tdc/" + felothar.getCollectorNumber() + "?format=image&version=normal", url.get());
    }

    @Test
    void rueckseiteBekommtFaceBack() {
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        String key = felothar.getImageKey(false) + "$alt";
        assertTrue(ImageKeys2Scryfall.url(key).get().endsWith("&face=back"));
    }

    @Test
    void tokensUndUnbekanntesSindLeer() {
        assertTrue(ImageKeys2Scryfall.url("t:goblin_r_1_1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url("c:Gibtsnicht|XXX|1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url(null).isEmpty());
        assertTrue(ImageKeys2Scryfall.url("").isEmpty());
    }
}
