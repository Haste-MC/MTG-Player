package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import forge.card.CardSplitType;
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

    /** Befund 3: echte Vorschaukarten-Namen mit Akzenten. Forges Key enthaelt den Namen ohne Akzente
     *  ({@code StringUtils.stripAccents}), die Adresse aber den echten Namen, UTF-8-kodiert - Scryfalls
     *  {@code exact} vergleicht mit dem echten Namen. */
    @Test
    void namensRueckfallKodiertAkzenteAlsUtf8() {
        String basis = "https://api.scryfall.com/cards/named?exact=";
        String ende = "&format=image&version=normal";
        String[][] faelle = {
                {"Fíli the Pathfinder", "F%C3%ADli%20the%20Pathfinder", "c:Fili the Pathfinder|"},
                {"Kíli the Resourceful", "K%C3%ADli%20the%20Resourceful", "c:Kili the Resourceful|"},
                {"Glóin the Mighty", "Gl%C3%B3in%20the%20Mighty", "c:Gloin the Mighty|"},
                {"Fíli and Kíli, Joyous", "F%C3%ADli%20and%20K%C3%ADli%2C%20Joyous", "c:Fili and Kili, Joyous|"},
                {"Dáin's Company", "D%C3%A1in%27s%20Company", "c:Dain's Company|"},
                {"Thrór's Map", "Thr%C3%B3r%27s%20Map", "c:Thror's Map|"},
        };
        for (String[] f : faelle) {
            PaperCard pc = vorschaukarte(f[0]);
            // Forges Key traegt den Namen ohne Akzente ...
            String key = pc.getImageKey(false);
            assertTrue(key.startsWith(f[2]), key);
            // ... die Adresse den echten (Forges Datenbank fuehrt diese Karten teils schon mit Nummer, hier ohne)
            PaperCard ohne = ohneNummer(pc, pc.getRules().getSplitType());
            assertEquals(f[0], ohne.getName());
            String url = ImageKeys2Scryfall.nameUrl(ohne, false);
            assertTrue(url.startsWith(basis + f[1] + ende), url);
        }
    }

    /** Befund 3: Namen mit Satzzeichen aus Forges Vorschaukarten ({@code !}, {@code -}, {@code .}) und echten
     *  Karten ({@code :}, {@code /}). Nur Buchstaben, Ziffern sowie {@code - . _ *} lassen {@code URLEncoder}
     *  stehen; alles andere wird %XX, Leerzeichen %20 (nicht '+'). */
    @Test
    void namensRueckfallKodiertSatzzeichenImNamen() {
        String basis = "https://api.scryfall.com/cards/named?exact=";
        String ende = "&format=image&version=normal";
        assertEquals(basis + "Khaaaaaaaaaaaannn%21" + ende,
                ImageKeys2Scryfall.nameUrl(ohneNummer(vorschaukarte("Khaaaaaaaaaaaannn!"), CardSplitType.None), false));
        assertEquals(basis + "Shields%20Up%21" + ende,
                ImageKeys2Scryfall.nameUrl(ohneNummer(vorschaukarte("Shields Up!"), CardSplitType.None), false));
        assertEquals(basis + "Celebrate%20the%20Mountain-king" + ende,
                ImageKeys2Scryfall.nameUrl(ohneNummer(vorschaukarte("Celebrate the Mountain-king"), CardSplitType.None), false));
        assertEquals(basis + "I%27m%20a%20Doctor%2C%20Not%20a%20.%20.%20." + ende,
                ImageKeys2Scryfall.nameUrl(ohneNummer(vorschaukarte("I'm a Doctor, Not a . . ."), CardSplitType.None), false));
        assertEquals(basis + "Circle%20of%20Protection%3A%20Red" + ende,
                ImageKeys2Scryfall.nameUrl(ohneNummer(vorschaukarte("Circle of Protection: Red"), CardSplitType.None), false));
    }

    /** Die Kodierung selbst, ohne Karte: auch Zeichen, die in keiner heutigen Vorschaukarte vorkommen. */
    @Test
    void encodeKodiertJedesNichtSicherZeichen() {
        assertEquals("a%20b", ImageKeys2Scryfall.encode("a b"));
        assertEquals("a%2Bb", ImageKeys2Scryfall.encode("a+b"));      // ein echtes '+' bleibt %2B, wird nicht zu %20
        assertEquals("a%2Ab", ImageKeys2Scryfall.encode("a*b"));      // URLEncoder liess '*' stehen
        assertEquals("%2A", ImageKeys2Scryfall.encode("*"));
        assertEquals("a-b.c_d", ImageKeys2Scryfall.encode("a-b.c_d")); // sicher, bleiben stehen
        assertEquals("%21%3A%2F%2F%26%23%3F", ImageKeys2Scryfall.encode("!://&#?"));
        assertEquals("F%C3%ADli", ImageKeys2Scryfall.encode("Fíli"));
        assertEquals("Fire%20%2F%2F%20Ice", ImageKeys2Scryfall.encode("Fire // Ice"));
    }

    /** Befund 1: {@code face=back} gibt es nur bei Karten mit zwei echten Seiten (Transform, Modal). Bei Adventure,
     *  Omen, Prepare, Split und Flip ist es bei Scryfall ein einziges Bild; {@code face=back} wuerde mit 422
     *  beantwortet. Die Bedingung haengt an der Kartenart, nicht nur am Rueckseiten-Suffix des Keys. */
    @Test
    void namensRueckfallHaengtFaceBackNurAnKartenMitEchterRueckseite() {
        String basis = "https://api.scryfall.com/cards/named?exact=";
        String ende = "&format=image&version=normal";
        // Karten ohne echte Rueckseite: auch bei verlangter Rueckseite nur face=front
        for (Object[] f : new Object[][] {
                {"Bonecrusher Giant", CardSplitType.Adventure},
                {"Cunning Azurescale", CardSplitType.Omen},
                {"Hallway Heckler", CardSplitType.Prepare},
                {"Consign // Oblivion", CardSplitType.Split},
                {"Akki Lavarunner", CardSplitType.Flip},
        }) {
            PaperCard pc = ohneNummer(vorschaukarte((String) f[0]), (CardSplitType) f[1]);
            assertEquals(basis + enc(pc.getName()) + ende + "&face=front", ImageKeys2Scryfall.nameUrl(pc, true), f[0] + " Rueckseite");
            assertEquals(basis + enc(pc.getName()) + ende + "&face=front", ImageKeys2Scryfall.nameUrl(pc, false), f[0] + " Vorderseite");
        }
        // Karten mit zwei echten Seiten: Rueckseite bekommt face=back
        for (Object[] f : new Object[][] {
                {"Delver of Secrets", CardSplitType.Transform},
                {"Clearwater Pathway", CardSplitType.Modal},
        }) {
            PaperCard pc = ohneNummer(vorschaukarte((String) f[0]), (CardSplitType) f[1]);
            assertEquals(basis + enc(pc.getName()) + ende + "&face=back", ImageKeys2Scryfall.nameUrl(pc, true), f[0] + " Rueckseite");
            assertEquals(basis + enc(pc.getName()) + ende + "&face=front", ImageKeys2Scryfall.nameUrl(pc, false), f[0] + " Vorderseite");
        }
    }

    /** Befund 2: Forge setzt bei Meld in beiden Faellen {@code face=front} (ImageUtil.getScryfallDownloadUrl) und
     *  erreicht die Rueckseite ueber eine andere Sammlernummer. Ueber den Namen heisst das: die Rueckseite ist die
     *  Karte des Meld-Ergebnisses (Forges {@code getNameToUse}), nicht noch einmal die Vorderseite. */
    @Test
    void namensRueckfallBeiMeldImmerFaceFrontUndRueckseiteIstDasErgebnis() {
        String basis = "https://api.scryfall.com/cards/named?exact=";
        String ende = "&format=image&version=normal&face=front";
        String ergebnis = "Hanweir%2C%20the%20Writhing%20Township";
        PaperCard battlements = ohneNummer(vorschaukarte("Hanweir Battlements"), CardSplitType.Meld);
        PaperCard garrison = ohneNummer(vorschaukarte("Hanweir Garrison"), CardSplitType.Meld);
        assertEquals(basis + "Hanweir%20Battlements" + ende, ImageKeys2Scryfall.nameUrl(battlements, false));
        assertEquals(basis + ergebnis + ende, ImageKeys2Scryfall.nameUrl(battlements, true));
        assertEquals(basis + "Hanweir%20Garrison" + ende, ImageKeys2Scryfall.nameUrl(garrison, false));
        assertEquals(basis + ergebnis + ende, ImageKeys2Scryfall.nameUrl(garrison, true));
    }

    /** Wie {@code encode}, aber unabhaengig davon ausgeschrieben, damit der Test die Kodierung nicht von sich selbst erbt. */
    private static String enc(String s) {
        return s.replace(",", "%2C").replace(" ", "%20").replace("/", "%2F");
    }

    private static PaperCard ohneNummer(PaperCard pc, CardSplitType erwartet) {
        assertEquals(erwartet, pc.getRules().getSplitType(), pc.getName());
        return new PaperCard(pc.getRules(), pc.getEdition(), pc.getRarity(), pc.getArtIndex(),
                false, IPaperCard.NO_COLLECTOR_NUMBER, pc.getArtist(), pc.getFunctionalVariant());
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
