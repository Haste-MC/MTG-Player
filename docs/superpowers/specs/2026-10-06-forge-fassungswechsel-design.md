# Forge-Fassungswechsel 2.0.14 → 2.0.15 – Design

Stand: 2026-10-06. Betrifft den Fork `Haste-MC/forge` (Branch `mtg-player`, Basis `forge-2.0.14`) und das
Hauptprojekt `Haste-MC/MTG-Player`, das ihn als Submodul `forge/` führt.

Dies ist der abgespaltene Abschnitt 8 aus `2026-10-02-kartendaten-abgleich-design.md`, jetzt als eigene
Arbeit — upstream hat am 2026-09-28 `forge-2.0.15` veröffentlicht.

## 1. Warum jetzt

Zwei Gründe, beide gemessen:

**Der Abstand ist minimal.** Genau eine Zwischenfassung. Wartet man auf 2.0.18, ist dieselbe Arbeit gegen
ein Vielfaches an fremden Änderungen zu machen.

**Der Gewinn ist bezifferbar.** Der erste Kartenabgleich (2026-10-06) hat 78 Karten ausgeschlossen, die
unsere Fassung nicht bauen kann, und zehn weitere auf unserer alten Fassung festgehalten. Die Ursachen
sind durchweg Engine-Lücken, keine Datenfehler:

| Ursache | Karten |
|---|---|
| `PutSticker` / `StickerPlaced` (Aufkleber-Mechanik der Un-Sets) | 40 |
| `Empower` (unbekannter `ApiType`) | 34 |
| `CombatDamageNegatePower`, `CantBeBeamedUp`, `IgnorePlaneswalkerZeroLoyaltyRule`, `FlippedCoinOnce` | 4 |
| auf alter Fassung festgehalten (`CanBlockIfShadow`, `ManaRestriction`, `CantGainControl`, `DrawFromBottom`) | 10 |

Wie viele davon 2.0.15 tatsächlich baut, misst der Prüfmodus (§4) — das ist zugleich der Erfolgsnachweis
dieser Arbeit.

## 2. Der Trockenlauf, der den Umfang festgelegt hat

Gemessen am 2026-10-06 gegen den Tag `forge-2.0.15`, ohne etwas zu verändern: unsere 33 Code-Commits
einzeln per `git am --3way` auf den Tag aufgetragen.

- **31 von 33 gehen glatt durch.**
- **Zwei Konflikte**, beide genau ein Block:
  - `forge-game/.../player/Player.java` — 5 strittige Zeilen (GameCopier/Monarch-Reparatur, `2990f760`).
  - `forge-ai/.../AiAttackController.java` — 52 strittige Zeilen (Goad-Angriffsplanung, `51251c00`).
    Upstream hat die Parallelverarbeitung dieser Methode umgebaut, von `CompletableFuture` auf
    `ExecutorService.invokeAll`. Unsere Schleife für Pflicht-Angreifer außerhalb des gewählten
    Verteidigers muss in der neuen Bauweise neu formuliert werden. Die REGEL ändert sich nicht, nur ihre
    Verdrahtung.
- Keine andere unserer Änderungen fasst diese beiden Dateien an. Das Überspringen der zwei Konflikte hat
  das Ergebnis der übrigen 31 also nicht geschönt.

## 3. Wie die Commits auf die neue Basis kommen

**Zuerst der Rettungsanker.** Der heutige Fork-Stand (`840f3cc0`, Merge des ersten Kartenabgleichs, Pull Request #1
im Fork) bekommt im Fork den Tag `mtg-player-2.0.14`; er enthält `903f0f1d` als Vorfahr, auf das die ausgelieferten
Pakete verweisen, und deckt es damit mit ab. Das ist Pflicht, nicht Bequemlichkeit: die veröffentlichten Pakete verweisen über
`forge/MODIFICATIONS.md` und den Release-Text auf genau diesen Quelltext, und die GPL verlangt, dass
dieser Verweis gültig bleibt. Ohne den Tag liefe er nach dem Umschreiben ins Leere.

**Dann 27 Commits neu aufsetzen** (`rebase --onto forge-2.0.15`). Nicht übertragen werden:

- die **drei Paare aus Bewertungs-Experiment und Rücknahme** (`620b238b`/`9f06a677`, `191a45df`/`ebc387ca`,
  `b7605d49`/`3b808037`) — sechs Commits, die sich aufheben. Ihre Messungen stehen in `docs/bench/` und
  in `docs/forge-fork.md`; der Verlauf muss sie nicht zweimal erzählen.
- der **Kartendaten-Commit** `0e30eaac` — 2.0.15 bringt diese Karten selbst mit, und für Kartendaten ist
  seit dem 2026-10-02 der wöchentliche Abgleich zuständig.
- der **Versions-Commit** `952c5e1a` — die Maven-Version wird für die neue Basis neu gesetzt
  (`2.0.15-mtgplayer`), nicht übertragen.

`forge/MODIFICATIONS.md` nennt danach `forge-2.0.15` als Basis.

**Ziel:** `git log forge-2.0.15..mtg-player` ist wieder genau die Liste unserer Änderungen — die
Eigenschaft, die den Fork lesbar hält und auf die `MODIFICATIONS.md` verweist.

## 4. Was das Hauptprojekt nachzieht

- `bridge/pom.xml`: Abhängigkeiten auf `2.0.15-mtgplayer`.
- Der Submodul-Zeiger auf den neuen Fork-Stand.
- Alle Stellen in der Dokumentation, die `forge-2.0.14` als Basis nennen (`docs/forge-fork.md`, README).
- **Die Ausschlussliste `docs/kartendaten-ausgeschlossen.txt` wird geleert** und vom nächsten Prüflauf neu
  aufgebaut. Sie gilt je Engine-Fassung (so steht es in `2026-10-02-kartendaten-abgleich-design.md` §5).
  Die Differenz zur alten Liste IST der Gewinn aus §1 und gehört in den Bericht dieser Arbeit.

## 5. Was belegt sein muss

Drei Stufen, von billig nach teuer. Jede muss stehen, bevor die nächste etwas wert ist.

**Szenentests.** Jede unserer KI-Reparaturen ist durch eine Szene festgenagelt, die vorher kaputt war:
`GoadTest` und `GoadedTokenSceneTest` (Goad), `ControlDonationSceneTest` (Iroh-Spende), `MeldTitaniaTest`
(Meld), `PhagePlayerLossTest` (ausgeschiedener Spieler), `SimCopierTest` (Kopiertreue), `MulliganTest`,
`SorceryCommanderTest`. Fällt einer um, ist sofort klar welcher und warum.

**Volle Bridge-Suite** auf dem Entwicklungsrechner — nicht die enge CI-Auswahl. Gerade die Tests, die
Forge wirklich hochfahren und spielen, sind hier die aussagekräftigen.

**Kartendaten-Prüfmodus** (`--kartendaten-pruefen`): liefert die Zahlen aus §1 für die neue Fassung.

**Bench-Vergleich.** Spiegelpartien, dieselben Startwerte für beide Seiten, alte Engine gegen neue, nach
derselben Machart wie die Stufe-2-bis-4-Läufe; Bericht nach `docs/bench/`.

> **Was der Bench leisten kann und was nicht:** Vierzig Partien tragen eine Unsicherheit von gut fünfzehn
> Prozentpunkten. Das fängt einen **Absturz** der Spielstärke, keine Feinheit. Wer eine Verschiebung von
> fünf Punkten sicher sehen will, braucht Hunderte von Partien; das ist eine andere Größenordnung und
> ausdrücklich NICHT Teil dieser Arbeit. Der Bericht muss das so sagen, statt eine Zahl ohne ihr
> Vertrauensintervall zu nennen.

## 6. Die Entscheidungsregel — vorher festgelegt

Verglichen wird gegen die **Kontrollseite desselben Laufs** — nicht gegen eine Zahl aus einem früheren
Bericht. Beide Seiten spielen dieselben Startwerte, nur die Engine unterscheidet sich; alles andere
(Decks, Profile, Bedenkzeit) bleibt gleich. Eine Zahl von damals wäre keine Vergleichsgröße, weil
seitdem auch unsere eigene KI-Arbeit weitergegangen ist.

Liegt die neue Fassung **klar unter** der Kontrollseite (Quote unterhalb deren unterer Schranke),
bleibt es bei 2.0.14: der Wechsel wird nicht in `main` übernommen. Die Arbeit ist dadurch nicht verloren —
der neu aufgesetzte Zweig bleibt im Fork stehen, bis upstream nachbessert oder eine spätere Fassung die
Sache dreht.

Liegt sie gleichauf oder besser, zieht das Hauptprojekt nach (§4).

Die Regel steht hier, damit sie nicht nach dem Ergebnis geschrieben wird.

## 7. Der Rückweg

Welche Engine gespielt wird, entscheidet allein der Submodul-Zeiger im Hauptprojekt. Zurück ist also ein
einziger Commit. Mit dem Tag `mtg-player-2.0.14` aus §3 bleibt der alte Stand dauerhaft erreichbar, auch
nachdem der Zweig umgeschrieben wurde.

**Solange der Bench nicht durch ist, wird aus der neuen Engine kein Paket gebaut.** Der Weg zu anderen
Leuten bleibt zu, bis die Zahl da ist.

## 8. Fehlerverhalten

| Fall | Verhalten |
|---|---|
| Ein Szenentest fällt um | Der Wechsel steht still, bis die Ursache benannt ist. Kein „beobachten wir mal". |
| Ein Konflikt lässt sich nicht sinnerhaltend auflösen | Der betroffene Commit wird mit Begründung ausgesetzt und als offener Punkt vermerkt — nicht halb umgesetzt. |
| Bench klar schlechter | §6, Fall eins. |
| Kartenzahl sinkt gegenüber 2.0.14 | Abbruch und Untersuchung. Eine neuere Engine darf nicht weniger Karten kennen. |

## 9. Grenzen, die bleiben

- Der Trockenlauf belegt, dass unsere Änderungen **textlich** passen, nicht dass sie weiterhin das
  Richtige tun. Genau dafür sind die Szenentests da.
- Der Bench fängt keinen feinen Rückgang der Spielstärke (§5).
- Forges eigene Simulations-Tests laufen in dieser Umgebung nicht (`GuiDesktop` braucht eine echte
  Anzeige, siehe `docs/forge-fork.md`). Daran ändert der Wechsel nichts.

## 10. Nicht enthalten

- Ein Sprung über 2.0.15 hinaus.
- Das Zurückgeben unserer Reparaturen an upstream.
- Die wöchentliche Benachrichtigung über neue Forge-Releases. Sie war Teil des alten Abschnitts 8;
  nachdem dieser Wechsel von Hand angestoßen wurde, ist sie erst wieder nötig, wenn 2.0.16 ansteht —
  eigene, kleine Arbeit.
