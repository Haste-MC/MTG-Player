package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kampf, Schadensquellen, Lebensgewinn und Zeitachse (Runde B, Stueck 2). Angriff, Block und der
 * Schaden einer fliegenden Kreatur entstehen aus echten Spielzuegen im Szenen-Harness; die
 * uebrigen Faelle (Planeswalker als Verteidiger, Trample, Nicht-Kampfschaden, Commander-Schaden,
 * Lebensgewinn, Zeitachse) werden ueber {@code game.fireEvent(...)} mit genau den
 * Ereignisobjekten eingespeist, die Forge im echten Spiel feuert.
 */
class MatchRecorderCombatTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} schreiben, falls etwas meldet. */
    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    private static MatchRecord.Seat seat(MatchRecord r, String name) {
        return r.seats().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("kein Sitz " + name + " in " + r.seats()));
    }

    /** Ein {@code GameEventAttackersDeclared}, wie {@code PhaseHandler} es baut: Schluessel = Verteidiger. */
    private static GameEventAttackersDeclared angriff(Player attacker, GameEntityView defender, CardView... attackers) {
        Multimap<GameEntityView, CardView> map = ArrayListMultimap.create();
        for (CardView c : attackers) {
            map.put(defender, c);
        }
        return new GameEventAttackersDeclared(attacker.getView(), map);
    }

    /**
     * Ein {@code GameEventBlockersDeclared}, wie {@code PhaseHandler} es baut: Verteidiger =>
     * (Angreifer => Blocker). Ein UNGEBLOCKTER Angreifer steht dort als sein eigener "Blocker"
     * ({@code combat.getBlockers(att).isEmpty() ? List.of(att) : ...}) - genau dieser Fall wird hier
     * ueber {@code blockers.length == 0} nachgebaut.
     */
    private static GameEventBlockersDeclared block(Player defender, CardView attacker, CardView... blockers) {
        Multimap<CardView, CardView> inner = ArrayListMultimap.create();
        if (blockers.length == 0) {
            inner.put(attacker, attacker);
        }
        for (CardView b : blockers) {
            inner.put(attacker, b);
        }
        return new GameEventBlockersDeclared(defender.getView(), Map.of(defender.getView(), inner));
    }

    // ---------------------------------------------------------------- echte Spielzuege

    /**
     * Echter Zug: A greift mit Riese und Baeren an, B steht auf 3 Leben und muss blocken, um den Zug
     * zu ueberleben. Gezaehlt werden beide Seiten desselben Kampfes.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void echterAngriffZaehltAngreiferVerteidigerUndBlocker() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Forest", 5, a, ZoneType.Library);
        s.cards("Forest", 5, b, ZoneType.Library);
        s.card("Hill Giant", a, ZoneType.Battlefield);        // nicht einsatzverzoegert -> kann angreifen
        s.card("Grizzly Bears", a, ZoneType.Battlefield);
        s.card("Grizzly Bears", b, ZoneType.Battlefield);
        b.setLife(3, null);                                    // toedlich -> die KI muss blocken
        s.setPhase(PhaseType.MAIN1, a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.loopUntil(PhaseType.END_OF_TURN, a);

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertTrue(sa.attacksDeclared() >= 1, "A sollte angegriffen haben:\n" + s.log(20));
        assertEquals(1, sa.attackedTurns(), "ein Zug mit Angriff");
        assertEquals(sa.attacksDeclared(), sb.attackersFaced(), "dieselben Angreifer aus B's Sicht");
        assertTrue(sb.blocksDeclared() >= 1, "B blockt, sonst stirbt B:\n" + s.log(20));
        assertEquals(0, sb.attacksDeclared(), "B war nicht am Zug");
        assertEquals(0, sb.attackedTurns());
        assertEquals(0, sa.attackersFaced(), "gegen A greift niemand an");
        assertEquals(0, sa.blocksDeclared(), "A blockt im eigenen Zug nicht");
    }

    /** Echter Zug: ein 2/2-Flieger kommt durch, der Schaden landet in {@code damageTakenFlying}. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void fliegenderSchadenAusEchtemKampf() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.card("Wind Drake", a, ZoneType.Battlefield);         // 2/2 fliegend, nicht einsatzverzoegert
        s.setPhase(PhaseType.MAIN1, a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.loopUntil(PhaseType.END_OF_TURN, a);

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(2, sb.damageTakenFlying(), "der Drake kommt durch:\n" + s.log(20));
        assertEquals(0, sb.damageTakenTrample());
        assertEquals(0, sb.damageTakenOther());
        assertEquals(0, sb.damageTakenNonCombat());
        assertEquals(2, sa.damageDealtCombat(), "dem Kontrolleur der Quelle gutgeschrieben");
        assertEquals(0, sa.damageDealtNonCombat());
        assertEquals(0, sb.commanderDamageTaken(), "der Drake ist kein Commander");
    }

    // ---------------------------------------------------------------- Kampf ueber Ereignisse

    /**
     * Forge traegt einen ungeblockten Angreifer in {@code GameEventBlockersDeclared} als seinen
     * eigenen "Blocker" ein. Wer das mitzaehlt, meldet Bloecke, die es nie gab.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ungeblockterAngreiferZaehltNichtAlsBlock() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        CardView att = s.card("Hill Giant", a, ZoneType.Battlefield).getView();
        CardView blocker = s.card("Grizzly Bears", b, ZoneType.Battlefield).getView();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(block(b, att));                     // ungeblockt
        s.game().fireEvent(block(b, att, blocker));            // echt geblockt

        MatchRecord r = rec.finish();
        assertEquals(1, seat(r, "B").blocksDeclared(), "nur der echte Block zaehlt");
        assertEquals(0, seat(r, "A").blocksDeclared());
    }

    /** Ein Angriff auf einen Planeswalker zaehlt beim Kontrolleur des Planeswalkers. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void angriffAufPlaneswalkerZaehltBeimKontrolleur() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Card pw = s.card("Chandra, Pyromaster", b, ZoneType.Battlefield);
        CardView att1 = s.card("Hill Giant", a, ZoneType.Battlefield).getView();
        CardView att2 = s.card("Grizzly Bears", a, ZoneType.Battlefield).getView();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(angriff(a, pw.getView(), att1));
        s.game().fireEvent(angriff(a, b.getView(), att2));

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(2, sa.attacksDeclared(), "beide Angreifer zaehlen bei A");
        assertEquals(2, sb.attackersFaced(), "Planeswalker und Spieler gehen beide an B");
        assertEquals(0, sa.attackersFaced());
    }

    /** Zwei Kaempfe in einem Zug (Extra-Kampfphase) sind ein Angriffszug, nicht zwei. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zweiKaempfeInEinemZugSindEinAngriffszug() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        CardView att1 = s.card("Hill Giant", a, ZoneType.Battlefield).getView();
        CardView att2 = s.card("Grizzly Bears", a, ZoneType.Battlefield).getView();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(angriff(a, b.getView(), att1, att2));
        s.game().fireEvent(angriff(a, b.getView(), att1));     // zweite Kampfphase, selber Zug
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));
        s.game().fireEvent(angriff(a, b.getView(), att1));

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A");
        assertEquals(4, sa.attacksDeclared(), "jede Deklaration zaehlt jeden Angreifer");
        assertEquals(2, sa.attackedTurns(), "Zug 1 und Zug 3");
        assertEquals(4, seat(r, "B").attackersFaced());
    }

    // ---------------------------------------------------------------- Schadensquellen

    /**
     * Kampfschaden wird nach dem Schluesselwort der Quelle getrennt, Nicht-Kampfschaden steht fuer
     * sich. Fliegen geht vor Trample: eine Quelle mit beidem zaehlt als Flieger, sonst waere die
     * Zuordnung von der Reihenfolge der Abfrage abhaengig.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void schadensquellenNachSchluesselwortUndKampf() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        CardView drake = s.card("Wind Drake", a, ZoneType.Battlefield).getView();
        CardView dreadmaw = s.card("Colossal Dreadmaw", a, ZoneType.Battlefield).getView();
        CardView bears = s.card("Grizzly Bears", a, ZoneType.Battlefield).getView();
        CardView shock = s.card("Shock", a, ZoneType.Graveyard).getView();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), drake, 2, true, false));
        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), dreadmaw, 6, true, false));
        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), bears, 4, true, false));
        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), shock, 5, false, false));

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(2, sb.damageTakenFlying());
        assertEquals(6, sb.damageTakenTrample());
        assertEquals(4, sb.damageTakenOther());
        assertEquals(5, sb.damageTakenNonCombat());
        assertEquals(17, sb.damageTaken(), "die Summe bleibt der alte Gesamtzaehler");
        assertEquals(12, sa.damageDealtCombat());
        assertEquals(5, sa.damageDealtNonCombat());
        assertEquals(0, sb.commanderDamageTaken());
    }

    /** Commander-Schaden ist Kampfschaden UND zaehlt zusaetzlich gesondert - nicht statt. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void commanderSchadenZaehltZusaetzlich() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Card cmd = s.card("Hill Giant", a, ZoneType.Battlefield);
        cmd.setCommander(true);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), cmd.getView(), 3, true, false));

        MatchRecord r = rec.finish();
        MatchRecord.Seat sb = seat(r, "B");
        assertEquals(3, sb.commanderDamageTaken());
        assertEquals(3, sb.damageTakenOther(), "der Riese hat kein Schluesselwort");
        assertEquals(3, sb.damageTaken());
    }

    /** Nur positive Lebensaenderungen zaehlen; Schaden und Lebensverlust nicht. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void lebensgewinnZaehltNurNachOben() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        a.gainLife(4, null, null);                             // echter Effekt, feuert das Ereignis
        a.loseLife(9, false, false, null);
        s.game().fireEvent(new GameEventPlayerLivesChanged(b.getView(), 20, 23));

        MatchRecord r = rec.finish();
        assertEquals(4, seat(r, "A").lifeGained(), "nur der Gewinn, der Verlust nicht");
        assertEquals(3, seat(r, "B").lifeGained());
    }

    // ---------------------------------------------------------------- Zeitachse

    /** Je eigenem Zug ein Punkt mit dem Stand zu Zugbeginn; die Zugnummer ist Forges globale. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zeitachseHaeltDenStandJedesEigenenZugesFest() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Forest", 2, a, ZoneType.Battlefield);
        s.card("Grizzly Bears", a, ZoneType.Battlefield);
        s.cards("Forest", 3, a, ZoneType.Hand);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.card("Forest", a, ZoneType.Battlefield);             // zwischen den Zuegen dazugekommen
        a.setLife(17, null);
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));

        MatchRecord r = rec.finish();
        List<MatchRecord.TurnPoint> tl = seat(r, "A").timeline();
        assertEquals(2, tl.size(), "zwei eigene Zuege, zwei Punkte: " + tl);
        assertEquals(new MatchRecord.TurnPoint(1, 2, 1, 20, 3), tl.get(0));
        assertEquals(new MatchRecord.TurnPoint(3, 3, 1, 17, 3), tl.get(1));
        assertEquals(List.of(new MatchRecord.TurnPoint(2, 0, 0, 20, 0)), seat(r, "B").timeline());
    }

    /** Lange Partien duerfen den Datensatz nicht aufblaehen - der Deckel greift bei 60 Punkten. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zeitachseIstAufSechzigPunkteGedeckelt() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        for (int turn = 1; turn <= 70; turn++) {
            s.game().fireEvent(new GameEventTurnBegan(a.getView(), turn));
        }

        MatchRecord r = rec.finish();
        List<MatchRecord.TurnPoint> tl = seat(r, "A").timeline();
        assertEquals(MatchRecorder.TIMELINE_MAX, tl.size());
        assertEquals(1, tl.get(0).turn(), "gedeckelt wird hinten, der Anfang bleibt stehen");
        assertEquals(MatchRecorder.TIMELINE_MAX, tl.get(tl.size() - 1).turn());
    }

    /** Ohne einen einzigen Zug ist die Zeitachse leer - und nie {@code null}. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneZuegeIstDieZeitachseLeerNichtNull() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecord r = new MatchRecorder(s.game(), "live", null).finish();

        for (MatchRecord.Seat st : r.seats()) {
            assertNotNull(st.timeline(), "timeline ist nie null");
            assertEquals(List.of(), st.timeline());
        }
    }

    // ---------------------------------------------------------------- Robustheit

    /** Nutzlast ohne Spieler oder Quelle darf weder abschiessen noch etwas erfinden. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void kaputteKampfereignisseBrechenDieErfassungNichtAb() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        CardView att = s.card("Hill Giant", a, ZoneType.Battlefield).getView();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventAttackersDeclared((PlayerView) null, null));
        s.game().fireEvent(new GameEventBlockersDeclared((PlayerView) null, null));
        s.game().fireEvent(new GameEventPlayerLivesChanged((PlayerView) null, 20, 25));
        s.game().fireEvent(new GameEventPlayerDamaged(b.getView(), null, 3, true, false));
        s.game().fireEvent(angriff(a, b.getView(), att));      // danach zaehlt es weiter

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(0, rec.counterFailures(), "kein Zaehler ist in eine Ausnahme gelaufen");
        assertEquals(3, sb.damageTakenOther(), "Schaden ohne Quelle ist Kampfschaden ohne Schluesselwort");
        assertEquals(0, sa.damageDealtCombat(), "ohne Quelle bekommt ihn niemand gutgeschrieben");
        assertEquals(0, sa.lifeGained());
        assertEquals(1, sa.attacksDeclared(), "die Erfassung laeuft nach den kaputten Ereignissen weiter");
        assertEquals(1, sb.attackersFaced());
    }
}
