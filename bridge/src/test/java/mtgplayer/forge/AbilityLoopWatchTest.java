package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.scene.Scene;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fuettert {@link AbilityLoopWatch} direkt mit Ereignissen - ohne echte Partie: {@link Scene} liefert
 * nur die realen Forge-Objekte (Karte, Faehigkeit, Spieler), die {@code GameEventSpellAbilityCast}
 * braucht ({@code SpellAbilityView}/{@code StackItemView} haben keinen oeffentlichen Konstruktor ohne
 * eine echte {@code SpellAbility}). Die KI-Sitze aus der Szene entscheiden nichts - die Tests rufen
 * {@link AbilityLoopWatch#onSpellAbilityCast} und {@link AbilityLoopWatch#onTurnBegan} direkt auf,
 * statt Forges Ereignisbus zu bemuehen; kein {@code loopUntil}, kein Spielzug laeuft wirklich.
 */
class AbilityLoopWatchTest {

    @TempDir
    Path tmp;

    private Scene scene;
    private AbilityLoopWatch watch;
    private Card lightningGreaves;
    private Card bonesplitter;
    private Player seat;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} des Nutzers schreiben. */
    @BeforeEach
    void setUp() {
        CrashLog.setFile(tmp.resolve("logs").resolve("bridge.log"));
        scene = Scene.twoPlayers(AiConfig.parse("sim:Default"), AiConfig.DEFAULT);
        watch = new AbilityLoopWatch(scene.game());
        seat = scene.player(0);
        lightningGreaves = scene.card("Lightning Greaves", seat, ZoneType.Battlefield);
        bonesplitter = scene.card("Bonesplitter", seat, ZoneType.Battlefield);
        watch.onTurnBegan(new GameEventTurnBegan(PlayerView.get(seat), 1));
    }

    @AfterEach
    void tearDown() {
        CrashLog.setFile(null);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void unterDerSchwelleKommtNichts() throws Exception {
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD - 1; i++) {
            cast(lightningGreaves);
        }
        assertEquals(0, incidentLines().size(), "sieben Aktivierungen sind noch kein Vorfall: " + incidentLines());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void abDerSchwelleGenauEineZeile() throws Exception {
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD; i++) {
            cast(lightningGreaves);
        }
        List<String> lines = incidentLines();
        assertEquals(1, lines.size(), "genau eine Zeile beim Ueberschreiten der Schwelle: " + lines);
        assertTrue(lines.get(0).contains("Lightning Greaves"), lines.get(0));
        assertTrue(lines.get(0).contains("8x"), lines.get(0));
        assertTrue(lines.get(0).contains("Zug 1"), lines.get(0));
        assertTrue(lines.get(0).contains("sim"), "KI-Modus fehlt: " + lines.get(0));

        // Weiter aktivieren im selben Zug darf KEINE zweite Zeile bringen - siehe Klassenkommentar
        // "Zeilenbudget": die Mitschrift soll keine Flut je Aktivierung erzeugen.
        cast(lightningGreaves);
        cast(lightningGreaves);
        assertEquals(1, incidentLines().size(), "kein zweiter Alarm im selben Zug: " + incidentLines());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void neuerZugFaengtDieZaehlungNeuAn() throws Exception {
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD; i++) {
            cast(lightningGreaves);
        }
        assertEquals(1, incidentLines().size());

        watch.onTurnBegan(new GameEventTurnBegan(PlayerView.get(seat), 2));
        // Nach dem Zugwechsel zaehlt dieselbe Karte wieder bei 0 - sieben weitere Aktivierungen
        // bleiben unter der Schwelle des NEUEN Zuges.
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD - 1; i++) {
            cast(lightningGreaves);
        }
        assertEquals(1, incidentLines().size(), "sieben Aktivierungen im neuen Zug sind noch kein Vorfall");

        cast(lightningGreaves);
        List<String> lines = incidentLines();
        assertEquals(2, lines.size(), "der neue Zug darf einen eigenen Vorfall melden: " + lines);
        assertTrue(lines.get(1).contains("Zug 2"), lines.get(1));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zweiVerschiedeneKartenZaehlenGetrennt() throws Exception {
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD - 1; i++) {
            cast(lightningGreaves);
            cast(bonesplitter);
        }
        assertEquals(0, incidentLines().size(), "je sieben Aktivierungen je Karte sind noch kein Vorfall");

        cast(lightningGreaves);
        List<String> afterGreaves = incidentLines();
        assertEquals(1, afterGreaves.size(), afterGreaves.toString());
        assertTrue(afterGreaves.get(0).contains("Lightning Greaves"), afterGreaves.get(0));

        cast(bonesplitter);
        List<String> afterBoth = incidentLines();
        assertEquals(2, afterBoth.size(), "Bonesplitter zaehlt eigenstaendig, nicht zusammen mit Lightning Greaves: " + afterBoth);
        assertTrue(afterBoth.get(1).contains("Bonesplitter"), afterBoth.get(1));
    }

    /**
     * Belegt die Entscheidung aus dem Klassenkommentar "Zeilenbudget": laeuft eine Karte nach dem
     * Alarm im selben Zug weiter, bekommt Kevin am Zugende die Endzahl - er soll unterscheiden koennen,
     * ob eine Karte bei acht aufgehoert hat oder bis zum Zugende auf x weitergelaufen ist.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zugendeMeldetDieEndzahlWennEsWeiterging() throws Exception {
        for (int i = 0; i < AbilityLoopWatch.THRESHOLD + 4; i++) {
            cast(lightningGreaves);
        }
        assertEquals(1, incidentLines().size(), "waehrend des Zuges bleibt es bei einer Zeile");

        watch.onTurnBegan(new GameEventTurnBegan(PlayerView.get(seat), 2));
        List<String> lines = incidentLines();
        assertEquals(2, lines.size(), "der Zugabschluss meldet die Endzahl: " + lines);
        assertTrue(lines.get(1).contains("12"), lines.get(1));
        assertTrue(lines.get(1).contains("Zug 1"), lines.get(1));
    }

    /** {@code getSpellAbilities()} traegt bei einem Artefakt auch die "als Zauber wirken"-Faehigkeit
     *  (Index 0, {@code isSpell() == true}) - das Ausruesten ist die AKTIVIERTE Faehigkeit daneben. */
    private void cast(Card card) {
        SpellAbility equip = card.getSpellAbilities().stream().filter(a -> !a.isSpell()).findFirst()
                .orElseThrow(() -> new IllegalStateException("keine aktivierte Faehigkeit auf " + card.getName()));
        equip.setActivatingPlayer(seat);
        SpellAbilityStackInstance si = new SpellAbilityStackInstance(equip);
        watch.onSpellAbilityCast(new GameEventSpellAbilityCast(equip, si, 0));
    }

    private List<String> incidentLines() throws Exception {
        Path f = CrashLog.file();
        if (!Files.exists(f)) {
            return List.of();
        }
        return Files.readString(f, StandardCharsets.UTF_8).lines()
                .filter(l -> l.contains("AbilityLoopWatch"))
                .toList();
    }
}
