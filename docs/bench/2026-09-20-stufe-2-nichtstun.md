# Stufe 2, Schritt 3: Nichtstun-Schwäche der Simulations-KI – Befund

Datum: 2026-09-20. Fork `mtg-player` @ 2990f760 (Zeitbudget + GameCopier) plus Debug-Ausgabe des Pickers
(`-Dforge.ai.sim.debug=true`, siehe unten). Bridge `feature/ki-paket-2`.

## Frage

In den verlorenen Spiegel-Spielen (`docs/bench/2026-09-20-sim-vs-standard.md`) spielte die Sim-KI 1–5 Zauber bei
3–6 Ländern und warf Karten ab. Verdacht der Spec: `chooseSpellAbilityToPlayImpl` hält Züge zurück
(`availableValue`-Regel vor Main 2), der Plan-Mechanismus sieht sie in Main 2 nicht mehr vor, oder die Bewertung
schätzt Handkarten zu hoch.

## Ergebnis in einem Satz

**Es gibt keine Nichtstun-Schwäche der Bewertung: in 6 der 7 nachgespielten Niederlagen hatte die Sim-KI
schlicht keinen bezahlbaren Zauber auf der Hand (Land- oder Farbmangel), und die Standard-KI verliert dieselben
Seeds mit denselben Händen genauso.**

## Vorgehen

Alle sieben Niederlagen der Sim-KI aus dem ersten Messlauf mit `--bench-one` in-process nachgespielt (gleicher
Index/Seed → gleiche Sitzreihenfolge, gleiche Starthände), Picker-Debug an: je Entscheidung Phase, Hand, jeder
bewertete Kandidat mit Wert (`value (available x)`), gewählter Zug, Plan. Die sieben Spiele endeten alle wieder
als Niederlage (Seed 40 ist Zug für Zug identisch mit dem Messlauf: 5 Länder, 1 Zauber, 1 Abwurf, 16 Züge).

```bash
cd bridge && java -Xmx4g -Dforge.ai.sim.debug=true -cp "target/classes:$(cat cp.txt)" mtgplayer.Main \
  --bench-one 20 --a sim:Default --b std:Default \
  --deck-a 'precon:Ahoy Mateys [LCC] [2023]' --deck-b 'precon:Ahoy Mateys [LCC] [2023]' --seed 20 --timeout 10
```

Kontrolle: dieselben vier Seeds mit `--a std:Default` (Standard-KI auf Sitz A). Mulligan-Entscheidung und
Bibliotheksreihenfolge hängen nicht vom KI-Modus ab, also spielt A dort mit derselben Hand.

## Die sieben Niederlagen

Sitz A = Sim-KI. „Länder" = Zug des 1./2./3./… Landabwurfs. „ohne Kandidat" = Main-Phasen-Entscheidungen, in
denen `getCandidateSpellsAndAbilities` keinen einzigen spielbaren Zauber fand (Ausgabe `BEST: N/A … TIME: 0`).

| Seed (Lauf) | Hand | Länder (Zug) | Zauber der Sim-KI | Main-Entsch. ohne Kandidat | Ursache |
|---|---|---|---|---|---|
| 40 (Spiegel T2, Spiel 21) | 7 (Mountain, Desolate Lighthouse + 5 blaue Karten) | 1, 3, 9, 13, 15 | 1 (Kari Zev, Zug 3) | 16 von 21 | **Farbmangel**: bis Zug 15 keine blaue Quelle (R, C, B, B, dann Thriving Bluff getappt), alle 7–8 Handkarten brauchen {U} |
| 22 (Spiegel T2, Spiel 3) | Mulligan auf 6 | 2, 4, 6, 8 (getappt), 12 | 5 (ab Zug 6) | 14 von 22 | **Landmangel**: 2 Länder bis Zug 5, 3 offene Mana bis Zug 9; danach wird alles gespielt |
| 3 (Spiegel T1, Spiel 3) | 7 | 2, 4, 6 (+ Bauble Zug 10) | 5 | 13 von 21 | **Landmangel**: 3 Länder Zug 6–12; einziger echter Halte-Fall, siehe unten (Breeches, Zug 14) |
| 4 (Spiegel T1, Spiel 4) | 7 (Thriving Moor, Mountain, Unclaimed Territory) | 1, 3, 5 – dann keins mehr | 3 (Sailor, Feed the Swarm, Shared Animosity) | 15 von 22 | **Landmangel**: 3 Länder Zug 5–15; alle Handkarten kosten ≥ 4 (Skeleton Crew 3B, Locker 3B, Don Andres 1UBR, Breeches 3R, Banner 5, Bident 2UU) – jeder bezahlbare Zauber wurde gespielt |
| 11 (Spiegel T1, Spiel 11) | Mulligan auf 6 | 1, 3, 5, 7, 9, 13 | 4 + Crew-Aktivierung | 14 von 21 | **Farbmangel**: 5 Länder = B/R, R, C, C, R – Admiral Beckett Brass (1UBR) und Amphin Mutineer (3UU) unbezahlbar bis Sunken Hollow in Zug 13 |
| 6 (Abzan vs Ahoy, Spiel 6) | 7 (Fortified Village, Access Tunnel, Plains) | 1, 3, 5 – dann keins mehr | 0 | 8 von 17 | **Landmangel**: 3 Mana (G/W, W, C) Zug 5–13; Hand: Titan 4GG, Arasta 2GG, Architect 3G, Faithmender 3W, Muse 3GG, Shadrix 3WB, Caryatid 1GG, Expel 3WW – nur Tower Defense (1G) spielbar und ohne Kreaturen zu Recht gehalten (228 < 229) |
| 32 (Spiegel T2, Spiel 13) | 7 (Sol Ring) | jeder eigene Zug | 13 | 6 von 24 | **kein Nichtstun** – Spielqualität: Prismari Command auf die eigene Hand (wirft Zara + Locker ab), Windfall wirft 2 eigene Karten, Commander stirbt dreimal; dazu Budget-Abbrüche (s. u.) |

Kontrolle mit Standard-KI auf Sitz A (gleiche Seeds): Seed 40 → B gewinnt in 16 Zügen, A spielt dieselben 5 Länder
und denselben einen Zauber; Seed 4 → B gewinnt (A 4 Zauber, Island erst Zug 13); Seed 6 (Abzan) → B gewinnt, A
spielt **null** Zauber; Seed 22 → B gewinnt in 13 Zügen (schneller als gegen die Sim-KI), A 3 Zauber. Die
Niederlagen liegen am Seed, nicht am Modus.

## Hypothesen und was die Logs sagen

| Hypothese (Spec) | Befund |
|---|---|
| `availableValue`-Regel hält Kreaturen vor Main 2 zurück und Main 2 spielt sie nicht | Regel greift in 150 Main-Phasen-Entscheidungen der 7 Spiele **zweimal**: Seed 4 Zug 7 (Spectral Sailor, 613 vs. verfügbar 462 < 467 → in Main 2 gespielt, wie vorgesehen) und Seed 3 Zug 14 (Breeches 3/3, Wert −393, verfügbar −601 < −596 → gehalten; im Kampf zahlt der Angriffs-Trigger von Fathom Fleet Captain optional {2} – Standard-KI-Logik, nicht der Picker –, in Main 2 sind nur noch 2 Mana offen, statt Breeches wird Francisco (2 Mana) gespielt). Ein Fall in sieben Spielen, mit Ersatzzug – nicht die Ursache. |
| Plan-Mechanismus verliert Züge | Ein `Failed to continue planned action` (Seed 22 Zug 12: der Plan wollte den von Hostage Taker exilierten Zauber direkt nach dem Landabwurf; die Referenz war nach dem Land nicht mehr auffindbar). Der Picker plant neu und spielt denselben Zauber in derselben Main-Phase. Kein Verlust. |
| Bewertung hält Handkarten für wertvoller als Spielen | Nein: eine Handkarte zählt 5 Punkte (`cards + 4 * fullValueCards`), eine gespielte 2/2 ~ 130. In allen Entscheidungen mit Kandidaten wurde der Kandidat mit dem höchsten Wert gespielt, wenn er `availableValue` hob. |
| Budget-Abbruch (nach Schritt 1) zu früh | Nur in Seed 32 relevant (5 von 12 Entscheidungen mit Kandidaten abgebrochen, 20 Kandidaten nie bewertet; Zug 12: 1 von 6 in 10 s, Distant Melody mit Zieliteration) und Seed 22 Zug 14 (4 von 7). In den Mangel-Spielen gab es nichts abzubrechen (`TIME: 0`). Kein Nichtstun, aber ein Qualitätsverlust bei vollen Boards. |

## Warum es nach Nichtstun aussah

Die Niederlagen sind eine Auswahl nach Ergebnis. Mit Mana gewinnt die Sim-KI den Spiegel zu ~76 %; verloren
gehen die Spiele, in denen sie keins hat – und die dauern Sekunden statt Minuten, weil es nichts zu simulieren
gibt (7–34 s im Messlauf gegen 40–540 s bei Siegen). Die geteilte Mulligan-Heuristik (`ComputerUtil.scoreHand`)
behält jede 7-Karten-Hand mit ≥ 2 Ländern, zählt Nutzländer wie Desolate Lighthouse, Access Tunnel und
Unclaimed Territory als volle Länder und prüft keine Farben – vier der sieben Hände hatten 2–3 Länder mit
1–2 farblosen darunter.

## Entscheidung

**Kein Fix an `SpellAbilityPicker`/`GameStateEvaluator`, kein 40-Seed-Bench** – es gibt nichts zu messen: der
Vergleichsbench würde dieselben Mangel-Seeds verlieren. Forge-Änderung nur die Debug-Ausgabe.

Vorschläge für die Bewertungsarbeit (Stufe 3), nach Nutzen sortiert:

1. **Kandidatenreihenfolge unter Budget** (`SpellAbilityPicker.chooseSpellAbilityToPlayImpl`): heute die
   Reihenfolge von `getAvailableCards` (Hand, dann Spielfeld, dann Kommandozone); nach Ablauf bleiben die
   späten Kandidaten unbewertet – in Seed 32 der Commander und ziel-lose Zauber, während ein Zauber mit
   Zieliteration die 10 s allein verbrauchte. Ziel-lose Kandidaten und Kreaturen zuerst, ziel-iterierende
   danach, und je Kandidat einen Anteil des Budgets statt „erster nimmt alles". ~30 Zeilen, messbar im
   Spiegel-Bench mit `--timeout 5`.
2. **Mulligan mit Farben** (`ComputerUtil.scoreHand`, betrifft beide Modi): Hände mit ≥ 2 Ländern, deren
   Farben keine der Handkarten bezahlbar machen (Seed 40), oder mit Nutzländern ohne Farbe als einziger
   zweiter Quelle, unter die Behalte-Schwelle drücken. Kein Sim-Thema, aber der größte Hebel gegen diese
   Niederlagen.
3. **`availableValue`-Regel und optionale Trigger-Kosten** (Seed 3 Zug 14): die Regel nimmt an, dass das Mana in
   Main 2 noch da ist. Kleine Abhilfe ohne Neudesign: die Regel in Main 1 nur anwenden, wenn der gehaltene Zug
   nach dem Kampf noch bezahlbar wäre – prüfbar nur über eine Kampfsimulation mit Triggerzahlung, also erst
   sinnvoll, wenn die Bewertung Kampftrigger simuliert. Bis dahin: kein Handlungsbedarf (1 Fall / 150).

## Debug-Ausgabe (Fork)

`-Dforge.ai.sim.debug=true` schaltet in `SpellAbilityPicker` die Ausgabe der Top-Level-Entscheidungen ein
(`printOutput`, vorher nur per Quelltext-Kommentar erreichbar): Phase und Zug, Hand, jeder bewertete Kandidat
mit `Score`, `BEST`, Plan mit Schritten, `Planned decision`, `Failed to continue planned action`. Rekursive
Picker innerhalb einer Simulation bleiben stumm. `GameSimulator.debugPrint` (Zustands-Diffs) wird von
`GameSimulator` selbst bei jeder Konstruktion auf `false` gesetzt und ist damit als Schalter unbrauchbar –
unverändert gelassen. Bench-Aufruf: `java -Dforge.ai.sim.debug=true … mtgplayer.Main --bench-one …` (Ausgabe
auf stdout des Kindprozesses; für einen ganzen `--bench`-Lauf über `--in-process` oder die Kindprozess-Logs).
