# KI-Paket Stufe 4 – Szenen-Harness, Meld/Goad, Mulligan, Bewertung – Design

Stand: 2026-09-20. Kevins Auftrag: „mach mal die mulligan-heuristik und die bewertung", plus Befund: Titania, Voice
of Gaea + Argoth, Sanctum of Nature lagen in sechs Spielen und wurden nie gemeldet; Frage nach Goad. Grundlage:
`docs/bench/2026-09-20-stufe-3-kandidaten.md` (Sim 70 % im Spiegel; 30 s Budget bringt nichts → Engpass Bewertung)
und `docs/bench/2026-09-20-stufe-2-nichtstun.md` (Niederlagen = Land-/Farbmangel, Mulligan behält jede 2-Land-Hand).
Fork `Haste-MC/forge` @ `mtg-player`.

## 1. Szenen-Harness (Bridge, Test-Infrastruktur)

`bridge/src/test/java/mtgplayer/scene/Scene.java`, nach dem Muster von Forges `AITest` (forge-gui-desktop, dort nur
mit Display lauffähig): `Scene.twoPlayers(AiConfig a, AiConfig b)` baut `Match`/`Game` mit leeren Decks
(`GameType.Commander`-Regeln über `CommanderRules.create()`, `game.setAge(GameStage.Play)`), `card(name, player,
zone)` legt Karten per `Card.fromPaperCard` an (neuer Timestamp), `setPhase(PhaseType, player)`
(`devModeSet` + `onStackResolved`), `loopUntilPhase(PhaseType)`/`step(n)` treiben `PhaseHandler.mainLoopStep`
(Deckel 500 Schritte), `has(player, zone, name)`. Optional Sim-KI je Sitz über `AiConfig.newLobbyPlayer`.
`ForgeBoot.init()` wie in den anderen Tests. Damit werden Mechanik-Fälle in Sekunden testbar; die bisherigen
replay-basierten `SimCopierTest`-Fälle bekommen deterministische Geschwister (`GameCopierTest`: Zombie-Monarch-
Effektkarte, `<Nothing>` im Kampf, erinnerter Token im Exil, jeweils `new GameCopier(game).makeCopy()` ohne
Ausnahme).

## 2. Meld: Titania, Voice of Gaea + Argoth, Sanctum of Nature

Trigger (Kartenskript): Upkeep, **≥ 4 Landkarten im eigenen Friedhof**, beide besessen und kontrolliert → exile,
meld in Titania, Gaea Incarnate. Szene A (Mechanik): KI kontrolliert beide, 4 Länder im Friedhof, Phase auf
Gegner-Endstep, Schleife bis nach dem eigenen Upkeep → Erwartung: „Titania, Gaea Incarnate" im Spiel, keine
Ausnahme. Schlägt das fehl → Fork-Fix (Trigger/`MeldEffect`/`MeldAi`) und Szene bleibt als Regressionstest.
Szene B (Strategie): KI kontrolliert beide, 2 Länder im Friedhof, Argoth ungetappt + genug Mana in Main 1 →
Erwartung: die KI aktiviert Argoths `{2}{G}{G},{T}` (Bär + Mill 3) statt zu passen. Schlägt B fehl → Spiellogik im
Fork: `TokenAi`/generische Aktivierung bevorzugt Fähigkeiten mit `SubAbility Mill`, wenn eine eigene Karte einen
`Meld`-Trigger mit `CheckSVar`-Bedingung auf Länder im Friedhof hat und die Schwelle noch nicht erreicht ist
(zielgerichtet, upstream-tauglich: „AI works toward its own meld condition"). Beide Szenen auch mit Sim-KI.

## 3. Goad

Szene: drei Spieler; Gegner G goadet eine Kreatur der KI (`Goad`-Effekt, z. B. über eine Karte mit `Goad`-API);
im nächsten Kampf der KI: die Kreatur greift an (Pflicht) und **nicht** G, sofern ein anderer Spieler angreifbar
ist; Sim-KI: dieselbe Szene, Bewertung darf die gegoadete Kreatur nicht als „frei" zählen. Erwartungen als Tests;
Abweichungen → Fix in `AiAttackController` (Ziel-Wahl bei Goad) bzw. `GameStateEvaluator`/`CreatureEvaluator`.

## 4. Mulligan-Heuristik (`ComputerUtil.scoreHand`, `PlayerControllerAi.tuckCardsViaMulligan`)

Änderungen, per AI-Profil-Property schaltbar (`MULLIGAN_CHECK_COLORS=true`, in `Default.ai` an, neue Property in
`AiProps`; `false` = altes Verhalten):
- **Farben:** aus den Ländern der Hand die produzierbaren Farben (Any-Color zählt für alle) bestimmen; ein Zauber
  gilt als „spielbar", wenn CMC ≤ Landzahl **und** alle farbigen Pips der Hand-Länder abgedeckt sind. Hand ohne
  spielbaren Zauber mit CMC ≤ 3 → wie 0/1-Land-Hand bewertet (Mulligan, außer an der Schwelle).
- **Kurve:** Bonus für ≥ 2 spielbare Zauber mit CMC ≤ 3 (statt „castables × 2" ohne Farbprüfung).
- **Bottoming (London):** Karten mit dem höchsten „Unspielbarkeits-Rang" zuerst weglegen: unspielbare Farbe >
  CMC ≥ 6 > überzählige Länder (über `finalHandSize/2 + 1`) > höchste CMC.
- Commander-Spezifikum: der Commander liegt nicht in der Hand; Farbidentität aus `player.getCommanders()` als
  Hinweis, welche Farben das Deck braucht (Länder, die keine Identitätsfarbe liefern, zählen nicht).
Nachweis: Bench Standard(neu) gegen Standard(alt) über Profile (`--a std:Default --b std:Legacy`, `Legacy.ai` =
Kopie von `Default.ai` mit `MULLIGAN_CHECK_COLORS=false`), Abzan-Spiegel und Ahoy-Spiegel je 40 Seeds → Erwartung:
Untergrenze ≥ 50 % in beiden, und die Zahl der „Nichtstun"-Spiele (≤ 2 Zauber bei ≥ 5 Ländern) sinkt (Bench zählt
aus dem Log: neue Kennzahl `fewSpells` je Spiel).

## 5. Bewertung (`GameStateEvaluator`)

Änderungen einzeln, jede per Spiegel-Bench (Ahoy, 40 Seeds, 10 s) gegen die Referenz 28:12 (Stufe 3) gemessen;
behalten wird, was die Siegquote nicht senkt und die Untergrenze hebt:
1. **Landgewicht:** `evalManaBase` gibt 100 je Mana/Pip bis zum Deckmaximum – ein Land wiegt so viel wie ein
   3-Mana-Permanent. Vorschlag: 100 → 60 je Mana bis `min(maxCost, 6)`, darüber 5; Pips 100 → 40.
2. **Handkarte:** 5 → 12 (1 + 4 · fullValue → 4 + 8), damit Kartenvorteil zählt; Abwurf auf Handgröße bleibt 0.
3. **Bleibende Nicht-Kreaturen:** 50 + 30·CMC → 40 + 35·CMC (ausgespielte Zauber sollen die Handkarte + das
   ausgegebene Mana klar überwiegen).
4. **Mana offen halten:** kein neuer Term (zu unklar), aber: `availableValue`-Regel nur in Main 1 anwenden, wenn der
   Zug in Main 2 noch bezahlbar wäre (Nichtstun-Doc, Fall Breeches).
Reihenfolge: 1 → 2 → 3 → 4; nach jedem Schritt Bench; Abbruch eines Schritts, wenn die Quote sinkt.

## Nicht in dieser Stufe

Gelernte Bewertung, 4er-Pod-Bench, Deckbau-Hinweise (`DeckHints`), Upstream-PRs (Patches bleiben upstream-fähig).
