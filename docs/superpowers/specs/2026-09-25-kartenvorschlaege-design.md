# Kartenvorschläge – Stück 2 der Statistik – Design

Stand: 2026-09-25. Kevins Auftrag: zu den Schwächen eines Decks auch sagen, welche Karten sie schließen –
„Schwächen + Deckcheck" als Quelle, Kandidaten „Mischung aus Forge und EDHREC", Schnittvorschläge „ja, mit
Begründung".

Die Vorarbeit steht: `DeckAnalysis` ordnet jede Karte mit neun Regeln ein (`ramp`, `draw`, `removal`,
`wipes`, `counters`, `flyerDefense`, `wipeProtection`, `recursion`, `tutors`), `findings.ts` benennt die
Auffälligkeiten einer Deck-Statistik, `DeckStore` kennt das Bracket jedes Decks. Der Kern dieses Stücks:
**dieselben neun Regeln ordnen auch die Vorschläge ein** – Lücke und Vorschlag sprechen dieselbe Sprache.

## 1. Wer was weiß

Die Schwächenlogik bleibt im Client (`findings.ts`), das Kartenwissen in der Bridge. Der Client sagt, welche
Rollen er braucht; die Bridge antwortet mit Karten.

- Jede `Finding` bekommt ein optionales Feld `role`:
  Mana-Screw → `ramp`, Landflut → `draw`, Massenentfernung → `wipeProtection`, Flieger → `flyerDefense`,
  Gekonterte Zauber → `counters`, Früh raus → `removal`.
  „Viele Mulligans" und „Zugdeckel" bekommen **keine** Rolle: beide zeigen auf Kurve und Landzahl, nicht auf
  eine Kartenrolle, und ein Vorschlag wäre geraten.
- Der **Deckcheck** kommt ohne Partien aus: `findings.ts` bekommt Richtwerte je Rolle
  (`ramp` 10, `draw` 8, `removal` 8, `wipes` 2, `flyerDefense` 4, `wipeProtection` 2; `counters`,
  `recursion` und `tutors` haben keinen Richtwert, weil sie Deckabsicht sind und kein Mangel).
  `roleGaps(analysis, findings)` liefert die Rollen aus beiden Quellen, ohne Dopplung, höchstens drei –
  zuerst die aus Auffälligkeiten, dann die größten Unterschreitungen der Richtwerte.

## 2. Protokoll

```json
{ "type": "suggestCards", "deck": "<Name>", "roles": ["wipeProtection", "flyerDefense"] }
```
→
```json
{ "type": "cardSuggestions", "deck": "<Name>", "source": "edhrec",
  "note": "EDHREC nicht erreichbar – Vorschläge nur aus der Kartendatenbank.",
  "suggestions": [
    { "name": "Heroic Intervention", "role": "wipeProtection", "manaCost": "{1}{G}", "cmc": 2,
      "share": 0.68, "gameChanger": false, "imageKey": "c:heroic_intervention", "text": "…",
      "cut": { "name": "Overrun", "reason": "spielt in vergleichbaren Decks fast niemand (4 %) und ist mit {2}{G}{G}{G} die teuerste Karte dieser Rolle" } }
  ] }
```

- `source`: `"edhrec"` (angereichert) oder `"db"` (nur Kartendatenbank). `note` steht nur dabei, wenn es
  etwas zu sagen gibt (EDHREC nicht erreichbar, Commander dort unbekannt, Bracket-Hinweis).
- `share` fehlt bei `source: "db"`; `cut` fehlt, wenn sich kein Schnittkandidat findet.
- Leere `roles` → leere `suggestions` mit `note: "Keine Lücke gefunden."`. Unbekanntes Deck → `error`
  „Kartenvorschläge <name>: unbekanntes Deck", wie bei `analyzeDeck`.
- Die Antwort kommt aus einem Hintergrund-Task (`runBackgroundTask("suggest-cards", …)`), wie `analyzeDeck`:
  der Abruf darf den UI-Thread nicht anhalten.

## 3. EDHREC als Quelle (`mtgplayer.decks.Edhrec`)

- Adresse: `https://json.edhrec.com/pages/commanders/<slug>.json`.
- Slug: Commandernamen alphabetisch sortieren; je Name alles ab `//` abschneiden (doppelseitige Karten
  laufen bei EDHREC unter ihrer Vorderseite), kleinschreiben, **Apostrophe (`'` und `’`) ersatzlos
  entfernen**, dann alles außer `a-z0-9` durch `-` ersetzen, Mehrfach-`-` zusammenziehen, Ränder trimmen,
  mit `-` verbinden. Gegen EDHREC geprüft (200 gegen 403):
  „Titania, Protector of Argoth" → `titania-protector-of-argoth`, „Ms. Bumbleflower" → `ms-bumbleflower`,
  „Yuriko, the Tiger's Shadow" → `yuriko-the-tigers-shadow` (nicht `yuriko-the-tiger-s-shadow`, das ist
  403), „K'rrik, Son of Yawgmoth" → `krrik-son-of-yawgmoth`,
  „Esika, God of the Tree // The Prismatic Bridge" → `esika-god-of-the-tree`,
  Thrasios + Tymna → `thrasios-triton-hero-tymna-the-weaver`.
  Ein Apostroph ist damit der einzige Sonderfall, der **nicht** zum Trennzeichen wird – jeder Commander mit
  Apostroph im Namen wäre sonst dauerhaft ohne Vorschläge.
- Gelesen wird `container.json_dict.cardlists`: je Eintrag `header` und `cardviews` mit `name`, `num_decks`,
  `potential_decks`. `share = num_decks / potential_decks`. Die Liste mit `header == "Game Changers"`
  markiert die entsprechenden Karten.
- Zwischenspeicher: `~/.mtg-player/edhrec/<slug>.json`, gültig **sieben Tage**. Abgelaufen und nicht
  erreichbar → der alte Stand wird trotzdem benutzt (mit `note`), denn alte Daten sind besser als keine.
- Zeitgrenzen wie bei Archidekt: 10 s Verbindung, 20 s Antwort, derselbe `User-Agent`. Jeder Fehler
  (Zeitüberschreitung, 404, kaputtes JSON) führt zum Rückfall aus §6, nie zu einem Abbruch.
- Nach außen geht nur der Commandername – keine Deckliste, keine Partiedaten. Das steht auch im UI.

## 4. Kandidaten und Rangfolge (`mtgplayer.decks.Suggestions`)

Für jede angefragte Rolle:

1. Kandidaten sind die EDHREC-Karten des Commanders, die Forges Kartendatenbank kennt (unbekannte fallen
   weg – ohne Kartenregeln ließen sie sich weder einordnen noch anzeigen).
2. Weg damit, wenn: schon im Deck, außerhalb der Farbidentität des Commanders, ein Land (Länder sind eine
   eigene Baustelle und verdrängen sonst jede Rolle), oder die Rolle nicht getroffen (dieselbe
   `DeckAnalysis.Rule` wie bei der Deckanalyse).
3. Rangfolge: `share` absteigend, bei Gleichstand niedrigere Manakosten zuerst, dann Name – damit die
   Antwort auf dieselbe Frage zweimal gleich aussieht.
4. Höchstens **fünf** Vorschläge je Rolle und **fünfzehn** insgesamt, Rollen in der Reihenfolge der Anfrage.

## 5. Bracket

Das Bracket des Decks steht als Tag im `DeckStore` (`bracket:`).

- Bracket 1–2: Game Changer fallen raus.
- Bracket 3: Game Changer kommen mit, `gameChanger: true`, und die Antwort trägt
  `note: "Game Changer sind in Bracket 3 auf drei Karten begrenzt."`
- Bracket 4–5 oder unbekannt: keine Einschränkung, `gameChanger` bleibt als Kennzeichen erhalten.

## 6. Ohne EDHREC

Ist EDHREC nicht erreichbar und liegt auch kein alter Stand vor, kommen die Kandidaten aus Forges
Kartendatenbank: alle Karten, die die Rollenregel treffen, in der Farbidentität, Commander-legal, nicht im
Deck. Rangfolge: Manakosten aufsteigend, dann Name. `source: "db"`, `share` fehlt, `note` sagt den Grund.
Das ist grob, aber es funktioniert offline und der Screen bleibt benutzbar.

## 7. Schnittkandidaten

Zu jedem Vorschlag ein Kandidat aus dem eigenen Deck:

1. Karten des Decks mit derselben Rolle, ohne Länder und ohne Commander.
2. Rangfolge: zuerst die, die EDHREC für diesen Commander gar nicht führt, dann aufsteigender `share`, dann
   absteigende Manakosten, dann Name. Ohne EDHREC-Daten nur nach Manakosten absteigend, dann Name.
3. Findet sich in der Rolle nichts, kommen die unklassifizierten Karten des Decks dran (dieselbe Rangfolge).
4. Findet sich auch dort nichts, fehlt `cut` – lieber kein Vorschlag als ein schlechter.
5. Jeder Kandidat wird höchstens **einmal** vorgeschlagen; ist er vergeben, rückt der nächste nach.
6. Der Grund nennt nur, was wirklich geprüft wurde, und die Formulierung hängt an der tatsächlichen Zahl:
   - kein EDHREC-Eintrag → „führt EDHREC für diesen Commander gar nicht"
   - Anteil unter 10 % → „spielt in vergleichbaren Decks fast niemand (4 %)"
   - Anteil ab 10 % → „hat mit 85 % den niedrigsten Anteil der Karten dieser Rolle in deinem Deck"
     (ein hoher Anteil darf **nie** als „spielt fast niemand" erscheinen – die Karte ist dann nur der
     schwächste Kandidat eines starken Feldes, und genau das muss dastehen)
   - teuerste der Kandidatenliste → „ist mit {2}{G}{G}{G} die teuerste Karte dieser Rolle" (nur dann)
   - aus dem Rückfall auf unklassifizierte Karten → „trifft keine der neun Rollen"
   Treffen zwei Gründe zu, werden sie mit „und" verbunden.

**Nicht enthalten:** „hat in deinen Partien nie gewirkt". Die Partiedatensätze halten keine Kartennamen,
nur Zahlen je Sitz; das bräuchte eine eigene Aufzeichnung gewirkter Karten und ist damit ein eigenes Stück.

## 8. Anzeige (`web/src/components/Suggestions.tsx`)

Im Statistik-Board unter den Auffälligkeiten, je Deck:

- Überschrift „Kartenvorschläge" mit Zähler; darunter je Rolle eine Gruppe mit dem Rollennamen auf Deutsch
  (`ramp` → „Ramp", `draw` → „Kartenzug", `removal` → „Entfernung", `wipes` → „Massenentfernung",
  `counters` → „Gegenzauber", `flyerDefense` → „Antwort auf Flieger",
  `wipeProtection` → „Schutz vor Massenentfernung", `recursion` → „Rückholer", `tutors` → „Suchkarten").
- Je Vorschlag eine Zeile: Kartenbild (`CardImage` wie sonst), Name, Manakosten, „68 % der vergleichbaren
  Decks spielen sie", Marke „Game Changer" wo zutreffend, und rechts der Schnittkandidat mit Begründung.
- Kopfzeile der Gruppe nennt die Herkunft: „EDHREC, Stand 23.09." bzw. „nur Kartendatenbank" plus `note`.
- Ein Satz zur Datenweitergabe steht sichtbar am Abschnitt: an EDHREC geht nur der Commandername.
- Geladen wird erst auf Klick („Vorschläge laden"), nicht beim Öffnen des Boards – ein Netzabruf ohne
  Zutun beim bloßen Durchsehen der Statistik wäre eine Überraschung.
- Die bestehende Platzhalterzeile `SUGGESTIONS_HINT` („Kartenvorschläge folgen.") entfällt.

## 9. Tests

- **Java**: Slug-Bildung (Komma, Punkt, Apostroph, Partner, Sonderzeichen); Auswertung einer echten,
  im Test mitgelieferten EDHREC-Antwort (gekürzt, als Datei unter `src/test/resources`) zu `share` und
  Game-Changer-Marke; Kandidatenauswahl (schon im Deck, falsche Farbe, Land, falsche Rolle fallen raus);
  Rangfolge und Obergrenzen; Bracket 2 ohne Game Changer, Bracket 3 mit Hinweis; Schnittkandidat samt
  Begründung und „jeder nur einmal"; Rückfall ohne EDHREC. Kein Test darf ins Netz gehen: der Abruf wird
  über eine eingesetzte Quelle (Funktion, die den JSON-Text liefert) ersetzt.
- **TypeScript**: Rollenzuordnung der Auffälligkeiten, `roleGaps` (Auffälligkeit vor Richtwert, keine
  Dopplung, höchstens drei), Darstellung der Herkunft, Verhalten ohne Antwort und bei `error`.
- **Screenshot**: Statistik-Board mit geladenen Vorschlägen (Fixture) über `scripts/shot-stats.mjs`.

## 10. Nicht enthalten

- Automatisches Ändern der Deckliste – die App schlägt vor, Kevin entscheidet.
- Preise und Verfügbarkeit.
- Landvorschläge (Manabasis) – eigene Baustelle mit eigenen Regeln.
- Vorschläge, die auf Kartennamen aus eigenen Partien beruhen (siehe §7).
