# Übernehmen und Löschen – Design

Stand: 2026-09-21. Kevins Wunsch: „übernehmen bauen und ein löschen button für decks". Beschluss: Marke
„übernehmen" statt Knopf/Dialog, nicht vorausgewählt; Löschen mit Zwei-Klick-Bestätigung in der Kachel.

## 1. Übernehmen

- **Bridge** (`DeckSource.importArchidekt`, Neu-Pfad): kollidiert der Archidekt-Name (oder seine `.dck`-Datei) mit
  einem gespeicherten Deck **ohne** `archidekt:`-Tag, wird unter genau diesem gespeicherten Namen gespeichert
  (Übernahme: Inhalt ersetzt, beide Tags gesetzt). Trägt das kollidierende Deck ein anderes `archidekt:`-Tag,
  bleibt es bei `"<Name> (<Id>)"`. Kein neues Protokoll.
- **Web** (`archidektPlan.classify`): vierter Zustand `"übernehmen"` – kein eigenes Deck mit der Id, aber ein
  eigenes Deck mit gleichem Namen (exakt) und ohne `archidekt`. `defaultSelection` bleibt „geändert" (übernehmen
  ist nicht vorausgewählt), `updateAllIds` bleibt aktuell+geändert. Marke `.state-badge.uebernehmen` (orange-soft),
  Tooltip „ersetzt das gleichnamige lokale Deck". Fortschrittstext unverändert.

## 2. Löschen

- **Protokoll:** Client `{ type: "deleteDeck", name }` → Bridge löscht die Datei des gespeicherten Decks (nur
  `DeckStore`, nie Precons) und schickt eine neue `lobby`; unbekannter Name oder Dateifehler → `error`
  „Löschen <name>: …".
- **Bridge:** `DeckStore.delete(name)` (Datei über `readDisplayName`-Suche wie `load`, `Files.delete`), `Bridge.handle`
  `case "deleteDeck"` auf dem Hintergrund-Task-Muster mit `RuntimeException`-Fang. Ein laufender Import ist kein
  Hindernis (löscht schlimmstenfalls ein Deck, das der Lauf gleich neu schreibt).
- **Web:** in „Eigene Decks" je Kachel ein `button.delete` („🗑", `aria-label="Deck löschen"`) neben Resync. Erster
  Klick → der Knopf wird zu „Wirklich löschen?" (Klasse `confirm`, 4 s oder Klick außerhalb setzt zurück, Escape
  schließt weiterhin das Panel); zweiter Klick sendet `deleteDeck` und zeigt „löscht …" bis zur nächsten
  `lobby`/Fehlerzeile (Statuszeile wie beim Resync: „Deck gelöscht." bzw. rot der Fehler). Ist das Deck in einem Sitz
  gewählt, leert `Lobby.tsx` den Pick: `dropMissing` läuft bei jeder Änderung von `decks`/`precons`, nicht nur beim
  ersten Laden (Text-/Archidekt-Picks bleiben unberührt).

## 3. Nachweise

Bridge: `DeckSourceTest` (Übernahme: gleichnamiges Deck ohne Tag wird ersetzt und getaggt; mit anderem Tag →
Suffix), `DeckStoreTest.delete` (Datei weg, unbekannt → IAE), `BridgeEndToEndTest` `deleteDeck` unbekannt → `error`,
`BridgeArchidektImportTest` unverändert grün. Web: `archidektPlan.test.ts` (übernehmen, nicht vorausgewählt),
`lobbyPicks.test.ts` unverändert, Screenshot „Eigene Decks" mit Lösch-Knopf im Bestätigungszustand
(`shot-lobby.mjs --tab=saved --click=".deck-card .delete"` – neues Flag klickt einen Selektor nach dem Öffnen) und
„Archidekt" mit Marke „übernehmen" (Fixture `archidekt-decks.json` bekommt ein Deck, dessen Name einem eigenen
Deck ohne Tag entspricht – `lobby.json` bekommt dafür ein drittes eigenes Deck ohne `archidekt`).

## Nicht in dieser Runde

Umbenennen, Papierkorb/Undo, Löschen von Precons.
