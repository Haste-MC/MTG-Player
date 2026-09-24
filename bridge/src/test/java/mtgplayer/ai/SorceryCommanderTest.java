package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.model.FModel;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.CommanderRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ein Zauberspruch als Commander (Legendary Sorcery): landet er im Friedhof, fragt
 * {@code GameAction#stateBasedAction_Commander} den Besitzer per {@code confirmAction(...)} mit
 * {@code params == null}, und zwar über die erste Spruchfähigkeit der Karte. Bei einem
 * Discover-Zauber ist das {@code forge.ai.ability.DiscoverAi} – das griff ungeprüft auf
 * {@code params} zu und riss den Spielthread mit einer NPE ab (Fork-Fix, siehe
 * {@code docs/forge-fork.md}). Die KI muss die Frage beantworten können.
 */
class SorceryCommanderTest {

    /** Upstream-Zauber mit Discover als erster (und einziger) Spruchfähigkeit. */
    private static final String DISCOVER_SORCERY = "Hit the Mother Lode";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void discoverZauberAlsCommanderKehrtInDieKommandozone() {
        assertTrue(CommanderRules.create().hasAppliedVariant(GameType.Commander));
        Deck a = Precons.load("Abzan Armor [TDC] [2025]");
        Deck b = Precons.load("Adaptive Enchantment [C18] [2018]");
        RegisteredPlayer ra = RegisteredPlayer.forCommander(a);
        ra.setPlayer(new LobbyPlayerAi("KI", null));
        RegisteredPlayer rb = RegisteredPlayer.forCommander(b);
        rb.setPlayer(new LobbyPlayerAi("Gegner", null));
        Game game = new Match(CommanderRules.create(), List.of(ra, rb), "t").createGame();
        Player ki = game.getPlayers().get(0);

        // Den Zauber als Commander der KI einsetzen (wie Player#initVariantsZones) und aus der Hand
        // in den Friedhof schicken - so liegt er dort wie nach dem Verrechnen des Spruchs.
        IPaperCard paper = FModel.getMagicDb().getCommonCards().getCard(DISCOVER_SORCERY);
        Card cmd = Card.fromPaperCard(paper, ki);
        ki.initCommanderColor(cmd);
        cmd.setCollectible(true);
        ki.addCommander(cmd);
        ki.getZone(ZoneType.Hand).add(cmd);
        cmd.setZone(ki.getZone(ZoneType.Hand));

        game.getAction().moveToGraveyard(cmd, null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);

        assertEquals(1, anzahl(ki, ZoneType.Command, DISCOVER_SORCERY), "Commander in der Kommandozone");
        assertEquals(0, anzahl(ki, ZoneType.Graveyard, DISCOVER_SORCERY), "Commander nicht mehr im Friedhof");
    }

    private static int anzahl(Player p, ZoneType zone, String name) {
        return (int) p.getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).count();
    }
}
