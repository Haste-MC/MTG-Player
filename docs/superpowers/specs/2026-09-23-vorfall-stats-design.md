# Runde B – Vorfall-Kennzahlen je Partie – Design

Stand: 2026-09-23. Kevins Auftrag: „erst B, gib so viele stats wie du finden kannst". Ziel ist die Datengrundlage
für Schwächen-Aussagen der Art „wird oft gekontert", „stirbt an Massenentfernung ohne Schutz", „keine Antwort auf
Flieger". Quelle sind **typisierte Forge-Ereignisse**, kein Log-Text.

## 1. Was Forge liefert (geprüft)

| Ereignis | Nutzlast | wofür |
|---|---|---|
| `GameEventSpellAbilityCast` | `SpellAbility` (Engine-Objekt) | gewirkte Zauber, eigene Counterspells (`ApiType.Counter`), Ziele |
| `GameEventSpellResolved` | `SpellAbilityView`, `hasFizzled` | aufgelöst / verpufft |
| `GameEventSpellRemovedFromStack` | `SpellAbilityView` | gekontert bzw. vom Stack entfernt |
| `GameEventCardChangeZone` | `CardView`, `ZoneView from`, `ZoneView to` (je mit Spieler + Zonentyp) | Verluste, Ziehen, Abwerfen, Millen, Spielsteine |
| `GameEventPlayerDamaged` | `target`, `source` (CardView), `amount`, `combat`, `infect` | Schadensquellen inkl. Schlüsselwörtern |
| `GameEventCardDamaged` | `card`, `source`, `amount`, `DamageType` | Kampfschaden auf Kreaturen |
| `GameEventAttackersDeclared` | Angreifer je Verteidiger | Angriffsdruck |
| `GameEventBlockersDeclared` | Blocker je Angreifer | Blockverhalten |
| `GameEventPlayerLivesChanged` | alt/neu | Lebensgewinn |
| `GameEventCardCounters`, `GameEventPlayerPoisoned`, `GameEventMulligan`, `GameEventTurnBegan/Phase` | — | Marken, Gift, Mulligans, Zeitachse |

`CardView.getCurrentState().hasKeyword(Keyword.FLYING|TRAMPLE|…)` gibt die Schlüsselwörter der Quelle;
`GameEventCardDestroyed` und `GameEventTokenCreated` haben **keine** Nutzlast und sind unbrauchbar – Verluste und
Spielsteine werden deshalb aus `CardChangeZone` abgeleitet.

## 2. Neue Felder je Sitz (`MatchRecord.Seat`)

Alle Zähler sind `int`, fehlende Werte in alten Datensätzen lesen sich als `0`. Formatversion steigt auf `v: 2`
(rein additiv; `v: 1` bleibt lesbar, die neuen Felder sind dort schlicht 0 – die Auswertung muss das wissen und
Kennzahlen aus fehlenden Feldern als „keine Daten" behandeln, nicht als „0 %").

**Zauber und Interaktion**
- `spellsCountered` – eigene Zauber, die den Stack verlassen haben, ohne aufzulösen
- `spellsFizzled` – eigene Zauber, die mit `hasFizzled` auflösten (Ziele weg)
- `counterspellsCast` – eigene gewirkte Zauber mit `ApiType.Counter` in der Fähigkeitskette
- `cardsDrawn` – eigene Karten Bibliothek → Hand
- `cardsDiscarded` – eigene Karten Hand → Friedhof
- `cardsMilled` – eigene Karten Bibliothek → Friedhof/Exil (nicht über die Hand)

**Brett und Verluste**
- `permanentsLost` – eigene bleibende Karten Schlachtfeld → Friedhof/Exil
- `creaturesLostInCombat` – davon Kreaturen während `COMBAT_DAMAGE`
- `creaturesLostOther` – davon Kreaturen außerhalb des Kampfes (Entfernung, Opfer, Zustandsbasiert)
- `biggestSweep` – größte Zahl eigener bleibender Karten, die in **einem Auflösungsfenster** verloren gingen
- `sweepsSuffered` – Fenster mit `biggestSweep >= 3`
- `tokensCreated` – eigene Spielsteine, die ins Spiel kamen

**Kampf**
- `attacksDeclared` – eigene Kreaturen, die als Angreifer deklariert wurden (Summe über alle Kämpfe)
- `attackedTurns` – Züge, in denen mindestens eine eigene Kreatur angriff
- `attackersFaced` – gegnerische Kreaturen, die **gegen diesen Sitz** deklariert wurden
- `blocksDeclared` – eigene Kreaturen, die geblockt haben
- `damageTakenFlying`, `damageTakenTrample`, `damageTakenOther` – Kampfschaden am Sitz nach Schlüsselwort der Quelle
- `damageTakenNonCombat` – Schaden am Sitz ohne Kampf (`combat == false`)
- `damageDealtCombat`, `damageDealtNonCombat` – Schaden, dessen Quelle dieser Sitz kontrollierte
- `commanderDamageTaken` – Kampfschaden von Karten mit `isCommander()`
- `lifeGained` – Summe positiver Lebensänderungen

**Zeitachse** (`timeline`, Liste je eigenem Zug, Eintrag `{ turn, lands, creatures, life, hand, spells }`): Stand
jeweils zu Beginn des eigenen Zuges, gedeckelt auf 60 Einträge. Damit lassen sich später Kurven zeichnen („wann
bist du zurückgefallen") ohne neue Erfassung. `spells` fällt aus der Reihe: kein Stand, sondern die Zauber, die
der Sitz in dem **Zugabschnitt** gewirkt hat, den der Punkt eröffnet (vom Beginn seines eigenen Zuges bis zum
Beginn seines nächsten eigenen Zuges) – ein Blitz im Zug des Gegners zählt also zum vorangegangenen eigenen Zug.
Zauber vor dem ersten eigenen Zug und Züge hinter dem Deckel haben keinen Punkt und fallen aus der Zeitachse; die
Gesamtzahl steht unabhängig davon in `Seat.spells`.

**Nachtrag (nach der Review von Runde B): vier Felder, die jetzt fast nichts kosten und sich später aus keinem
Datensatz mehr rekonstruieren ließen.** Rein additiv, `VERSION` bleibt `2` – ein `v: 1`-Datensatz liest sie als 0.
- `TurnPoint.spells` – siehe oben
- `handEnd` – Handkarten des Sitzes, als er aus dem Spiel ging („mit voller Hand gestorben"). Gemessen beim
  **letzten Ereignis vor dem Ausscheiden**: `Game.onPlayerLost` lässt nach CR 800.4a alle Karten eines
  ausgeschiedenen Sitzes aufhören zu existieren, später nachzusehen liefert immer 0, und ein eigenes Ereignis für
  den Moment des Ausscheidens feuert Forge nicht. Ein Sitz, der überlebt, trägt seinen Stand am Partieende.
- `openingLands` – Länder in der Hand, die der Sitz nach allen Mulligans behalten hat, gezählt beim **ersten
  `GameEventTurnBegan`** der Partie (Mulligans sind durch, der erste Zug hat noch nicht gezogen). Grundlage für
  „behält Zwei-Land-Hände".
- `removalCast` – eigene Zauber, deren Fähigkeitskette `ApiType.Destroy`, `ApiType.DealDamage` auf ein Ziel, das
  eine bleibende Karte sein kann, oder `ApiType.ChangeZone` **vom Schlachtfeld weg** enthält. Gelaufen wird die
  Kette in demselben Gang wie für `counterspellsCast`.

**Genäherte Kennzahlen – wer sie auswertet, muss das wissen:**
- `cardsDiscarded` zählt **jeden** Weg Hand → Friedhof, nicht nur das Abwerfen im Wortsinn: Kosten, Wahnsinn und
  der Handkartenangriff des Gegners stehen alle darin.
- **Infektschaden zählt in den Kampf-Töpfen mit, kostet aber kein Leben.** Forge feuert `GameEventPlayerDamaged`
  auch für Infekt; der Recorder wertet das Feld `infect` nicht aus. `damageTaken` ist deshalb **nicht** das
  verlorene Leben – wer Leben rechnen will, rechnet mit `lifeEnd` (der Giftanteil steht in `poisonEnd`).
- `commanderDamageTaken` ist die Summe über **alle** gegnerischen Commander zusammen, nicht je Commander. Die
  21-Punkte-Regel (CR 903.10a) lässt sich daraus nicht ableiten.
- `removalCast` zählt die **Absicht, nicht den Erfolg**: ein gekonterter, verpuffter oder ins Gesicht geschossener
  Blitz zählt mit. Die Massenvarianten (`DestroyAll`, `ChangeZoneAll`, `DamageAll`) zählen **nicht** – eine
  Massenentfernung schlägt sich beim Gegner in `sweepsSuffered` nieder.
- `attackersFaced` löst eine Battle über ihren **Beschützer** auf (wie `Combat.getDefenderPlayerByAttacker`),
  `blocksDeclared` hängt an Forges `defendingPlayer` und damit am **Kontrolleur**. Die Asymmetrie steckt in Forge;
  wer eine Seite „geradezieht", verschiebt die Kennzahl auf den falschen Sitz.

**Auflösungsfenster** (für `biggestSweep`): ein Fenster beginnt bei `GameEventSpellResolved` bzw. bei einem
Phasenwechsel und endet beim nächsten dieser Ereignisse. Verluste innerhalb eines Fensters zählen als ein Vorgang.
Das ist eine Näherung (zustandsbasierte Aktionen aus mehreren Quellen landen im selben Fenster), aber robust und
ohne Eingriff in Forge.

## 3. Umsetzung

`MatchRecorder` bekommt zusätzliche `@Subscribe`-Methoden; die Zuordnung zum Sitz läuft weiter über die
`PlayerView`-Karte, für Zonenwechsel über `ZoneView.player()` (der Besitzer der Zone), für Schaden über den
Kontrolleur der Quellkarte (`CardView.getController()`, Rückfall `getOwner()`). Karten ohne bekannten Sitz werden
ignoriert. Kein Zähler darf eine Ausnahme werfen können: jede Handler-Methode fängt `RuntimeException` und zählt
den Fall nur nicht mit (ein kaputter Zähler darf keine Partie abschießen) – gemeldet wird das einmal je Partie
über `CrashLog.note`.

`AiMatch` und `HumanMatch` bleiben unverändert; `MatchStore` liest `v: 1`-Datensätze weiter. `Json.mapper()`
überliest unbekannte Felder (`FAIL_ON_UNKNOWN_PROPERTIES` aus): sonst kostet ein Rückschritt auf einen älteren
Bridge-Stand die ganze Historie – der wirft beim Lesen, `MatchStore.all()` meldet „kaputte Datei" und liefert
eine leere Liste, und der nächste Schreibvorgang ersetzt die Datei.

Die Sammelmeldung am Partieende (`CrashLog.note`) läuft in `build()` in einem `try/catch (RuntimeException)`:
sie ist die Nebensache, der Datensatz die Hauptsache. Würfe sie (kaputter Log-Pfad, volle Platte), stünde
`finished` zwar, der Sink bekäme die Partie aber nie – und ein zweiter `finish()`-Versuch kehrte sofort um.

## 4. Nachweise

Szenen-Tests (`mtgplayer.scene.Scene`, 2 Spieler) je Zähler, jeweils mit echtem Spielzug wo möglich, sonst über
`game.fireEvent(...)`:
- Zauber gekontert (Counterspell auf einen Zauber) → `spellsCountered` beim Zaubernden, `counterspellsCast` beim
  Konternden; verpuffter Zauber → `spellsFizzled`
- Ziehen/Abwerfen/Millen über echte Effekte (`Divination`, `Mind Rot`, `Mill`)
- Kreatur im Kampf verloren vs. per `Murder` entfernt → `creaturesLostInCombat` / `creaturesLostOther`
- `Day of Judgment` auf vier Kreaturen → `biggestSweep == 4`, `sweepsSuffered == 1`
- Angriff mit zwei Kreaturen, eine geblockt → `attacksDeclared`, `attackedTurns`, `attackersFaced`, `blocksDeclared`
- Schaden von einer fliegenden Kreatur → `damageTakenFlying`; Blitz vom Gegner → `damageTakenNonCombat`
- Zeitachse: nach drei eigenen Zügen drei Einträge mit steigenden Ländern
- Robustheit: ein Handler, dem die Quelle fehlt (`fireEvent` mit `null`-Feldern), bricht die Erfassung nicht ab
- Nachtrag: Murder/Flame Slash/Unsummon über den echten Stapel → `removalCast == 3`, Rampant Growth (ChangeZone
  aus der Bibliothek) und ein Kreaturenzauber zählen nicht, der Counterspell derselben Szene landet in
  `counterspellsCast`; Tod mit drei Handkarten → `handEnd == 3`; zwei Länder in der behaltenen Hand →
  `openingLands == 2` (ein danach gezogenes Land nicht mehr); Zauber vor/zwischen/nach eigenen Zügen →
  `TurnPoint.spells` je Zugabschnitt, hinter dem Deckel 0
- Robustheit: eine werfende Sammelmeldung (Log-Pfad ohne Elternverzeichnis) verschluckt den Datensatz nicht, und
  ein Datensatz mit unbekanntem Feld bleibt lesbar (`MatchStoreTest`)

Dazu ein Durchlauf `MatchRecorderAiTest` (echte KI-Partie): alle neuen Zähler sind ≥ 0 und plausibel
(`spellsCast >= counterspellsCast`, `permanentsLost >= creaturesLostInCombat + creaturesLostOther` gilt **nicht**
zwingend – Kreaturen sind eine Teilmenge, also `permanentsLost >= creaturesLostInCombat + creaturesLostOther`
prüfen und bei Verletzung den Grund im Test dokumentieren).

## Nicht in dieser Runde

Anzeige im Statistik-Screen (Runde A), statische Deckanalyse (Runde A), Auffälligkeiten-Regeln (Runde A),
Zuordnung „welcher Zauber hat meine Kreatur zerstört" (Forge liefert dafür keine verwertbare Nutzlast).
