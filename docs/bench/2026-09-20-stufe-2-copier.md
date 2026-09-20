# Bench: GameCopier robust machen (Stufe 2, Schritt 2)

Datum: 2026-09-20. Fork `mtg-player` @ 2990f760 (Monarch, `<Nothing>`-Platzhalter) plus 6c84aec7 (Debug-Flag),
noch **ohne** den Treasure-Fix 450c26e5 – der wurde erst durch den Absturz in diesem Lauf gefunden. Bridge
`feature/ki-paket-2`. Rohdaten daneben in diesem Ordner: `2026-09-20-stufe-2-copier-roh-ahoy-spiegel.md`
(Spiegel, wie Schritt 1) und `2026-09-20-stufe-2-copier-roh-abzan-vs-estrid.md` (Deckvergleich gegen
Planeswalker-Commander).

## Aufbau

Zwei Läufe, `--timeout 10 --game-timeout 10`, Zugdeckel 200, ein JVM-Kindprozess je Spiel:

1. **Spiegel** Ahoy Mateys [LCC] gegen Ahoy Mateys [LCC], Sitz A = `sim:Default`, Sitz B = `standard:Default`,
   Seeds 1–40 (identischer Aufbau wie Schritt 1 und `sim-vs-standard.md`).
2. **Deckvergleich** Abzan Armor [TDC] (Sitz A, `sim:Default`) gegen Adaptive Enchantment [C18] (Sitz B,
   `standard:Default`, Planeswalker-Commander Estrid), Seeds 1–20 – das Matchup, das im Ausgangslauf wegen
   `Couldn't map <Nothing>` fast jedes Spiel abbrach.

```bash
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default \
  --deck-a 'precon:Ahoy Mateys [LCC] [2023]' --deck-b 'precon:Ahoy Mateys [LCC] [2023]' --seed 1 \
  --timeout 10 --game-timeout 10"
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 20 --a sim:Default --b std:Default \
  --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1 \
  --timeout 10 --game-timeout 10"
```

## Ergebnis

| Lauf | A gewinnt | B gewinnt | Abstürze | Siegquote A | 95-%-Intervall (Wilson) | Ø Dauer |
|---|---|---|---|---|---|---|
| Spiegel Ahoy (Seeds 1–40) | 25 | 14 | 1 (Treasure, Seed 30) | 64,1 % | 48,4–77,3 % | 64 s |
| Abzan(sim) vs Estrid(std) (Seeds 1–20) | 19 | 1 | 0 | 95,0 % | 76,4–99,1 % | 65 s |
| Ausgangslauf Spiegel (`sim-vs-standard.md`) | 22 | 7 | 2 (Monarch) + 9 Timeouts | 75,9 % | 57,9–87,8 % | – |

**0 Abstürze an Monarch oder `<Nothing>`.** Der Deckvergleich gegen Estrid, der im Ausgangslauf praktisch in
jedem Spiel mit `Couldn't map <Nothing>` abbrach, lief alle 20 Seeds ohne einen einzigen Absturz durch. Im
Spiegel blieb genau ein Absturz übrig: `Couldn't map Treasure Token` bei Seed 30, seitdem behoben (450c26e5,
`SimCopierTest` Fall c – siehe `docs/forge-fork.md`). Mit diesem Fix stehen für den Spiegel 40 von 40 Seeds ohne
Absturz und ohne Timeout zur Verfügung.

**Siegquote 64,1 % [48,4–77,3 %] gegenüber 75,9 % [57,9–87,8 %] vorher – andere Grundgesamtheit, nicht
dieselbe Messung.** Im Ausgangslauf waren die komplexen Boards (viele Zauber, lange Partien) mehrheitlich
Timeouts oder Abstürze und fielen aus der Wertung; die 29 gewerteten Spiele waren eine Auswahl der einfacheren
Partien. Jetzt zählen diese Boards mit – und dort ist die Kandidatensuche der Simulation durch das
10-Sekunden-Budget gestutzt, bevor sie alle Optionen bewertet hat. Die beiden 95-%-Intervalle überlappen fast
vollständig (57,9–78,5 % gemeinsame Fläche); 40 Seeds trennen das nicht. Die Entscheidungsregel aus der Spec
(Untergrenze des Intervalls > 50 %) ist mit 48,4 % **nicht** erfüllt.

**Ehrliche Schlagzeile: 0 Timeouts, 0 Abstürze, Siegquote statistisch unverändert.** Stufe 2 macht die
Simulations-KI benutzbar (keine hängenden Entscheidungen, keine Commander-Abstürze mehr), verändert aber die
gemessene Spielstärke gegenüber dem Ausgangslauf nicht nachweisbar.

## Einschränkungen

- Sim-Sitze sind mit Zeitbudget nicht mehr seed-reproduzierbar: die Deadline hängt an der Wanduhr. Bench-Läufe
  mit `--timeout` sind ab hier als Verteilungen zu lesen (mehrere Seeds, Konfidenzintervall), nicht als
  wiederholbares Einzelspiel-Ergebnis.
- Nur zwei Deckpaare gemessen (Ahoy-Spiegel, Abzan gegen Estrid); die 95 % beim Estrid-Matchup sind, wie im
  Ausgangslauf schon beim Abzan-gegen-Ahoy-Vergleich festgestellt, wahrscheinlich größtenteils Deckstärke statt
  Sim-Vorteil (keine `standard`-vs-`standard`-Baseline für dieses Paar gemessen).
- Kopiertreue-Frage aus `docs/forge-fork.md` (`CHECK_GAME_COPY_SCORE`, nur mit `-ea`) bleibt offen; sie betrifft
  die Bewertungsgenauigkeit einzelner Simulationsschritte, nicht die hier gemessenen Abstürze.

## Nächster Hebel

Die Siegquote hebt sich mit gleichem Budget vermutlich nicht ohne Änderung an der Kandidatenreihenfolge
(`SpellAbilityPicker.chooseSpellAbilityToPlayImpl` bewertet in `getAvailableCards`-Reihenfolge und lässt den
Rest nach Ablauf des Budgets unbewertet) – siehe `2026-09-20-stufe-2-nichtstun.md`, Vorschlag 1. Ergänzend
lohnt ein Vergleichslauf mit `--timeout 30` gegen `--timeout 10`, um zu sehen, ob mehr Budget die Quote allein
schon näher an den Ausgangslauf bringt.
