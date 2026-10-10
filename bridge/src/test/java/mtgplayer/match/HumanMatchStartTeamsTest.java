package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.player.Player;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.protocol.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Eigene Klasse, damit {@code ForgeBoot} die drei schnellen Faelle in {@link HumanMatchTeamsTest}
 * nicht ausbremst.
 *
 * <p>Warum dieser Test existiert: {@code HumanMatchTeamsTest} ruft {@code applyTeams} direkt auf und
 * bleibt gruen, auch wenn der Aufruf in {@code start}/{@code startSpectator} geloescht wird - die
 * Teams waeren dann still tot. Hier kommen die Nummern an der Engine an einer wirklich ueber
 * {@code HumanMatch} gestarteten Partie an (Zuschauer-Partie, damit keine GUI-Interaktion noetig ist;
 * Aufbau wie in {@code SpectatorEndTest}).
 */
class HumanMatchStartTeamsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void einGestartetesSpielTraegtDieTeamsInSitzreihenfolge() throws Exception {
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"));
        List<Object> gesendet = Collections.synchronizedList(new ArrayList<>());
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(() -> gui);
        HumanMatch match = new HumanMatch();
        try {
            match.startSpectator(decks, List.of("KI 1", "KI 2"), Collections.nCopies(2, AiConfig.DEFAULT),
                    List.of(1, 2), 3, gui, null, java.util.Set.of(), null);
            Game game = warteAufSpiel(match);

            // getRegisteredPlayers(): alle Sitze in Sitzreihenfolge, auch ausgeschiedene
            List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
            assertEquals(2, sitze.size(), "zwei Sitze");
            assertEquals(1, sitze.get(0).getTeam(), "Sitz 0 traegt Team 1 - sonst ging der applyTeams-Aufruf in startSpectator verloren");
            assertEquals(2, sitze.get(1).getTeam(), "Sitz 1 traegt Team 2 - sonst ging der applyTeams-Aufruf in startSpectator verloren");

            // Dieselbe Partie, zweite Stelle: das Team muss auch im Snapshot ankommen, den der Browser
            // bekommt - sonst ging gui.setTeams(...) in startSpectator verloren.
            gesendet.clear();
            gui.pushState();
            // Kopie unter dem Monitor: die Denk-Anzeige der laufenden Partie schreibt nebenher in die Liste,
            // und synchronizedList sperrt das Iterieren nicht (ConcurrentModificationException).
            List<Object> kopie;
            synchronized (gesendet) {
                kopie = new ArrayList<>(gesendet);
            }
            Snapshot snap = kopie.stream().filter(Snapshot.class::isInstance).map(Snapshot.class::cast)
                    .findFirst().orElseGet(() -> fail("pushState hat keinen Snapshot gesendet"));
            assertEquals(2, snap.players().size(), "zwei Sitze im Snapshot");
            assertEquals(1, snap.players().get(0).team(), "Sitz 0 zeigt Team 1 im Snapshot");
            assertEquals(2, snap.players().get(1).team(), "Sitz 1 zeigt Team 2 im Snapshot");
        } finally {
            match.end();
            ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(null);
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void vierSitzeMitZweiTeamsSitzenAbwechselndUndDerSnapshotZeigtDieselbenTeams() throws Exception {
        // Lobby-Reihenfolge 1,1,2,2 - gespielt wird 1,2,1,2. Die Teamliste fuer die Snapshots (gui.setTeams)
        // muss mit den Sitzen wandern, sonst zeigt der Browser dem falschen Sitz das falsche Team.
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"),
                Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"));
        List<Object> gesendet = Collections.synchronizedList(new ArrayList<>());
        WebGuiGame gui = new WebGuiGame(gesendet::add);
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(() -> gui);
        HumanMatch match = new HumanMatch();
        try {
            match.startSpectator(decks, List.of("A", "B", "C", "D"), Collections.nCopies(4, AiConfig.DEFAULT),
                    List.of(1, 1, 2, 2), 3, gui, null, java.util.Set.of(), null);
            Game game = warteAufSpiel(match);

            List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
            assertEquals(List.of("A", "C", "B", "D"), sitze.stream().map(Player::getName).toList(),
                    "abwechselnd: Sitz 0 bleibt vorn, dann das andere Team");
            assertEquals(List.of(1, 2, 1, 2), sitze.stream().map(Player::getTeam).toList(),
                    "die Teams wandern mit den Sitzen");

            gesendet.clear();
            gui.pushState();
            List<Object> kopie;
            synchronized (gesendet) {
                kopie = new ArrayList<>(gesendet);
            }
            Snapshot snap = kopie.stream().filter(Snapshot.class::isInstance).map(Snapshot.class::cast)
                    .findFirst().orElseGet(() -> fail("pushState hat keinen Snapshot gesendet"));
            assertEquals(List.of("A", "C", "B", "D"), snap.players().stream().map(p -> p.name()).toList());
            assertEquals(List.of(1, 2, 1, 2), snap.players().stream().map(p -> p.team()).toList(),
                    "der Snapshot zeigt jedem Sitz sein eigenes Team");
        } finally {
            match.end();
            ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(null);
        }
    }

    private static Game warteAufSpiel(HumanMatch match) throws InterruptedException {
        long ende = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        while (System.currentTimeMillis() < ende) {
            if (match.isRunning()) {
                return match.laufendesSpiel();
            }
            Thread.sleep(100);
        }
        return fail("Forge hat innerhalb von 2 Minuten kein laufendes Spiel geliefert");
    }
}
