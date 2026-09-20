package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.ai.ComputerUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Mulligan-Heuristik (Spec §4, Fork-Property {@code MULLIGAN_CHECK_COLORS}): {@code ComputerUtil.scoreHand}
 *  prueft Farben und Kurve der Starthand, {@code PlayerControllerAi.tuckCardsViaMulligan} legt beim
 *  London-Mulligan zuerst weg, was die Hand nicht spielen kann. Profil {@code Legacy} (Kopie von Default mit
 *  {@code MULLIGAN_CHECK_COLORS=false}) muss das alte Verhalten zeigen - es ist der B-Sitz im Bench.
 *
 *  <p>Die Szene braucht eine Bibliothek: {@code scoreHand} schaut auf das Land-Verhaeltnis des Decks
 *  (leere Bibliothek = "kein Land-Deck", jede Hand wird behalten). 30 Laender auf 90 Karten sind ein
 *  normales Verhaeltnis (kein "heavy spell deck", kein "heavy land deck"). */
class MulliganTest {
    private static final AiConfig NEW = AiConfig.DEFAULT;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static AiConfig legacy() {
        return AiConfig.parse("std:Legacy");
    }

    private static Scene scene(AiConfig a) {
        Scene s = Scene.twoPlayers(a, AiConfig.DEFAULT);
        Player p = s.player(0);
        s.cards("Forest", 30, p, ZoneType.Library);
        s.cards("Grizzly Bears", 60, p, ZoneType.Library);
        return s;
    }

    private static void hand(Scene s, String... names) {
        for (String n : names) {
            s.card(n, s.player(0), ZoneType.Hand);
        }
    }

    private static String names(CardCollectionView cards) {
        return cards.stream().map(Card::getName).collect(Collectors.joining(", "));
    }

    private static CardCollectionView tuck(Scene s, int n) {
        Player a = s.player(0);
        return a.getController().tuckCardsViaMulligan(new CardCollection(a.getCardsIn(ZoneType.Hand)), n);
    }

    /** 5 Laender (R, C, B, B, B) und zwei blaue Zauber: kein Zauber ist bezahlbar - Mulligan. Die alte
     *  Heuristik zaehlte nur "CMC &lt;= Landzahl" und behielt die Hand. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void farbloseHandWirdGemulligant() {
        Scene s = scene(NEW);
        hand(s, "Mountain", "Wastes", "Swamp", "Swamp", "Swamp", "Counterspell", "Divination");
        assertTrue(ComputerUtil.wantMulligan(s.player(0), 0), "Hand ohne bezahlbaren Zauber muss weg");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void legacyBehaeltDieFarbloseHand() {
        Scene s = scene(legacy());
        hand(s, "Mountain", "Wastes", "Swamp", "Swamp", "Swamp", "Counterspell", "Divination");
        assertFalse(ComputerUtil.wantMulligan(s.player(0), 0), "Legacy-Profil = altes Verhalten (behalten)");
    }

    /** 3 Laender (G, G, W) und Zauber fuer G, GW, 2G: alles bezahlbar, Kurve da - behalten. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void passendeHandWirdBehalten() {
        Scene s = scene(NEW);
        hand(s, "Forest", "Forest", "Plains", "Llanowar Elves", "Qasali Pridemage", "Civic Wayfinder", "Colossal Dreadmaw");
        assertFalse(ComputerUtil.wantMulligan(s.player(0), 0), "Hand mit Kurve und Farben muss bleiben");
    }

    /** Vier Laender, aber nur ein Sechser als Zauber: keine Kurve bis CMC 3 - Mulligan (die alte Heuristik
     *  behielt jede Hand mit 2-5 Laendern). */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void handOhneFrueheZauberWirdGemulligant() {
        Scene s = scene(NEW);
        hand(s, "Forest", "Forest", "Forest", "Plains", "Colossal Dreadmaw", "Colossal Dreadmaw", "Colossal Dreadmaw");
        assertTrue(ComputerUtil.wantMulligan(s.player(0), 0), "Hand ohne Zauber bis CMC 3 muss weg");
    }

    /** London-Bottoming: der Zauber in der unspielbaren Farbe geht vor dem teuersten Zauber unten drunter. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void bottomingLegtUnspielbareFarbeZuerstWeg() {
        Scene s = scene(NEW);
        hand(s, "Forest", "Forest", "Plains", "Llanowar Elves", "Qasali Pridemage", "Colossal Dreadmaw", "Counterspell");
        CardCollectionView one = tuck(s, 1);
        assertEquals("Counterspell", names(one));
        CardCollectionView two = tuck(s, 2);
        assertEquals(2, two.size(), names(two));
        assertTrue(two.stream().anyMatch(c -> c.getName().equals("Counterspell")), names(two));
        assertTrue(two.stream().anyMatch(c -> c.getName().equals("Colossal Dreadmaw")), names(two));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void legacyBottomingLegtDenTeuerstenZauberWeg() {
        Scene s = scene(legacy());
        hand(s, "Forest", "Forest", "Plains", "Llanowar Elves", "Qasali Pridemage", "Colossal Dreadmaw", "Counterspell");
        assertEquals("Colossal Dreadmaw", names(tuck(s, 1)));
    }

    /** Ueberzaehlige Laender (ueber finalHandSize/2 + 1) gehen vor dem teuersten bezahlbaren Zauber unten drunter:
     *  7 Karten, 1 weg -> 6 bleiben, 4 Laender erlaubt, das fuenfte Land geht. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void bottomingLegtUeberzaehligeLaenderVorBezahlbarenZaubernWeg() {
        Scene s = scene(NEW);
        hand(s, "Forest", "Forest", "Forest", "Forest", "Forest", "Llanowar Elves", "Civic Wayfinder");
        assertEquals("Forest", names(tuck(s, 1)));
    }

    /** Commander-Farbidentitaet als Filter: Laender, die keine Identitaetsfarbe liefern, zaehlen nicht fuer
     *  die Farbabdeckung. Ohne Commander deckt die Insel die blauen Zauber und die Hand bleibt; mit einem
     *  gruenen Commander liefern Inseln und Ebene nichts Brauchbares, die gruenen Zauber sind unbezahlbar. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void commanderIdentitaetFiltertLaender() {
        Scene without = scene(NEW);
        hand(without, "Island", "Island", "Plains", "Counterspell", "Divination", "Llanowar Elves", "Grizzly Bears");
        assertFalse(ComputerUtil.wantMulligan(without.player(0), 0), "ohne Commander: blaue Zauber bezahlbar");

        Scene with = scene(NEW);
        Player a = with.player(0);
        a.addCommander(with.card("Grizzly Bears", a, ZoneType.Command));
        hand(with, "Island", "Island", "Plains", "Counterspell", "Divination", "Llanowar Elves", "Grizzly Bears");
        assertTrue(ComputerUtil.wantMulligan(a, 0), "gruener Commander: keine Identitaetsfarbe auf den Laendern");
    }

    /** Beliebige Farbe zaehlt fuer alle Farben. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void beliebigeFarbeDecktAlles() {
        Scene s = scene(NEW);
        hand(s, "Mana Confluence", "Wastes", "Wastes", "Counterspell", "Divination", "Llanowar Elves", "Colossal Dreadmaw");
        assertFalse(ComputerUtil.wantMulligan(s.player(0), 0), "Any-Color-Land deckt UU");
    }

    @Test
    void legacyProfilIstWaehlbar() {
        assertTrue(AiConfig.profiles().containsAll(List.of("Default", "Legacy")), AiConfig.profiles().toString());
    }
}
