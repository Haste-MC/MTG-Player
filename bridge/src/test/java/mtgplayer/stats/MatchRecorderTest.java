package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.GameEndReason;
import forge.game.card.Card;
import forge.game.ability.AbilityKey;
import forge.game.event.GameEventLandPlayed;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Der Recorder ist reine Ereignis-Buchhaltung an Forges Game-EventBus. Geprueft wird im
 * Szenen-Harness: Landabgabe und Kampfschaden entstehen aus echten Spielzuegen (die KI spielt ein
 * Land und greift an), die restlichen Zaehlungen werden ueber {@code game.fireEvent(...)} mit
 * denselben Ereignisobjekten eingespeist, die Forge im echten Spiel feuert - genau das, was der
 * Recorder sieht.
 */
class MatchRecorderTest {

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

    // ---------------------------------------------------------------- echte Spielzuege

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void echterZugZaehltLandZauberUndKampfschaden() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Forest", 3, a, ZoneType.Battlefield);
        s.card("Forest", a, ZoneType.Hand);
        s.card("Grizzly Bears", a, ZoneType.Hand);
        s.card("Grizzly Bears", a, ZoneType.Battlefield);   // nicht einsatzverzoegert -> kann angreifen
        s.setPhase(PhaseType.MAIN1, a);

        int lifeBefore = b.getLife();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.loopUntil(PhaseType.END_OF_TURN, a);
        MatchRecord r = rec.finish();

        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertTrue(sa.lands() >= 1, "die KI sollte ihr Land gespielt haben, war " + sa.lands());
        assertTrue(sa.spells() >= 1, "die KI sollte die Baeren gewirkt haben, war " + sa.spells());
        assertTrue(sa.spellMana() >= 2, "Mana-Summe aus der CMC der Baeren, war " + sa.spellMana());
        assertTrue(sb.damageTaken() >= 2, "B sollte Kampfschaden genommen haben, war " + sb.damageTaken());
        assertEquals(sb.damageTaken(), sb.combatDamageTaken(), "aller Schaden kam aus dem Kampf");
        assertEquals(sb.damageTaken(), sa.damageDealt(), "Schaden wird dem Verursacher gutgeschrieben");
        assertEquals(lifeBefore - sb.damageTaken(), sb.lifeEnd());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void sitzdatenQuelleDeckMenschUndKi() {
        Scene s = Scene.twoPlayers(new AiConfig(AiConfig.Mode.SIM, "Default"), AiConfig.DEFAULT);
        MatchRecord r = new MatchRecorder(s.game(), "spectate", null).finish();

        assertEquals("spectate", r.source());
        assertEquals(2, r.seats().size());
        MatchRecord.Seat sa = seat(r, "A");
        assertFalse(sa.human(), "Szenen-Sitze sind KIs");
        assertNotNull(sa.ai(), "KI-Sitz traegt seine AiConfig");
        assertEquals("sim", sa.ai().mode());
        assertEquals("Default", sa.ai().profile());
        assertEquals("standard", seat(r, "B").ai().mode());
        assertNotNull(r.id());
        assertNotNull(r.startedAt());
        assertNotNull(r.endedAt());
    }

    /**
     * Die Bedenkzeit je KI-Entscheidung steht dem Aufrufer beim Start ohnehin zur Verfuegung
     * ({@code HumanMatch}/{@code AiMatch} setzen sie); ohne sie im Datensatz laesst sich eine lange
     * Partie spaeter nicht mehr mit ihrem Budget erklaeren.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void datensatzTraegtDieUebergebeneBedenkzeit() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecord r = new MatchRecorder(s.game(), "spectate", 12, null).finish();

        assertEquals(12, r.aiTimeout());
    }

    /** Wer die Bedenkzeit nicht kennt (Altaufrufe, Tests), schreibt kein erfundenes Budget hinein. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ohneBekannteBedenkzeitBleibtDasFeldLeer() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecord r = new MatchRecorder(s.game(), "live", null).finish();

        assertNull(r.aiTimeout());
    }

    // ---------------------------------------------------------------- Buchhaltung ueber Ereignisse

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void laenderJeZugUndVerpassteLandabgaben() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.card("Forest", a, ZoneType.Hand);          // eine verpasste Abgabe zaehlt nur mit Hand
        s.card("Forest", b, ZoneType.Hand);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventLandPlayed(a.getView(), s.card("Forest", a, ZoneType.Battlefield).getView()));
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));          // A hatte ein Land -> keine Luecke
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));          // B ohne Land -> Luecke in B's 1. Zug
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 4));          // A ohne Land -> Luecke in A's 2. Zug
        MatchRecord r = rec.finish();                                        // laufender Zug von B zaehlt nicht

        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(4, r.turns());
        assertEquals(1, sa.lands());
        assertEquals(List.of(0, 1, 1), sa.landsByTurn(), "Index = eigener Zug, Wert = Laender bis dahin");
        assertEquals(1, sa.missedLandDrops());
        assertEquals(2, sa.firstMissedLandDrop());
        assertEquals(0, sb.lands());
        assertEquals(List.of(0, 0, 0), sb.landsByTurn());
        assertEquals(1, sb.missedLandDrops(), "B's laufender 2. Zug wird nicht gewertet");
        assertEquals(1, sb.firstMissedLandDrop());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mulligansZaehlenJeSitz() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.game().fireEvent(new GameEventMulligan(s.player(0).getView()));
        s.game().fireEvent(new GameEventMulligan(s.player(0).getView()));
        s.game().fireEvent(new GameEventMulligan(s.player(1).getView()));

        MatchRecord r = rec.finish();
        assertEquals(2, seat(r, "A").mulligans());
        assertEquals(1, seat(r, "B").mulligans());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void commanderCastsSteuerUndErsterCommanderZug() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        Card cmd = s.card("Grizzly Bears", a, ZoneType.Command);
        cmd.setCommander(true);
        a.addCommander(cmd);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));          // A's 2. eigener Zug
        SpellAbility sa = cmd.getFirstSpellAbility();
        s.game().fireEvent(new GameEventSpellAbilityCast(SpellAbilityView.get(sa), null, 0, null));
        a.incCommanderCast(cmd);
        a.incCommanderCast(cmd);                                             // zweimal gewirkt -> Steuer 2

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa2 = seat(r, "A");
        assertEquals(1, sa2.commanderCasts());
        assertEquals(2, sa2.firstCommanderTurn(), "erster Commander-Cast in A's 2. eigenem Zug");
        assertEquals(2, sa2.commanderTax(), "2 * (Casts - 1) je Commander");
        assertEquals(1, sa2.spells(), "der Commander-Zauber zaehlt auch als Zauber");
        assertEquals(2, sa2.spellMana());
        assertEquals(0, seat(r, "B").commanderTax());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void verlustgrundLebenAufNullUndSieger() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));

        b.setLife(0, null);
        s.game().getAction().checkStateEffects(true);                        // zustandsbasierte Aktionen
        assertTrue(s.game().isGameOver(), "B sollte bei 0 Leben verlieren");

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertTrue(sa.winner(), "A gewinnt");
        assertNull(sa.lossReason());
        assertNull(sa.eliminatedTurn());
        assertFalse(sb.winner());
        assertEquals("LifeReachedZero", sb.lossReason());
        assertEquals(3, sb.eliminatedTurn());
        assertEquals(0, sb.lifeEnd());
        assertFalse(r.draw());
        assertTrue(r.counted(), "3 Zuege, keine Aufgabe, kein Absturz");
        assertNull(r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void verpassteLandabgabeZaehltNurMitHandkarten() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.card("Forest", b, ZoneType.Hand);                                  // A bleibt mit leerer Hand
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));          // schliesst A's Zug: leere Hand
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));          // schliesst B's Zug: Hand voll

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(0, sa.missedLandDrops(), "ohne Handkarte kann man kein Land legen (Spec §1)");
        assertNull(sa.firstMissedLandDrop());
        assertEquals(1, sb.missedLandDrops(), "B hatte eine Hand und hat kein Land gelegt");
        assertEquals(1, sb.firstMissedLandDrop());
    }

    // ---------------------------------------------------------------- Vorfall-Kennzahlen (Runde B)

    /**
     * Beide Seiten eines Konters in einem Bild: A wirkt die Baeren, B den Counterspell, danach
     * verlaesst der Baeren-Zauber den Stapel, ohne aufgeloest zu haben. Gewirkt wird ueber
     * {@code MagicStack.add} - genau der Weg, auf dem Forge sein {@code GameEventSpellAbilityCast}
     * feuert, und der Zauber liegt dabei wirklich auf dem Stapel (nur dort findet der Recorder die
     * Faehigkeitskette zum {@code ApiType.Counter}).
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gekonterterZauberZaehltBeimZaubernden() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Card bears = s.card("Grizzly Bears", a, ZoneType.Hand);
        Card counter = s.card("Counterspell", b, ZoneType.Hand);
        SpellAbility bearsSa = bears.getFirstSpellAbility();
        bearsSa.setActivatingPlayer(a);
        SpellAbility counterSa = counter.getFirstSpellAbility();
        counterSa.setActivatingPlayer(b);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().getStack().add(bearsSa);
        counterSa.getTargets().add(bearsSa);                 // ohne Ziel legt Forge ihn gar nicht erst
        s.game().getStack().add(counterSa);
        // Forge entfernt einen gekonterten Zauber ohne vorheriges GameEventSpellResolved vom Stapel.
        s.game().fireEvent(new GameEventSpellRemovedFromStack(SpellAbilityView.get(bearsSa)));

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(1, sa.spellsCountered(), "A's Baeren haben den Stapel ohne Aufloesung verlassen");
        assertEquals(0, sa.counterspellsCast(), "die Baeren sind kein Counterspell");
        assertEquals(1, sb.spells(), "der Counterspell zaehlt als gewirkter Zauber");
        assertEquals(1, sb.counterspellsCast(), "B hat einen Zauber mit ApiType.Counter gewirkt");
        assertEquals(0, sb.spellsCountered());
        assertEquals(0, sa.spellsFizzled());
    }

    /**
     * Nach jeder normalen Aufloesung feuert Forge erst {@code GameEventSpellResolved} und danach
     * {@code GameEventSpellRemovedFromStack} fuer denselben Zauber - ohne Merkliste waere jeder
     * aufgeloeste Zauber "gekontert".
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aufgeloesterZauberGiltNichtAlsGekontertVerpuffterZaehltGesondert() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        SpellAbilityView resolved = SpellAbilityView.get(
                s.card("Grizzly Bears", a, ZoneType.Hand).getFirstSpellAbility());
        SpellAbilityView fizzled = SpellAbilityView.get(
                s.card("Shock", a, ZoneType.Hand).getFirstSpellAbility());
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventSpellResolved(resolved, false, "Grizzly Bears"));
        s.game().fireEvent(new GameEventSpellRemovedFromStack(resolved));
        s.game().fireEvent(new GameEventSpellResolved(fizzled, true, "Shock"));
        s.game().fireEvent(new GameEventSpellRemovedFromStack(fizzled));

        MatchRecord.Seat sa = seat(rec.finish(), "A");
        assertEquals(0, sa.spellsCountered(), "aufgeloest und verpufft sind nicht gekontert");
        assertEquals(1, sa.spellsFizzled(), "nur der Zauber mit hasFizzled zaehlt");
    }

    /** Echte Zonenwechsel ueber Forges eigene Wege ({@code drawCards}, {@code discard},
     *  {@code moveTo}); der Recorder sieht dabei dieselben Ereignisse wie im Spiel. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ziehenAbwerfenUndMillenAusEchtenZonenwechseln() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        s.cards("Forest", 10, a, ZoneType.Library);
        Card handkarte = s.card("Grizzly Bears", a, ZoneType.Hand);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        a.drawCards(2);
        a.discard(handkarte, null, false, AbilityKey.newMap());
        List<Card> bibliothek = new ArrayList<>();
        for (Card c : a.getCardsIn(ZoneType.Library)) {
            bibliothek.add(c);
        }
        for (int i = 0; i < 3; i++) {
            s.game().getAction().moveTo(ZoneType.Graveyard, bibliothek.get(i), null, null);   // gemillt
        }

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A"), sb = seat(r, "B");
        assertEquals(2, sa.cardsDrawn());
        assertEquals(1, sa.cardsDiscarded());
        assertEquals(3, sa.cardsMilled(), "Bibliothek -> Friedhof, nicht ueber die Hand");
        assertEquals(0, sb.cardsDrawn());
        assertEquals(0, sb.cardsDiscarded());
        assertEquals(0, sb.cardsMilled());
    }

    /** Echter Zug: A greift mit einem 3/3 an, B steht auf 3 Leben und muss chump-blocken. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void kreaturImKampfVerloren() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Forest", 5, a, ZoneType.Library);
        s.cards("Forest", 5, b, ZoneType.Library);
        s.card("Hill Giant", a, ZoneType.Battlefield);        // nicht einsatzverzoegert -> kann angreifen
        s.card("Grizzly Bears", b, ZoneType.Battlefield);
        b.setLife(3, null);                                    // toedlich -> die KI muss blocken
        s.setPhase(PhaseType.MAIN1, a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.loopUntil(PhaseType.END_OF_TURN, a);

        MatchRecord r = rec.finish();
        MatchRecord.Seat sb = seat(r, "B");
        assertEquals(1, sb.permanentsLost(), "B hat die Baeren verloren:\n" + s.log(15));
        assertEquals(1, sb.creaturesLostInCombat(), "gestorben im Kampfschadenschritt:\n" + s.log(15));
        assertEquals(0, sb.creaturesLostOther());
        assertEquals(0, seat(r, "A").permanentsLost(), "der Riese ueberlebt den Chump-Block");
    }

    /** Echter Zug: die KI raeumt die einzige gegnerische Kreatur mit einer Hexerei ab - ausserhalb
     *  des Kampfschadenschritts, also {@code creaturesLostOther}. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void kreaturPerEntfernungVerloren() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Forest", 5, a, ZoneType.Library);
        s.cards("Forest", 5, b, ZoneType.Library);
        s.cards("Mountain", 2, a, ZoneType.Battlefield);
        s.card("Flame Slash", a, ZoneType.Hand);               // Hexerei, 4 Schaden auf eine Kreatur
        s.card("Hill Giant", b, ZoneType.Battlefield);
        s.setPhase(PhaseType.MAIN1, a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.loopUntil(PhaseType.END_OF_TURN, a);

        MatchRecord r = rec.finish();
        MatchRecord.Seat sb = seat(r, "B");
        assertEquals(1, sb.permanentsLost(), "der Riese sollte weg sein:\n" + s.log(15));
        assertEquals(1, sb.creaturesLostOther(), "gestorben ausserhalb des Kampfes:\n" + s.log(15));
        assertEquals(0, sb.creaturesLostInCombat());
    }

    /**
     * Massenentfernung: vier Kreaturen gehen in EINEM Aufloesungsfenster verloren. Gewirkt und
     * aufgeloest wird ueber den echten Stapel ({@code add} + {@code resolveStack}), damit die
     * Zonenwechsel und das abschliessende {@code GameEventSpellResolved} in der Reihenfolge kommen,
     * in der Forge sie feuert - daran haengt die Fenstergrenze.
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void massenentfernungIstEinFenster() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        s.cards("Grizzly Bears", 4, b, ZoneType.Battlefield);
        Card doj = s.card("Day of Judgment", a, ZoneType.Hand);
        SpellAbility sa = doj.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().getAction().moveToStack(doj, sa);
        s.game().getStack().add(sa);
        s.game().getStack().resolveStack();

        MatchRecord r = rec.finish();
        MatchRecord.Seat sb = seat(r, "B"), sa2 = seat(r, "A");
        assertEquals(4, sb.permanentsLost(), "vier Baeren:\n" + s.state());
        assertEquals(4, sb.creaturesLostOther());
        assertEquals(4, sb.biggestSweep(), "alle vier in einem Fenster");
        assertEquals(1, sb.sweepsSuffered(), "ein Fenster ab drei Verlusten");
        assertEquals(0, sa2.permanentsLost());
        assertEquals(0, sa2.biggestSweep());
        assertEquals(0, sa2.sweepsSuffered());
    }

    /** Spielsteine kommen ohne eigenes Ereignis ins Spiel ({@code GameEventTokenCreated} hat keine
     *  Nutzlast) - gezaehlt wird der Zonenwechsel nach Battlefield mit {@code CardView.isToken}. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void spielsteineZaehlenBeimErzeuger() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        Card fodder = s.card("Dragon Fodder", a, ZoneType.Hand);   // "Create two 1/1 red Goblin tokens."
        SpellAbility sa = fodder.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().getAction().moveToStack(fodder, sa);
        s.game().getStack().add(sa);
        s.game().getStack().resolveStack();

        MatchRecord r = rec.finish();
        assertEquals(2, seat(r, "A").tokensCreated(), "zwei Goblins:\n" + s.state());
        assertEquals(0, seat(r, "B").tokensCreated());
    }

    /** Ein Ereignis ohne Quelle darf die Erfassung weder abschiessen noch verfaelschen. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ereignisseOhneQuelleBrechenDieErfassungNichtAb() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0);
        s.cards("Forest", 5, a, ZoneType.Library);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);

        s.game().fireEvent(new GameEventCardChangeZone((CardView) null, null, null));
        s.game().fireEvent(new GameEventCardChangeZone(
                s.card("Grizzly Bears", a, ZoneType.Hand).getView(),
                new ZoneView(null, ZoneType.Hand), new ZoneView(null, ZoneType.Graveyard)));
        s.game().fireEvent(new GameEventSpellRemovedFromStack(null));        // MagicStack.clear()
        s.game().fireEvent(new GameEventSpellResolved(null, true, null));
        a.drawCards(1);                                                      // danach zaehlt es weiter

        MatchRecord r = rec.finish();
        MatchRecord.Seat sa = seat(r, "A");
        assertEquals(0, rec.counterFailures(), "kein Zaehler ist in eine Ausnahme gelaufen");
        assertEquals(0, sa.cardsDiscarded(), "ohne Sitz an der Zone wird nichts zugeordnet");
        assertEquals(0, sa.spellsCountered());
        assertEquals(0, sa.spellsFizzled());
        assertEquals(1, sa.cardsDrawn(), "die Erfassung laeuft nach den kaputten Ereignissen weiter");
    }

    // ---------------------------------------------------------------- nicht gewertete Partien

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zuKurzWirdNichtGewertet() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));

        MatchRecord r = rec.finish();
        assertEquals(2, r.turns());
        assertFalse(r.counted());
        assertEquals("zu kurz", r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aufgabeWirdNichtGewertet() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(b.getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(a.getView(), 3));
        b.concede();
        s.game().getAction().checkStateEffects(true);

        MatchRecord r = rec.finish();
        assertEquals("Conceded", seat(r, "B").lossReason());
        assertFalse(r.counted());
        assertEquals("aufgegeben", r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void absturzWirdNichtGewertet() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 3));

        CrashLog.report("Game-0", "kaputt", new IllegalStateException("Testabsturz"));

        MatchRecord r = rec.finish();
        assertFalse(r.counted());
        assertEquals("Absturz", r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void abgewuergtePartieWirdNichtGewertet() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "spectate", null);
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 3));

        rec.markAborted();                                                   // HumanMatch.end()

        MatchRecord r = rec.finish();
        assertFalse(r.counted());
        assertEquals("abgebrochen", r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void abgewuergtePartieMachtKeinenSitzZumSieger() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "spectate", null);
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 3));

        rec.markAborted();
        s.game().setGameOver(GameEndReason.AllHumansLost);                   // genau das macht HumanMatch.end()
        assertTrue(s.game().getRegisteredPlayers().stream().allMatch(p -> p.getOutcome() != null && p.getOutcome().hasWon()),
                "Forge macht dabei jeden Sitz ohne eigenen Ausgang zum Sieger - genau davor schuetzt build()");

        MatchRecord r = rec.finish();
        assertTrue(r.seats().stream().noneMatch(MatchRecord.Seat::winner),
                "eine abgebrochene Partie darf keinen Sieg erfinden, auch nicht nach erneutem Werten");
        assertFalse(r.draw(), "und sie ist auch kein Remis");
        assertEquals("abgebrochen", r.excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void zugdeckelWirdNichtGewertetLaesstDenSitzenAberIhrRemis() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "sparring", null);
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 1));
        s.game().fireEvent(new GameEventTurnBegan(s.player(1).getView(), 2));
        s.game().fireEvent(new GameEventTurnBegan(s.player(0).getView(), 3));

        rec.markTurnCapped();                                                // AiMatch am Zugdeckel
        for (Player p : s.game().getPlayers()) {                             // genau das macht AiMatch dort auch
            p.intentionalDraw();
        }
        s.game().setGameOver(GameEndReason.Draw);

        MatchRecord r = rec.finish();
        assertFalse(r.counted());
        assertEquals("Zugdeckel", r.excludeReason());
        assertTrue(r.draw(), "am Deckel ist es ein echtes Remis - der Ausgang bleibt stehen");
        assertTrue(r.seats().stream().noneMatch(MatchRecord.Seat::winner), "ein Remis hat keinen Sieger");
        assertEquals("IntentionalDraw", seat(r, "A").lossReason(), "der Verlustgrund des Sitzes bleibt unangetastet");
        assertEquals("IntentionalDraw", seat(r, "B").lossReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void absturzUndAbbruchGehenDemZugdeckelVor() {
        Scene crash = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder recCrash = new MatchRecorder(crash.game(), "sparring", null);
        recCrash.markTurnCapped();
        recCrash.markCrashed();
        assertEquals("Absturz", recCrash.finish().excludeReason(),
                "ein Absturz erklaert auch den Deckel, der danach noch gesetzt wurde");

        Scene abort = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder recAbort = new MatchRecorder(abort.game(), "sparring", null);
        recAbort.markTurnCapped();
        recAbort.markAborted();
        assertEquals("abgebrochen", recAbort.finish().excludeReason());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void nachFinishMeldetDerRecorderKeineAbstuerzeMehr() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        MatchRecord r = rec.finish();

        CrashLog.report("Game-0", "spaeter Absturz", new IllegalStateException("danach"));
        assertSame(r, rec.finish(), "finish() bleibt beim einmal gebauten Datensatz");
        assertEquals("zu kurz", r.excludeReason(), "der Absturz nach finish() gehoert nicht mehr zu dieser Partie");
    }

    // ---------------------------------------------------------------- Abschluss

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void finishIstIdempotentUndLiefertDenDatensatzGenauEinmalAnDenSink() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        List<MatchRecord> sunk = new ArrayList<>();
        MatchRecorder rec = new MatchRecorder(s.game(), "live", sunk::add);

        MatchRecord first = rec.finish();
        MatchRecord second = rec.finish();

        assertSame(first, second);
        assertEquals(1, sunk.size(), "der Sink bekommt die Partie genau einmal");
        assertSame(first, sunk.get(0));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void ereignisseNachFinishAendernDenDatensatzNichtMehr() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        MatchRecorder rec = new MatchRecorder(s.game(), "live", null);
        MatchRecord r = rec.finish();

        s.game().fireEvent(new GameEventMulligan(s.player(0).getView()));
        assertEquals(0, seat(rec.finish(), "A").mulligans());
        assertEquals(0, seat(r, "A").mulligans());
    }

    /** Stueck 3 schreibt Sparring-Partien in Folge zurueck - zwei gleiche Ids und
     *  {@code MatchStore.delete} traefe den falschen Datensatz. */
    @Test
    void tausendIdsInEngerSchleifeSindVerschieden() {
        Instant now = Instant.now();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(MatchRecorder.newId(now));
        }
        assertEquals(1000, ids.size(), "Ids muessen auch innerhalb derselben Millisekunde eindeutig sein");
    }
}
