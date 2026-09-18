package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class WebGuiGameTest {

    private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final WebGuiGame gui = new WebGuiGame(m -> sent.add(Json.toJson(m)));

    @BeforeAll
    static void boot() {
        ForgeBoot.init(); // Localizer für Button-Labels
    }

    private JsonNode nextChoice() throws InterruptedException {
        while (true) {
            String s = sent.poll(5, TimeUnit.SECONDS);
            if (s == null) throw new AssertionError("keine choice-Nachricht");
            JsonNode n = Json.parse(s);
            if ("choice".equals(n.get("type").asText())) return n;
        }
    }

    @Test
    void confirmWirdZuConfirmChoice() throws Exception {
        CompletableFuture<Boolean> r = CompletableFuture.supplyAsync(
                () -> gui.showConfirmDialog("Aufgeben?", "Concede", "Ja", "Nein", false));
        JsonNode c = nextChoice();
        assertEquals("confirm", c.get("kind").asText());
        assertEquals("Aufgeben?", c.get("message").asText());
        assertEquals("Ja", c.get("options").get(0).get("label").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("true"));
        assertTrue(r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void getChoicesLiefertGewaehlteObjekteInAntwortReihenfolge() throws Exception {
        List<String> opts = List.of("Rot", "Grün", "Blau");
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Farbe", 1, 2, opts, null, null));
        JsonNode c = nextChoice();
        assertEquals("many", c.get("kind").asText());
        assertEquals(1, c.get("min").asInt());
        assertEquals(2, c.get("max").asInt());
        assertEquals("Grün", c.get("options").get(1).get("label").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[2,0]"));
        assertEquals(List.of("Blau", "Rot"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void getChoicesMitMaxEinsIstKindOne() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Eins", 1, 1, List.of("a", "b"), null, null));
        JsonNode c = nextChoice();
        assertEquals("one", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("1"));
        assertEquals(List.of("b"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void nullAntwortLiefertMinimalauswahl() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("Zwang", 1, 1, List.of("x", "y"), null, null));
        JsonNode c = nextChoice();
        gui.broker().answer(c.get("id").asInt(), null);
        assertEquals(List.of("x"), r.get(5, TimeUnit.SECONDS), "bei null: die ersten min Einträge");
    }

    @Test
    void orderLiefertNeueReihenfolge() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.order("Sortieren", "oben", 0, 0, List.of("a", "b", "c"), null, null, false, false).ordered());
        JsonNode c = nextChoice();
        assertEquals("order", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[2,1,0]"));
        assertEquals(List.of("c", "b", "a"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void orderMitRemainingBoundsIstTeilauswahl() throws Exception {
        // many(min=1,max=2) auf 4 Karten → order(remainingMin=2, remainingMax=3)
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.order("Scry", "unten", 2, 3, List.of("a", "b", "c", "d"), null, null, false, false).ordered());
        JsonNode c = nextChoice();
        assertEquals("many", c.get("kind").asText());
        assertEquals(1, c.get("min").asInt());
        assertEquals(2, c.get("max").asInt());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[3,1]"));
        assertEquals(List.of("d", "b"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void manyUeberAbstractGuiGameWaehltTeilmenge() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.many("Discard", "weg", 2, 2, List.of("a", "b", "c", "d", "e"), null));
        JsonNode c = nextChoice();
        assertEquals("many", c.get("kind").asText());
        assertEquals(2, c.get("min").asInt());
        assertEquals(2, c.get("max").asInt());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[4,0]"));
        assertEquals(List.of("e", "a"), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void orderMitUnbegrenztemRestErlaubtLeereAuswahl() throws Exception {
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.order("Opt", "x", -1, -1, List.of("a", "b"), null, null, false, false).ordered());
        JsonNode c = nextChoice();
        assertEquals(0, c.get("min").asInt());
        assertEquals(2, c.get("max").asInt());
        gui.broker().answer(c.get("id").asInt(), Json.parse("[]"));
        assertEquals(List.of(), r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void showOptionDialogLiefertIndex() throws Exception {
        CompletableFuture<Integer> r = CompletableFuture.supplyAsync(
                () -> gui.showOptionDialog("Frage", "Titel", null, List.of("A", "B", "C"), 0));
        JsonNode c = nextChoice();
        assertEquals("one", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("2"));
        assertEquals(2, r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void showInputDialogNumerisch() throws Exception {
        CompletableFuture<String> r = CompletableFuture.supplyAsync(
                () -> gui.showInputDialog("X?", "Titel", null, "0", null, true));
        JsonNode c = nextChoice();
        assertEquals("number", c.get("kind").asText());
        gui.broker().answer(c.get("id").asInt(), Json.parse("7"));
        assertEquals("7", r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void showInputDialogNullAntwortIstAbbruch() throws Exception {
        CompletableFuture<String> r = CompletableFuture.supplyAsync(
                () -> gui.showInputDialog("X?", "Titel", null, "0", null, true));
        JsonNode c = nextChoice();
        gui.broker().answer(c.get("id").asInt(), null);
        assertEquals(null, r.get(5, TimeUnit.SECONDS));
    }

    @Test
    void isUiSetToSkipPhaseIstInM2Falsch() {
        assertFalse(gui.isUiSetToSkipPhase(null, null));
    }

    @Test
    void isUiSetToSkipPhaseFolgtStopsUndFullControl() {
        assertTrue(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP), "ohne Spiel: eigener Zug angenommen, UPKEEP wird übersprungen");
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.MAIN1));
        gui.setFullControl(true);
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP));
        gui.setFullControl(false);
        gui.setStops(Stops.defaults().with(true, java.util.EnumSet.of(forge.game.phase.PhaseType.UPKEEP)));
        assertFalse(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.UPKEEP));
        assertTrue(gui.isUiSetToSkipPhase(null, forge.game.phase.PhaseType.MAIN1));
    }

    @Test
    void seqWechseltNurBeiInputWechsel() {
        int s0 = gui.currentSeq();
        gui.showPromptMessage(null, "a", null);
        gui.updateButtons(null, "OK", "Cancel", true, true, true);
        assertEquals(s0, gui.currentSeq(), "re-push des gleichen Inputs bumpt nicht");
        gui.onInputChanged();
        assertEquals(s0 + 1, gui.currentSeq());
    }

    @Test
    void eingabenMitFalscherSeqWerdenVerworfen() {
        gui.onInputChanged();
        int seq = gui.currentSeq();
        assertTrue(gui.seqOk(null), "ohne seq: akzeptiert");
        assertTrue(gui.seqOk(seq));
        assertFalse(gui.seqOk(seq - 1));
        assertFalse(gui.onOk(seq - 1), "veraltetes ok wird verworfen");
        assertFalse(gui.onSelectCard(1, false, seq + 5), "seq aus der Zukunft wird verworfen");
    }

    @Test
    void optionenTragenKartendetailsWennSichtbar() throws Exception {
        // ohne GameView: detail bleibt null, aber die Struktur muss serialisierbar sein
        CompletableFuture<List<String>> r = CompletableFuture.supplyAsync(
                () -> gui.getChoices("x", 1, 1, List.of("a"), null, null));
        JsonNode c = nextChoice();
        assertFalse(c.get("options").get(0).has("detail"));
        gui.broker().answer(c.get("id").asInt(), Json.parse("0"));
        r.get(5, TimeUnit.SECONDS);
    }

    @Test
    void getChoicesMitMinMaxMinusEinsIstRevealOhneBlockieren() throws Exception {
        // min < 0 && max < 0 ist Forges Konvention fuer "nur anzeigen" (reveal) - kein Future noetig,
        // der Aufruf muss sofort zurueckkehren statt auf eine Antwort zu warten.
        List<String> out = gui.getChoices("Zeig her", -1, -1, List.of("a", "b"), null, null);
        assertTrue(out.isEmpty());

        JsonNode c = nextChoice();
        assertEquals("reveal", c.get("kind").asText());
        assertEquals(2, c.get("options").size());
    }
}
