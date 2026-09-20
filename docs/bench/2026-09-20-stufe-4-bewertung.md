# Stufe 4: Bewertung in Schritten (`GameStateEvaluator`, `SpellAbilityPicker`) – Bench

Datum: 2026-09-20/21. Ahoy-Mateys-Spiegel, Sitz A = `sim:Default`, Sitz B = `std:Default`, 40 Seeds ab 1,
`--timeout 10 --game-timeout 10`, Kindprozess je Spiel, Zugdeckel 200. Jeder Schritt der Spec (§5) wurde einzeln
auf den jeweils besten Stand gesetzt, gebaut, die Jars eingefroren und gemessen; behalten nach der Spec-Regel
**Siegquote ≥ Referenz oder Untergrenze ≥ Untergrenze der Referenz**, sonst per `git revert` zurückgenommen.
Referenz ist nicht mehr die 28 : 12 aus Stufe 3 (Fork c3910fdb), sondern eine **frische Basis auf dem
aktuellen Fork-Stand aa851d40** (Mulligan-Heuristik auf beiden Sitzen, Phage/Goad/Meld-Fixes) – zufällig
ebenfalls 28 : 12, aber mit 6 anderen Seed-Siegern als in Stufe 3.

Rohberichte: `2026-09-20-stufe-4-roh-bewertung-{basis,s1-land,s2-hand,s3-permanent,s4-main2}.md`.

| Schritt | Fork-Stand | A : B | Siegquote A | 95-%-Intervall | Ø Züge | Ø Dauer | gegen | Entscheidung |
|---|---|---|---|---|---|---|---|---|
| Basis (Stand vor Stufe-4-Bewertung) | aa851d40 | 28 : 12 | 70,0 % | 54,6–81,9 % | 14,8 | 54 s | – | Referenz |
| **1 Landgewicht** (`evalManaBase`: 60 je Quelle bis min(Deckmax, 6), darüber 5; Pips 40 statt 100) | 7c4fbee2 | **30 : 10** | **75,0 %** | **59,8–85,8 %** | 15,0 | 53 s | Basis 70,0 / 54,6 | **behalten** |
| 2 Handkarte (4 + 8·behaltbar statt 1 + 4, Gegner gleich) – auf Schritt 1 | 620b238b, revertiert 9f06a677 | 29 : 11 | 72,5 % | 57,2–83,9 % | 14,7 | 50 s | Schritt 1 75,0 / 59,8 | zurückgenommen |
| 3 Nicht-Kreaturen (40 + 35·CMC statt 50 + 30·CMC) – auf Schritt 1 | 191a45df, revertiert ebc387ca | 29 : 11 | 72,5 % | 57,2–83,9 % | 14,8 | 45 s | Schritt 1 75,0 / 59,8 | zurückgenommen |
| 4 `availableValue`-Regel nur in eigener Main 1, wenn nach dem Kampf noch bezahlbar – auf Schritt 1 | b7605d49, revertiert 3b808037 | 28 : 12 | 70,0 % | 54,6–81,9 % | 15,3 | 46 s | Schritt 1 75,0 / 59,8 | zurückgenommen |

Keine Abstürze, keine Unentschieden, keine Sim-Fehler in allen Läufen; Nichtstun-Spiele (≥ 5 Länder, ≤ 2 Zauber)
A 0 / B 0–1. Sitz A war in allen Läufen 22-mal Startspieler (gleiche Seeds).

Zusätzlich gemessen, weil die Schritte parallel vorgebaut wurden (nicht entscheidungsrelevant, alle 40 Seeds,
gleiche Parameter): 1+2+3 → 29 : 11, 1+2+3+4 → 29 : 11, 1+3+4 → 29 : 11. Jede Kombination der Schritte 2–4 auf
Schritt 1 landet bei 29 : 11 oder 28 : 12.

## Was die Seeds sagen

Die Sim-Sitze sind trotz Zeitbudget weitgehend seed-reproduzierbar: Schritt 2 und Schritt 1 stimmen in 38 von 40
Spielen in Sieger **und** Zugzahl überein, Schritt 3 in 37, Schritt 4 in 33; die Basis gegen Schritt 1 in 27.
Die Differenzen zwischen den Läufen stecken also in wenigen Spielen, nicht im Rauschen von 40:

- **Schritt 1 gewinnt zwei Seeds hinzu und verliert keinen:** Seed 20 (Basis B in 12 Zügen → A in 19) und
  Seed 38 (B in 14 → A in 15). Das ist das ehrlichste Maß für den Effekt: +2 Spiele auf 40 bei sonst gleichem
  Verlauf.
- **Schritte 2, 3 und 4 verlieren jeweils genau Seed 20 wieder** (B in 16 bzw. 14 Zügen); Schritt 4 zusätzlich
  Seed 23 (A in 21 → B in 23). Keine der drei Änderungen gewinnt ein Spiel, das Schritt 1 verliert.
- Seed 20 ist damit das einzige Spiel, das an der Bewertungsgewichtung hängt – ein Spiel, das zwischen zwei
  Sitzen kippt, je nachdem, wie die Sim-KI Land gegen Zauber abwägt.

## Einordnung

- **Landgewicht (behalten):** Vor dem Schritt zählte ein Land 100 in `evalManaBase` plus ~103 in
  `evaluateLand`, so viel wie eine 3-Mana-Kreatur; der Picker bevorzugte dann Land oder Mana-Artefakt vor
  Board-Entwicklung. 60 je Quelle bis zur gedeckelten Kurve (min(Deckmaximum, 6) – Commander-Decks haben ein
  bis zwei sehr teure Karten, sonst wäre das achte Land so viel wert wie das vierte) und 40 je Farb-Pip. +5 Punkte,
  Untergrenze erstmals bei 59,8 %. Bei 40 Seeds nicht von Zufall trennbar, aber die Regel der Spec ist erfüllt und
  der Seed-Vergleich zeigt einen reinen Zugewinn (+2 / −0).
- **Handkarte (zurückgenommen):** 12 statt 5 je Karte (4 + 8, Gegnerkarten gleich statt pauschal 4). 29 : 11 –
  über der Basis, aber unter Schritt 1; die Regel vergleicht gegen den besten Stand. Im Seed-Vergleich neutral
  minus Seed 20. Die Hypothese „Kartenvorteil zählt nicht" ist damit nicht widerlegt, nur in diesem Spiegel
  ohne Wirkung: Ahoy ist ein Aggro-Precon, das seine Hand ohnehin leert.
- **Nicht-Kreaturen (zurückgenommen):** 40 + 35·CMC statt 50 + 30·CMC verschiebt ein 5-Mana-Permanent von 200
  auf 215, ein 2-Mana-Permanent von 110 auf 110 – die Änderung ist zu klein, um Entscheidungen zu drehen, und
  sie tut es auch nicht (37 von 40 Spielen identisch mit Schritt 1). Ø Dauer 45 s statt 53 s, wohl weil das
  Basis-Bild mehr Züge mit Trivialentscheidungen enthielt; nicht als Effekt gewertet.
- **`availableValue`-Regel (zurückgenommen):** Die Regel hält einen Zug, der den verfügbaren Wert nicht hebt
  (unbeschworene Kreatur vor Main 2), jetzt nur noch in der eigenen Main 1 und nur, wenn eine bis Main 2
  vorgespielte Spielkopie den Zauber noch bezahlen kann (`GameCopier.makeCopy(MAIN2)` + `canPayManaCost`;
  Fall Breeches aus `2026-09-20-stufe-2-nichtstun.md`). Sie greift selten: in einem Debug-Spiel (Seed 4) einmal in
  179 Top-Level-Entscheidungen, wie damals 2 in 150. Es gibt also nichts zu gewinnen, und der Lauf zeigt es:
  28 : 12, Seed 20 und 23 verloren. Die Umsetzung bleibt im Fork-Verlauf (Commit + Revert) für den Fall, dass die
  Kampfsimulation später Trigger-Kosten abbildet.
- **Ø Züge / Dauer:** 14,7–15,3 Züge, 45–54 s je Spiel; die Bewertungsänderungen kosten keine Laufzeit (alle
  Läufe hatten 4–5 parallele Benches auf 32 Kernen, die Dauern sind untereinander vergleichbar, nicht mit Stufe 3).

## Einschränkungen

- 40 Seeds ≈ ±15 Punkte Intervallbreite. Der Unterschied 70 → 75 % sind zwei Spiele; die Spec-Regel ist eine
  Entscheidungsregel, kein Signifikanztest. Die drei zurückgenommenen Schritte sind **neutral**, nicht schädlich:
  jede Kombination liegt innerhalb eines Spiels von Schritt 1 und über der Basis.
- Ein Deckpaar (Aggro-Spiegel), ein Profil, Standard-KI als Gegner. Kartenvorteil (Schritt 2) und teure
  Permanents (Schritt 3) kommen in Ahoy kaum vor; gegen ein Kontroll-Deck (Abzan/Estrid) könnten die Schritte
  anders ausgehen – nicht gemessen.
- Seed-Reproduzierbarkeit ist hoch, aber nicht vollständig (Basis vs. Schritt 1: 13 Spiele mit anderem
  Verlauf, davon nur 2 mit anderem Sieger); unter Zeitbudget kann derselbe Seed mit anderer Simulationstiefe
  anders enden.
- Die Basis ist zufällig zahlengleich mit der Stufe-3-Referenz (28 : 12); die Sieger unterscheiden sich in 6
  Seeds (Mulligan auf beiden Sitzen). Vergleiche mit Stufe-3-Läufen nur über die Quote, nicht seedweise.

## Nächste Hebel

1. Die zurückgenommenen Schritte gegen ein Kontroll-Deck messen (Abzan oder Estrid), wo Handkarten und
   Nicht-Kreaturen-Permanents tatsächlich Entscheidungen tragen.
2. Kampfsimulation mit Trigger-Kosten in `simulateUpcomingCombatThisTurn`, dann Schritt 4 wieder aufnehmen.
3. Mehr Seeds (80–120) für Schritt 1 gegen Basis, bevor die Zahl 75 % irgendwo zitiert wird.
