package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.decks.Suggestions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link Messages.CardSuggestionsMsg} OHNE laufende Bridge: im Projekt gibt es keinen Test, der einzelne
 * Nachrichten-Records isoliert prueft (das Protokoll wird sonst ueber eine echte Bridge/WsServer-Verbindung
 * getestet, siehe BridgeEndToEndTest). Fuer suggestCards waere das hier unpassend: Bridge haelt ihr
 * {@code edhrec}-Feld fest auf {@code new Edhrec()} (echter Netzabruf, echter Zwischenspeicher unter
 * {@code ~/.mtg-player/edhrec}) - ein Erfolgspfad ueber eine echte Bridge wuerde also echtes EDHREC treffen,
 * was hier verboten ist. Dieser Test prueft stattdessen nur, dass {@link Messages.CardSuggestionsMsg} die
 * vom Client erwarteten Felder traegt (Spec 2026-09-25-kartenvorschlaege §2); den Fehlerpfad (unbekanntes
 * Deck) deckt BridgeEndToEndTest#suggestCardsUnbekanntesDeckLiefertError ab, ohne Edhrec#page() zu erreichen.
 */
class MessagesTest {

    @Test
    void cardSuggestionsMsgTraegtDieVomClientErwartetenFelder() {
        Suggestions.Cut cut = new Suggestions.Cut("Overrun",
                "spielt in vergleichbaren Decks fast niemand (4 %) und ist mit {2}{G}{G}{G} die teuerste Karte dieser Rolle");
        Suggestions.Item item = new Suggestions.Item("Heroic Intervention", "wipeProtection", "{1}{G}", 2,
                0.68, false, "c:heroic_intervention", "Instant\nUntil end of turn, permanents you control gain hexproof and indestructible.", cut);
        Suggestions.Result result = new Suggestions.Result("edhrec", null, List.of(item));

        Messages.CardSuggestionsMsg msg = new Messages.CardSuggestionsMsg("Mein Deck", result);
        assertEquals("cardSuggestions", msg.type());
        assertEquals("Mein Deck", msg.deck());
        assertEquals("edhrec", msg.source());
        assertEquals(List.of(item), msg.suggestions());

        JsonNode json = Json.parse(Json.toJson(msg));
        assertEquals("cardSuggestions", json.get("type").asText());
        assertEquals("Mein Deck", json.get("deck").asText());
        assertEquals("edhrec", json.get("source").asText());
        assertFalse(json.has("note"), "note ist null und soll fehlen, nicht als null-Feld erscheinen");
        JsonNode suggestion = json.get("suggestions").get(0);
        assertEquals("Heroic Intervention", suggestion.get("name").asText());
        assertEquals("wipeProtection", suggestion.get("role").asText());
        assertEquals("{1}{G}", suggestion.get("manaCost").asText());
        assertEquals(2, suggestion.get("cmc").asInt());
        assertEquals(0.68, suggestion.get("share").asDouble(), 1e-9);
        assertFalse(suggestion.get("gameChanger").asBoolean());
        assertEquals("c:heroic_intervention", suggestion.get("imageKey").asText());
        assertTrue(suggestion.has("text"));
        assertEquals("Overrun", suggestion.get("cut").get("name").asText());
        assertTrue(suggestion.get("cut").get("reason").asText().contains("teuerste Karte dieser Rolle"));
    }

    @Test
    void cardSuggestionsMsgOhneNoteOhneShareOhneSchnittFuerQuelleDb() {
        // source "db" (Ruecknahme ohne EDHREC): share fehlt (Spec §2), ebenso cut ohne Schnittkandidat.
        Suggestions.Item item = new Suggestions.Item("Rampant Growth", "ramp", "{2}{G}", 3,
                null, false, "c:rampant_growth", "Search your library for a basic land card.", null);
        Suggestions.Result result = new Suggestions.Result("db", "Ohne EDHREC-Daten: Vorschläge nur aus der Kartendatenbank.", List.of(item));

        Messages.CardSuggestionsMsg msg = new Messages.CardSuggestionsMsg("Mein Deck", result);
        JsonNode json = Json.parse(Json.toJson(msg));
        assertEquals("db", json.get("source").asText());
        assertEquals("Ohne EDHREC-Daten: Vorschläge nur aus der Kartendatenbank.", json.get("note").asText());
        JsonNode suggestion = json.get("suggestions").get(0);
        assertFalse(suggestion.has("share"), "share fehlt bei source db");
        assertFalse(suggestion.has("cut"), "cut fehlt ohne Schnittkandidat");
    }

    @Test
    void leereRollenLiefertLeereSuggestionsMitHinweis() {
        // Spec §2: leere roles -> leere suggestions mit festem Hinweistext (siehe Suggestions.of).
        Suggestions.Result result = new Suggestions.Result("edhrec", "Keine Lücke gefunden.", List.of());
        Messages.CardSuggestionsMsg msg = new Messages.CardSuggestionsMsg("Mein Deck", result);
        JsonNode json = Json.parse(Json.toJson(msg));
        assertEquals("Keine Lücke gefunden.", json.get("note").asText());
        assertTrue(json.get("suggestions").isEmpty());
    }
}
