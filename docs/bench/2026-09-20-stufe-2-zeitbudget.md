# Bench: Zeitbudget für die Voll-Simulation (Stufe 2, Schritt 1)

Datum: 2026-09-20. Fork `mtg-player` @ ffe45981/7b88bf01 (Zeitbudget je Entscheidung, zweite Planrunde nur mit
Restbudget), ohne die `GameCopier`-Fixes aus Schritt 2. Bridge `feature/ki-paket-2`. Rohdaten liegen daneben in
diesem Ordner: `2026-09-20-stufe-2-zeitbudget-roh-ahoy-spiegel.md`.

## Aufbau

Gleicher Lauf wie in `2026-09-20-sim-vs-standard.md`: Spiegel-Match Ahoy Mateys [LCC] gegen Ahoy Mateys [LCC],
Sitz A = `sim:Default`, Sitz B = `standard:Default`, Seeds 1–40, ein JVM-Kindprozess je Spiel, Zugdeckel 200.
Diesmal mit `--timeout 10 --game-timeout 10` (vorher `--game-timeout 10/20` ohne `--timeout`, siehe Ausgangslauf).

```bash
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default \
  --deck-a 'precon:Ahoy Mateys [LCC] [2023]' --deck-b 'precon:Ahoy Mateys [LCC] [2023]' --seed 1 \
  --timeout 10 --game-timeout 10"
```

## Ergebnis

| | A (sim) | B (std) | Abstürze | Siegquote A | 95-%-Intervall (Wilson) | Ø Dauer |
|---|---|---|---|---|---|---|
| **Dieser Lauf (Zeitbudget, ohne Copier-Fixes)** | 22 | 12 | 6 (5× Monarch, 1× Treasure) | 64,7 % | 47,9–78,5 % | 58 s |
| Ausgangslauf (`sim-vs-standard.md`, kein Zeitbudget) | 22 | 7 | 2 (Monarch) + 9 Timeouts | 75,9 % | 57,9–87,8 % | – |

**0 Timeouts (vorher 9 von 40).** Das Zeitbudget hält jede einzelne Simulations-Entscheidung unter Kontrolle;
keine Partie hängt mehr über die `--game-timeout`-Grenze hinaus. Durchschnittliche Spieldauer ~60 s statt der
85–180 s, die einzelne Partien im Ausgangslauf ohne Budget brauchten.

Die 6 Abstürze in diesem Lauf sind der noch offene `GameCopier`-Fehler (Monarch, Treasure) – Schritt 1 ändert
nur das Zeitbudget, nicht den Kopierer. Die Siegquote 64,7 % [47,9–78,5 %] ist auf dieser Datengrundlage nicht
belastbar: mit Abstürzen als Ausfällen ist die Stichprobe (34 entschiedene Spiele) eine andere Auswahl als der
Ausgangslauf, und beide Intervalle überlappen fast vollständig. Einordnung der Siegquote und der Vergleich zum
Ausgangslauf mit vollem Fix-Stand stehen in `2026-09-20-stufe-2-copier.md`.

## Einschränkungen

- Gemessen ist nur ein Deckpaar (`Default` gegen `Default`, Ahoy-Mateys-Spiegel), wie im Ausgangslauf.
- Die Simulationsseite ist mit Zeitbudget nicht mehr seed-reproduzierbar: die Deadline hängt an der Wanduhr,
  nicht am Seed, also kann ein Wiederholungslauf auf einer anderen Maschine oder unter Last andere Kandidaten
  abbrechen. Bench-Vergleiche mit Zeitbudget sind ab hier als Verteilungen zu lesen, nicht als Einzelspiel-Repro.
- Die 6 Abstürze dieses Laufs sind in `2026-09-20-stufe-2-copier-roh-ahoy-spiegel.md` und
  `2026-09-20-stufe-2-copier-roh-abzan-vs-estrid.md` adressiert.
