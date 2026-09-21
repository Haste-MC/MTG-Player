# Archidekt-Konto – Deckliste eines Kontos im Deck-Panel holen und aktualisieren – Design

Stand: 2026-09-21. Kevins Wunsch: „kann man den resync auch mit dem account namen machen? ein klick, alle decks
die man will ziehen?" Gewählt: volle Reiter-Variante.

## 1. Archidekt-API

`GET https://archidekt.com/api/decks/v3/?ownerUsername=<name>&pageSize=50[&page=n]` (geprüft am 2026-09-21;
`owner=`/`ownerexact` filtern **nicht**). Antwort `{ count, next, results: [{ id, name, updatedAt, deckFormat,
featured, customFeatured, private, unlisted, owner: { username } }] }`. Nur `deckFormat == 3` (Commander) wird
angeboten; private Decks liefert die API ohne Login nicht. Bis zu 10 Seiten folgen (`next`), mit ≥ 1 s Abstand je
Anfrage wie beim Einzelabruf. Deck-Detail (`/api/decks/<id>/`) enthält `updatedAt` – der Stand, der beim Import
gespeichert wird.

## 2. Bridge

- `DeckStore`: zweites Tag `archidekt-updated:<updatedAt>` (ISO-String aus dem Deck-Detail) neben `archidekt:<id>`;
  `DeckInfo` bekommt `archidektUpdated: String` (null ohne Tag). `DeckSource.resolve`/`resync` setzen beide Tags
  (`Archidekt.Result` erhält `updatedAt`).
- `Archidekt.listDecks(String username)` → `List<Entry(long id, String name, String updatedAt, String art)]` (`art` =
  `customFeatured` wenn nicht leer, sonst `featured`; nur Commander-Decks); Fehler (HTTP, JSON) →
  `IllegalArgumentException("Archidekt: …")`; leere Liste ist kein Fehler (Konto unbekannt oder ohne öffentliche
  Decks → der Client zeigt „keine öffentlichen Commander-Decks gefunden").
- Nachrichten (Client → Bridge): `{ type: "archidektList", username }` → Antwort `{ type: "archidektDecks", username,
  decks: [{ id, name, updatedAt, art }] }` oder `error`.
  `{ type: "archidektImport", ids: [long] }` → Hintergrund-Task: je Id nacheinander `DeckSource.importArchidekt(id)`
  (vorhandenes Deck mit diesem Tag → Resync unter dem gespeicherten Namen, sonst Neuimport mit Archidekt-Namen),
  je Deck vorher `{ type: "archidektProgress", done, total, current: <Name>, errors: [..] }`, nach jedem Deck eine
  `lobby`-Nachricht (die Liste wächst sichtbar), zum Schluss `archidektProgress` mit `done == total` und `current:
  null`; Fehler je Deck werden in `errors` gesammelt (`"<Name>: <Grund>"`), der Lauf läuft weiter. Ein zweiter
  `archidektImport` während ein Lauf läuft → `error` „Import läuft noch".
- `DeckStore.byArchidektId(id)` liefert den gespeicherten Namen oder null (über `infos()`).
- Tests: `ArchidektTest` (`listDecks` aus Fixture `archidekt-list-1.json`, Filter Commander, `art`-Fallback,
  Paginierung über `next` mit Fake-Fetcher), `DeckStoreTest` (zweites Tag), `DeckSourceTest`
  (`importArchidekt`: neu vs. vorhanden), `BridgeEndToEndTest` (`archidektList` Fehlerpfad, `archidektImport` mit
  leerer Liste → sofort `archidektProgress 0/0`).

## 3. Web

- Protokoll: Inbound `ArchidektDecks`, `ArchidektProgress`; Outbound `archidektList`, `archidektImport`;
  `DeckInfo.archidektUpdated`.
- Store: `archidekt: { username?: string; decks?: ArchidektEntry[]; loading: boolean; progress?: Progress }`;
  Reducer für die beiden Inbound-Typen; `error` beendet `loading`. Benutzername in `localStorage`
  `mtg.lobby.archidekt` (Modul `archidektSettings.ts`, Muster `aiSettings.ts`).
- `DeckPicker`: vierter Reiter **„Archidekt"**. Kopf: Eingabefeld *Benutzername* + Knopf „Decks laden" (Enter
  ebenso); Liste bleibt im Store, solange die Lobby offen ist. Raster wie „Eigene Decks", Bild = `art` (direkt von
  Archidekt, `<img>` mit `referrerpolicy="no-referrer"`), je Kachel ein Häkchen (Checkbox, Klick auf die Kachel
  toggelt) und eine Marke: **neu** (keine eigene Deck-Info mit dieser Id), **aktuell** (`archidektUpdated ===
  updatedAt`), **geändert** (sonst). Vorbelegung der Häkchen: alle *geändert*. Fußzeile: „Ausgewählte holen (n)"
  (sendet `archidektImport` mit den Ids) und „Alle aktualisieren" (Ids aller *aktuell*/*geändert*); während eines
  Laufs beide gesperrt und eine Fortschrittszeile „3/7 · Koma, World-Eater …", danach „7/7 fertig" bzw. die
  gesammelten Fehler (rot, mehrzeilig). Nach dem Lauf wählt ein Klick auf „Eigene Decks" wie gewohnt.
- Reine Logik `archidektPlan.ts`: `classify(entry, ownDecks) → "neu" | "aktuell" | "geändert"`, `defaultSelection(entries,
  ownDecks) → ids`, `updateAllIds(entries, ownDecks) → ids`; getestet.
- Screenshots (Fixture `lobby.json` + neue Inbound-Fixtures `archidekt-decks.json`, `archidekt-progress.json`
  per `mtgApply`; `shot-lobby.mjs` `--tab=archidekt`): Liste mit Marken, laufender Import, Fehlerzeile.

## 4. Nicht in dieser Runde

Login/private Decks, Löschen lokaler Decks, die auf Archidekt verschwunden sind, automatischer Sync beim Start.
