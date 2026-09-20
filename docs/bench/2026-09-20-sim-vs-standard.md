# Bench: Simulations-KI gegen Standard-KI (Stufe 1, erster Messlauf)

Datum: 2026-09-20. Forge 2.0.14 unverändert, Bridge `main` @ eafcd3f. Rohberichte der einzelnen Läufe liegen
daneben in diesem Ordner (Markdown je Lauf, Einzelspiele mit Seed, Startspieler, Grund, Zügen, Dauer).

## Aufbau

- **Hauptmessung: Spiegel-Match** Ahoy Mateys [LCC] gegen Ahoy Mateys [LCC], Sitz A = `sim:Default`
  (Forges `USE_FULL_SIMULATION`), Sitz B = `standard:Default`. Gleiches Deck auf beiden Seiten, Startspieler
  wechselt (Forge lost zusätzlich, der tatsächliche Erste steht in den Tabellen). Seeds 1–40, ein
  JVM-Kindprozess je Spiel, Zugdeckel 200. Teil 1 (Seeds 1–19) mit 20 min Spiel-Timeout, Teil 2 (Seeds 20–40)
  mit 10 min – alle regulär beendeten Spiele lagen unter 10 min.
- **Nebenläufe (Deckvergleich, nicht symmetrisch):** Abzan Armor [TDC] (Sitz A) gegen Ahoy Mateys – einmal
  `sim` vs `standard` (16 Spiele, abgebrochen), einmal `standard` vs `standard` als Baseline (40 Spiele).

## Ergebnis

| Lauf | A gewinnt | B gewinnt | Siegquote A | 95-%-Intervall (Wilson) | Ausfälle |
|---|---|---|---|---|---|
| **Spiegel Ahoy, sim vs std (Seeds 1–40)** | **22** | **7** | **75,9 %** | **57,9–87,8 %** | 11 (2 Abstürze, 9 Timeouts) |
| Abzan(sim) vs Ahoy(std), 16 Spiele | 12 | 3 | 80,0 % | 54,8–93,0 % | 1 Absturz |
| Abzan(std) vs Ahoy(std), Baseline | 28 | 12 | 70,0 % | 54,6–81,9 % | 0 |

**Entscheidungsregel aus der Spec** (Untergrenze des Intervalls > 50 %): im Spiegel **erfüllt** (57,9 %).
Die Simulations-KI gewinnt das Spiegel-Match in rund drei von vier ausgetragenen Spielen.

Der Deckvergleich Abzan gegen Ahoy zeigt, warum der Spiegel nötig war: Abzan Armor gewinnt schon mit
Standard-KI auf beiden Seiten 70 % – die 80 % mit Sim-KI sind davon nicht zu unterscheiden (Intervalle
überlappen fast vollständig).

## Einschränkungen – und die sind gewichtig

1. **Ausfallquote 28 %.** 11 von 40 Spiegel-Spielen wurden nicht ausgetragen:
   - 9× **Timeout**: die Sim-KI hing in *einer* Entscheidung länger als 10 bzw. 20 Minuten (Forges
     Zauberwahl-Simulation kennt kein Zeitlimit; `--timeout`/`AI_TIMEOUT` greift dort nicht). Im
     Abzan-Spiegel (erster Versuch) hing schon Spiel 1 bei Zug 13 über 30 Minuten – große Boards mit vielen
     Token sind für die Simulation praktisch nicht spielbar.
   - 2× **Absturz** `GameCopier: Couldn't map The Monarch` – der Spielkopierer der Simulation kennt den
     Monarch-Effekt nicht. Gegen Planeswalker-Commander (z. B. Estrid, Adaptive Enchantment) stürzt er
     ebenso ab (`Couldn't map <Nothing>`: Forge legt beim Tod eines angegriffenen Planeswalkers eine
     Fake-Karte in den Kampf), deshalb wurde das Matchup gewechselt.
   Die Ausfälle sind nicht zufällig verteilt (Timeouts = komplexe Boards), die 29 gewerteten Spiele sind also
   eine Auswahl der *einfacheren* Spiele. Die 76 % gelten für Spiele, die die Simulation überhaupt zu Ende
   bringt.
2. **Verlorene Spiele der Sim-KI sehen nach Nichtstun aus.** In den 7 Niederlagen hat A auffallend wenig
   gespielt (1–5 Zauber bei 3–6 Ländern, Karten abgeworfen), B dagegen 5–7 Zauber. Im Abzan-Lauf gab es ein
   Spiel, in dem A mit 3 Ländern sieben Züge lang nichts gespielt hat. Das ist keine Fehlermeldung im Log
   (0 Sim-Fehler), sondern eine Bewertungsschwäche: die Simulation hält Karten, die sie spielen könnte.
3. **Ein Deckpaar, ein Profil.** Gemessen ist nur `Default` gegen `Default` mit einem Aggro-Precon. Ob das
   Bild bei Cautious/Reckless oder bei kontrolllastigen Decks hält, ist offen. Hybrid (nur Zauberwahl
   simuliert) wurde noch nicht gemessen.
4. **Speicher:** ohne den Bridge-Fix (`AiCache.clear()` je Spielkopie, siehe
   `.superpowers/sdd/sim-oom-investigation.md`) läuft die Simulation nach wenigen Spielen in
   `OutOfMemoryError`; mit Fix bleibt sie unter 2 GB.

## Was das für Stufe 2 heißt

Die Simulations-KI ist **spielstärker, aber nicht benutzbar**: 28 % der Spiele enden in Timeout oder
Absturz, und einzelne Entscheidungen dauern Minuten. Bevor ein Fork am `GameStateEvaluator` (Bewertung)
ansetzt, sind die Vorbedingungen wichtiger – und alle drei sind Fork-Themen:

1. **Zeitbudget für die Simulation** (`SpellAbilityPicker`: Abbruch mit bestem bisherigen Zug nach n Sekunden) –
   ohne das ist der Modus im Live-Spiel unbrauchbar.
2. **`GameCopier` robust machen** (Monarch, `<Nothing>`-Fake-Karte, „SA not found") – sonst stürzt ein
   Commander-Spiel mit Planeswalker-Commander oder Monarch zuverlässig ab.
3. **Nichtstun-Schwäche** untersuchen (warum hält die Simulation spielbare Karten?) – vermutlich Bewertung,
   also erst dann `GameStateEvaluator`.

Der Bench ist dafür das Messinstrument: `--bench --games 40 --a sim:Default --b std:Default
--deck-a 'precon:Ahoy Mateys [LCC] [2023]' --deck-b 'precon:Ahoy Mateys [LCC] [2023]' --seed 1 --game-timeout 10`
reproduziert diesen Lauf (Seeds gelten je Spiel; Timeouts der Standard-KI hängen von der Wanduhr ab).

**Für Kevins Spiele heute:** „Hybrid" ist die spielbare Zwischenstufe; „Simulation" nur mit Geduld und
ohne Planeswalker-Commander/Monarch am Tisch.
