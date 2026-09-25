package mtgplayer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/** Kontrollwechsel weg von der KI ("verschenken"): Forges {@code ControlGainAi} war nur auf das
 *  Wegnehmen fremder Permanents ausgelegt, und {@code PumpAi} konnte einen reinen Traeger-Pump auf
 *  einen Gegner gar nicht anzielen. Ergebnis im Spiel: eine KI mit Steel Golem ("du kannst keine
 *  Kreaturenzauber wirken") sass sechs Zuege mit voller Hand da, obwohl Iroh ihr genau den Ausweg bot. */
class ControlDonationSceneTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Szene A: Iroh + eigenes Sperr-Permanent. Die KI muss den Selbst-Stax verschenken und bekommt
     *  dafuer den Ally-Spielstein mit einer +1/+1-Marke (ein Permanent bei den Gegnern). */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void verschenktDasPermanentDasDieEigenenKreaturenSperrt() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, 5);
        Player a = s.player(0), b = s.player(1);

        s.card("Iroh, Tea Master", a, ZoneType.Battlefield);
        Card golem = s.card("Steel Golem", a, ZoneType.Battlefield);
        s.cards("Forest", 4, a, ZoneType.Battlefield);
        s.cards("Grizzly Bears", 3, a, ZoneType.Hand);
        s.cards("Forest", 10, a, ZoneType.Library);
        s.cards("Island", 10, b, ZoneType.Library);
        s.card("Island", b, ZoneType.Battlefield);

        s.setPhase(PhaseType.MAIN1, a);
        s.loopUntil(PhaseType.MAIN2, a);

        assertSame(b, golem.getController(), "Sperr-Permanent haette verschenkt werden muessen\n" + s.state());
        assertEquals(1, s.count(a, ZoneType.Battlefield, "Ally Token"), s.state());
        Card ally = a.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Ally Token")).findFirst().orElseThrow();
        assertEquals(1, ally.getCounters(CounterEnumType.P1P1), "eine Marke je eigenem Permanent beim Gegner");
        Card bears = a.getCardsIn(ZoneType.Hand).getFirst();
        assertFalse(StaticAbilityCantBeCast.cantBeCastAbility(bears.getFirstSpellAbility(), bears, a),
                "die Sperre auf den eigenen Kreaturenzaubern muss weg sein\n" + s.state());
    }

    /** Szene B: dieselbe Faehigkeit ohne Nachteils-Permanent. Ein funktionierendes Permanent
     *  (Kreatur oder Land) darf die KI nicht fuer einen 1/1 hergeben. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void verschenktKeinFunktionierendesPermanent() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, 5);
        Player a = s.player(0), b = s.player(1);

        s.card("Iroh, Tea Master", a, ZoneType.Battlefield);
        s.card("Grizzly Bears", a, ZoneType.Battlefield);
        s.cards("Forest", 4, a, ZoneType.Battlefield);
        s.cards("Forest", 10, a, ZoneType.Library);
        s.cards("Island", 10, b, ZoneType.Library);
        s.card("Island", b, ZoneType.Battlefield);

        s.setPhase(PhaseType.MAIN1, a);
        s.loopUntil(PhaseType.MAIN2, a);

        assertEquals(1, b.getCardsIn(ZoneType.Battlefield).size(), "nichts verschenkt\n" + s.state());
        assertEquals(0, s.count(a, ZoneType.Battlefield, "Ally Token"), s.state());
        assertFalse(s.log(1000).contains("failed to target"),
                "ohne verschenkbares Permanent darf der Traeger gar nicht erst einen Gegner anvisieren,\n"
                        + "sonst verwirft MagicStack.add die Faehigkeit wieder - dieselbe Endlosmeldung wie im echten Log\n"
                        + s.log(1000));
    }

    /** Szene C: drei Spieler. Ein Permanent, das den eigenen Controller sperrt, ist eine Waffe -
     *  es gehoert an den staerksten Gegner (B mit Laendern und Kreaturen), nicht an den schwaechsten. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void sperrPermanentGehtAnDenStaerkstenGegner() {
        Scene s = Scene.threePlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1), c = s.player(2);

        s.card("Iroh, Tea Master", a, ZoneType.Battlefield);
        Card golem = s.card("Steel Golem", a, ZoneType.Battlefield);
        s.cards("Forest", 4, a, ZoneType.Battlefield);
        staerkerAls(s, b, c);

        s.setPhase(PhaseType.MAIN1, a);
        s.loopUntil(PhaseType.MAIN2, a);

        assertSame(b, golem.getController(), "Sperre gehoert an den staerksten Gegner\n" + s.state());
        assertEquals(1, s.count(a, ZoneType.Battlefield, "Ally Token"), s.state());
    }

    /** Szene D: dieselben drei Spieler, aber das verschenkte Permanent ist blosse Donate-Ware ohne
     *  Sperre - ein Geschenk gehoert an den schwaechsten Gegner, der am wenigsten damit anfangen kann. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void geschenkGehtAnDenSchwaechstenGegner() {
        Scene s = Scene.threePlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1), c = s.player(2);

        s.card("Iroh, Tea Master", a, ZoneType.Battlefield);
        Card gift = s.card("Master of the Feast", a, ZoneType.Battlefield);
        s.cards("Forest", 4, a, ZoneType.Battlefield);
        staerkerAls(s, b, c);

        s.setPhase(PhaseType.MAIN1, a);
        s.loopUntil(PhaseType.MAIN2, a);

        assertSame(c, gift.getController(), "Geschenk gehoert an den schwaechsten Gegner\n" + s.state());
        assertEquals(1, s.count(a, ZoneType.Battlefield, "Ally Token"), s.state());
    }

    /** Gibt {@code stark} ein deutlich besseres Brett als {@code schwach} (Bewertung ueber
     *  {@code ComputerUtil.evaluateBoardPosition}: Laender, Kreaturen, Bibliotheksgroesse). */
    private static void staerkerAls(Scene s, Player stark, Player schwach) {
        s.cards("Island", 5, stark, ZoneType.Battlefield);
        s.cards("Grizzly Bears", 2, stark, ZoneType.Battlefield);
        s.card("Island", schwach, ZoneType.Battlefield);
        s.cards("Forest", 10, s.player(0), ZoneType.Library);
        s.cards("Island", 10, stark, ZoneType.Library);
        s.cards("Island", 10, schwach, ZoneType.Library);
    }
}
