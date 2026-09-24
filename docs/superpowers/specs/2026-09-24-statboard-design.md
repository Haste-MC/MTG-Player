# Runde A – Statistik-Board, Deckanalyse, Auffälligkeiten, Serien-Automatik – Design

Stand: 2026-09-24. Kevins Punkte: Board zu schmal und ohne Bilder, Kennzahlen ohne Erklärung, „ich sehe nicht,
wo man Schwächen rauslesen soll", 1vs1 und Gruppe müssen getrennt sein – und: „die Serie mit den Best-of-
Einstellungen sollte dafür sorgen, dass einfach das nächste Spiel gestartet wird, sonst kann ich mir das sparen".

Runde B (auf `main`) liefert die Daten: ~25 Vorfall-Kennzahlen je Sitz plus Zeitachse, Formatversion 2; ältere
Partien sind `v: 1` und haben die Felder **nie gezählt** – die Auswertung muss sie als „keine Daten" behandeln.

## 1. Serien-Automatik (unabhängig, kommt zuerst)

Heute ist „Best of N" nur eine Anzeige: nach jedem Spiel steht derselbe Knopf wie sonst. Neu:

- Ist eine Serie aktiv (`bestOf > 0`) und **noch nicht entschieden**, zeigt der Spielende-Dialog statt „Nochmal
  spielen" einen Countdown: „Spiel 2 von 3 startet in 5 …" mit den Knöpfen **Jetzt starten** und **Serie beenden**.
  Läuft der Countdown ab, schickt der Client `lastStart` von selbst. Der Countdown läuft nur, solange der Dialog
  offen ist; „Serie beenden" bricht ihn ab und lässt den Dialog mit „Zur Lobby" stehen.
- Ist die Serie **entschieden**, kein Countdown: „<Name> gewinnt die Serie (2:1)" mit „Neue Serie" und „Zur Lobby".
- Wurde die Partie abgebrochen (`counted: false`, Grund „abgebrochen") oder ist die Bridge nicht bereit, startet
  nichts automatisch.
- Während der Partie steht der Stand in der Phasenleiste bzw. der Zuschauer-Fußzeile: „Serie 1:0 · Best of 3".

## 2. Datenmenge (Vorbedingung für das Board)

Ein Datensatz ist mit Zeitachse ~6 KB (Extremfall 19 KB); die Bridge schickt heute bei jeder Änderung die ganze
Liste. Deshalb:

- `matches` enthält die Datensätze **ohne** `timeline` (Feld weggelassen, nicht leer – der Client erkennt daran
  „nicht geladen") und höchstens die **letzten 300**; die Nachricht trägt zusätzlich `total`.
- Neu: `{ "type": "matchDetail", "id": "…" }` → `{ "type": "match", "match": { … vollständig inkl. timeline … } }`,
  unbekannte Id → `error`. Das Board lädt ein Detail erst, wenn eine Partie aufgeklappt wird.

## 3. Deckanalyse (Bridge, `mtgplayer.decks.DeckAnalysis`)

Rein aus der Kartendatenbank, ohne Partien – wirkt sofort für jedes Deck. Angefordert mit
`{ "type": "analyzeDeck", "deck": "<Name>" }` (Precon- oder Speichername), Antwort
`{ "type": "deckAnalysis", "deck": "…", "analysis": { … } }`, unbekanntes Deck → `error`.

```json
{ "cards": 100, "lands": 36, "basics": 22, "avgCmc": 3.4,
  "curve": { "0": 2, "1": 7, "2": 13, "3": 15, "4": 11, "5": 7, "6": 5, "7+": 4 },
  "sources": { "W": 0, "U": 12, "B": 15, "R": 0, "G": 0, "any": 6 },
  "identity": ["U","B"],
  "categories": { "ramp": 8, "draw": 9, "removal": 6, "wipes": 1, "counters": 3,
                  "flyerDefense": 2, "wipeProtection": 0, "recursion": 4, "tutors": 2 },
  "unclassified": 31 }
```

Einordnung über Typen und Orakeltext (eine Regel-Liste in einer Datei, jede Regel mit Test):
- `ramp`: Nicht-Land, das Mana erzeugt (`Add {`) oder ein Land sucht (`Search your library for a … land`)
- `draw`: „draw a card"/„draw N cards" für den Beherrscher (nicht „each opponent draws")
- `removal`: „Destroy target"/„Exile target" (Kreatur/bleibende Karte) oder „deals N damage to target creature"
- `wipes`: „Destroy all"/„Exile all"/„each creature" in Verbindung mit Zerstören/Opfern
- `counters`: „Counter target spell"
- `flyerDefense`: eigene Karten mit Flying oder Reach, oder „can block creatures with flying"
- `wipeProtection`: Indestructible, Hexproof für eigene Karten, „regenerate", „return … from your graveyard to
  the battlefield" (Wiederaufbau)
- `recursion`: Karten aus dem Friedhof zurück; `tutors`: „Search your library for a card"
- Eine Karte darf in mehreren Kategorien zählen; `unclassified` = keine Kategorie getroffen.
Farbquellen: Länder mit Basistyp oder „Add {X}" je Farbe, „any color" separat.

Grenzen ehrlich benennen: Das ist Textmustererkennung, keine Semantik. Die Zahlen sind Größenordnungen, keine
Wahrheit – das Board schreibt das dazu.

## 4. Board-Umbau (Web)

Volle Fensterbreite statt 1100-px-Karte. Aufbau:

- **Kopf**: Titel, „Zur Lobby", Format-Chips `Alle · 1 vs 1 · Pod (3+)` mit Partienzahl je Chip, Schalter
  „Erklärungen" (Standard an).
- **Links**: Deckliste mit Commander-Bild (56×78, Muster `DeckPicker`), Commander-Name groß, Deckname klein,
  Bilanz als „3 S · 2 N", Partienzahl; Decks mit nur Zugdeckel-Partien wie bisher.
- **Rechts**, in Blöcken mit Überschrift; jede Kachel trägt Wert, Label und (wenn Erklärungen an) einen Satz:
  1. **Bilanz** – Partien, Siegquote + Intervall, Ø Züge, Ø Dauer, im Pod zusätzlich Ø Platz und Ausscheide-Zug.
  2. **Mana & Start** – Ø Länder bis eigenem Zug 3/5, Mana-Screw-Quote (≤ 2 Länder im 3. eigenen Zug),
     Flut-Quote (≥ 6 Länder, ≤ 2 Zauber), verpasste Landabgaben, Mulligan-Quote, Ø Länder der Starthand.
  3. **Tempo & Commander** – Zug des 1. Commanders, Commander-Steuer, Zauber je Partie und je Zug.
  4. **Kampf & Überleben** – Schaden gemacht/genommen, Anteil Flieger/Trampel am erlittenen Kampfschaden,
     Angriffe je Partie, gegen dich deklarierte Angreifer, Ø Leben am Ende, Handkarten beim Ausscheiden.
  5. **Interaktion & Verluste** – gekonterte eigene Zauber (Quote), eigene Counterspells und Entfernung,
     bleibende Karten verloren, größte Massenentfernung, Partien mit Massenentfernung.
  6. **Deck** (aus §3) – Länder, Ø CMC, Kurve als kleines Balkenbild, Farbquellen, Kategorien als Chips.
  7. **Gegner** – mit Commander-Bild, Partien, Bilanz.
- **Partienliste** unten wie bisher (Datum, Quelle, Gegner, Ergebnis, Züge, Dauer, Bedenkzeit, gewertet,
  Papierkorb); eine Zeile lässt sich aufklappen und lädt dann per `matchDetail` die Zeitachse als kleine Kurve
  (Länder/Kreaturen/Leben je Zug).
- Kennzahlen aus Runde B werden nur über Partien mit `v >= 2` gerechnet; fehlen solche, zeigt die Kachel „–"
  und die Erklärzeile „wird erst ab neuen Partien erfasst".

## 5. Auffälligkeiten

Eigener Block über den Kacheln, Regeln als reine Funktion (`web/src/findings.ts`), Eingabe: Zusammenfassung des
gewählten Decks im gewählten Format + Deckanalyse. Jede Regel liefert Stufe (`info | warn`), Titel, Satz mit
**Zahl und Stichprobe**, und – wenn vorhanden – den Bezug zur Deckanalyse. Erst ab **5 gewerteten Partien** im
gewählten Format; darunter steht „zu wenige Partien für Aussagen (n von 5)".

Startsatz an Regeln (Schwellen als Konstanten, jede mit Test):
- Mana-Screw > 30 % → warn, mit Landzahl und Ø CMC aus der Deckanalyse
- Flut-Quote > 25 % → warn
- Mulligan-Quote > 40 % → warn, mit Ø Ländern der Starthand
- Massenentfernung in > 30 % der Partien **und** `wipeProtection == 0` → warn
- Fliegeranteil am erlittenen Kampfschaden > 40 % **und** `flyerDefense <= 2` → warn
- Gekonterte Zauber > 15 % **und** `counters == 0` → info
- Zugdeckel-Quote > 20 % → warn („kein verlässlicher Abschluss")
- Ausscheide-Zug im Pod deutlich vor Partieende (Ø `eliminatedTurn / turns < 0,7`) → info
- Kein Befund → „Nichts Auffälliges bei n Partien."
Jede Zeile endet mit „Kartenvorschläge folgen" – das ist Stück 2.

## 6. Nachweise

- Bridge: `DeckAnalysisTest` (je Kategorie ein bekanntes Deck bzw. eine bekannte Karte, Farbquellen, Kurve),
  `BridgeEndToEndTest` (`analyzeDeck` unbekannt → `error`; `matchDetail` unbekannt → `error`; `matches` ohne
  `timeline` und mit `total`).
- Web: `matchStats.test.ts` (Formatfilter, v2-Kennzahlen, „keine Daten" bei v1), `findings.test.ts` (jede Regel
  über oder unter der Schwelle, Mindeststichprobe), `store.test.ts` (Serien-Countdown, `deckAnalysis`, `match`).
- Screenshots: Board mit Bildern und allen Blöcken (1600×900 und 1280×720), Auffälligkeiten mit Befunden,
  aufgeklappte Partie mit Kurve, Spielende-Dialog mit Countdown.

## Nicht in dieser Runde

Kartenvorschläge (Stück 2), Verlaufsgrafiken über mehrere Partien, Export, Deck-Vergleich.
