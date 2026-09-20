# Stufe 3: Kandidatenreihenfolge unter Budget und 30-s-Budget – Bench

Datum: 2026-09-20. Ahoy-Mateys-Spiegel, Sitz A = `sim:Default`, Sitz B = `standard:Default`, 40 Seeds ab 1,
Kindprozess je Spiel, Zugdeckel 200. Referenz: Abschlusslauf Stufe 2 (`2026-09-20-stufe-2-final-roh-ahoy-spiegel.md`).
Rohberichte: `2026-09-20-stufe-3-roh-kandidaten-sortiert.md`, `2026-09-20-stufe-3-roh-budget30.md`.

| Lauf | Fork-Stand | Budget | A : B | Siegquote A | 95-%-Intervall | Ausfälle | Ø Dauer |
|---|---|---|---|---|---|---|---|
| Referenz Stufe 2 | 94931591 | 10 s | 26 : 14 | 65,0 % | 49,5–77,9 % | 0 | 58 s |
| **Kandidaten sortiert** | c3910fdb | 10 s | **28 : 12** | **70,0 %** | **54,6–81,9 %** | 0 | 62 s |
| 30-s-Budget (unsortiert) | 94931591 | 30 s | 23 : 16 | 59,0 % | 43,4–72,9 % | 1 Absturz (Seed 31, NPE `SimulationController`, seitdem behoben) | 105 s |

## Einordnung

- **Sortierung (Fork-Commit c3910fdb):** `SpellAbilityPicker.getCandidateSpellsAndAbilities` sortiert die Kandidaten
  wie die Regel-KI (`ComputerUtilAbility.saEvaluator`, Kreaturen zuerst). Unter Zeitbudget werden so die
  aussichtsreichen Züge zuerst simuliert statt „was `getAvailableCards` zuerst liefert" (Länder, Cantrips).
  +5 Punkte gegenüber der Referenz; bei 40 Seeds nicht von Zufall trennbar (die Intervalle überlappen stark),
  aber die Untergrenze liegt erstmals über 50 % – die Entscheidungsregel der Spec ist erfüllt. Kein
  Laufzeitkostenunterschied.
- **30 s statt 10 s Budget:** schwächer (59 %), fast doppelte Spieldauer. Mehr Rechenzeit macht die Simulation
  nicht besser – ein Hinweis, dass die Grenze in der Bewertung liegt (tiefere/mehr Simulationen bewerten mit
  derselben Funktion) und nicht in der Suchtiefe. Für Live-Spiele bleibt 10 s (oder weniger) die Empfehlung.
- Der Absturz im 30-s-Lauf war der zweite Vertreter der Familie „Simulator bricht früh ab, `advance()` poppt
  Ebenen, die nie gepusht wurden" (nach `RuntimeException("-1")`); beide sind im Fork behoben (`ChoicePoint.open`,
  siehe `docs/forge-fork.md`). Ob weitere Vertreter existieren, zeigt nur Laufzeit – der Bench meldet sie jetzt
  mit Stacktrace in `game-<i>.log`.

## Einschränkungen

Ein Deckpaar (Aggro-Spiegel), ein Profil, 40 Seeds je Lauf; Sim-Sitze sind mit Zeitbudget nicht
seed-reproduzierbar. Aussagen gelten als Richtung, nicht als exakte Prozentwerte. Für einen belastbaren
Unterschied zwischen 65 % und 70 % bräuchte es ~300 Spiele.

## Nächste Hebel

1. Bewertung (`GameStateEvaluator`): Hand-vs-Board-Gewichtung, Mana-Entwicklung, Kommandozonen-Zugriff – der
   30-s-Befund sagt, dass hier der Engpass liegt.
2. Mulligan-Heuristik: jede 2-Land-Hand wird behalten, Farben ungeprüft (Nichtstun-Befund) – betrifft beide KIs,
   im Spiegel neutral, im Spiel gegen Menschen aber ein echter Nachteil.
