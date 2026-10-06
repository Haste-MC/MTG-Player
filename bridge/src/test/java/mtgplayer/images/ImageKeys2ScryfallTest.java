package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import forge.item.IPaperCard;
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

    /** Kevins Befund: Spielsteine hatten kein Bild. Token-Keys ("t:script|SET", optional "|nr") landen auf
     *  Scryfalls Token-Set (TokensCode der Edition, Standard "T" + Set) mit der Sammlernummer aus dem
     *  [tokens]-Block der Editionsdatei - wie Forges eigener ImageFetcher. */
    @Test
    void tokenKeyWirdZuTokenSetUndSammlernummer() {
        Optional<String> url = ImageKeys2Scryfall.url("t:g_3_3_beast|C21");
        assertTrue(url.isPresent());
        assertEquals("https://api.scryfall.com/cards/tc21/10/en?format=image&version=normal", url.get());
    }

    @Test
    void tokenKeyMitSammlernummerNutztDiese() {
        assertEquals("https://api.scryfall.com/cards/tc21/10/en?format=image&version=normal",
                ImageKeys2Scryfall.url("t:g_3_3_beast|C21|10").get());
    }

    @Test
    void tokenOhneEditionOderUnbekanntBleibtLeer() {
        assertTrue(ImageKeys2Scryfall.url("t:g_3_3_beast").isEmpty());
        assertTrue(ImageKeys2Scryfall.url("t:gibt_es_nicht|C21").isEmpty());
    }

    /** Der Key, den Forge fuer einen echten Spielstein erzeugt (PaperToken.getImageKey, mit Sammlernummer
     *  und Art-Index), fuehrt zu einer URL. */
    @Test
    void echterPaperTokenKeyFuehrtZuUrl() {
        String key = StaticData.instance().getAllTokens().getToken("g_3_3_beast", "C21").getImageKey(false);
        assertTrue(key.startsWith("t:g_3_3_beast|C21"), key);
        assertTrue(ImageKeys2Scryfall.url(key).orElse("").startsWith("https://api.scryfall.com/cards/tc21/"), key);
    }

    /** Vorschaukarten aus cardsfolder/upcoming haben keinen Druck und damit keine Sammlernummer
     *  ("Avacyn, Angel of Horror|TRK|[N.A.]" in den Deck-Dateien). Scryfall kennt sie trotzdem, nur unter dem
     *  echten Set - also ueber den Namen fragen statt gar nicht. */
    @Test
    void karteOhneSammlernummerFaelltAufDenNamenZurueck() {
        PaperCard avacyn = vorschaukarte("Avacyn, Angel of Horror");
        assertEquals(IPaperCard.NO_COLLECTOR_NUMBER, avacyn.getCollectorNumber());
        Optional<String> url = ImageKeys2Scryfall.url(avacyn.getImageKey(false));
        assertEquals("https://api.scryfall.com/cards/named?exact=Avacyn%2C%20Angel%20of%20Horror&format=image&version=normal", url.orElse("-"));
    }

    @Test
    void namensRueckfallKodiertApostrophUndKommaUndLeerzeichen() {
        PaperCard samut = vorschaukarte("Samut, Hazoret's Champion");
        assertEquals("https://api.scryfall.com/cards/named?exact=Samut%2C%20Hazoret%27s%20Champion&format=image&version=normal",
                ImageKeys2Scryfall.url(samut.getImageKey(false)).orElse("-"));
    }

    @Test
    void namensRueckfallBeiRueckseiteMitFaceBack() {
        // Delver ist ein Transform-Pair; ohne Nummer faellt der Weg auf den Namen, und die Rueckseite
        // behaelt ihr &face=back wie im Weg ueber die Sammlernummer. (Direkt auf nameUrl geprueft: ueber den Key
        // wuerde Forge die echte Delver-Karte mit Nummer aus der Datenbank holen.)
        PaperCard delver = StaticData.instance().getCommonCards().getCard("Delver of Secrets");
        PaperCard ohneNummer = new PaperCard(delver.getRules(), delver.getEdition(), delver.getRarity(), delver.getArtIndex(),
                false, IPaperCard.NO_COLLECTOR_NUMBER, delver.getArtist(), delver.getFunctionalVariant());
        assertEquals("https://api.scryfall.com/cards/named?exact=Delver%20of%20Secrets&format=image&version=normal&face=front",
                ImageKeys2Scryfall.nameUrl(ohneNummer, false));
        assertEquals("https://api.scryfall.com/cards/named?exact=Delver%20of%20Secrets&format=image&version=normal&face=back",
                ImageKeys2Scryfall.nameUrl(ohneNummer, true));
        // Karte ohne Rueckseite: kein &face, auch wenn die Rueckseite verlangt wird (wie Forges Builder)
        PaperCard avacyn = vorschaukarte("Avacyn, Angel of Horror");
        assertEquals("https://api.scryfall.com/cards/named?exact=Avacyn%2C%20Angel%20of%20Horror&format=image&version=normal",
                ImageKeys2Scryfall.nameUrl(avacyn, true));
    }

    /** Jede Absage nennt ihren Grund - sonst ist ein fehlendes Bild von einem nie angefragten nicht zu unterscheiden. */
    @Test
    void jedeAbsageHatEinenGrund() {
        for (String key : new String[] {null, "", "x", "t:goblin_r_1_1", "t:gibt_es_nicht|C21", "c:Gibtsnicht|XXX|1"}) {
            ImageKeys2Scryfall.Result r = ImageKeys2Scryfall.resolve(key);
            assertTrue(r.url().isEmpty(), String.valueOf(key));
            assertTrue(r.reason() != null && !r.reason().isBlank(), "Grund fehlt fuer " + key);
        }
        assertEquals(null, ImageKeys2Scryfall.resolve("t:g_3_3_beast|C21").reason());
    }

    private static PaperCard vorschaukarte(String name) {
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
        assertTrue(pc != null, name + " fehlt in Forges Kartendatenbank");
        return pc;
    }
}
