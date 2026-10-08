# Tisch lesbar machen – Design

Stand: 2026-10-08. Betrifft `web/` (drei Teile) und `bridge/` (einer).

Anlass: Kevin hat am 2026-10-08 beim Spielen drei Dinge gemeldet, nachdem eine von mir
durchgespielte Zuschauer-Partie sauber durchlief (40 Züge, Datensatz gewertet):

1. Der Spielende-Dialog nennt den Sieger nicht, obwohl der Datensatz ihn kennt.
2. Die Gegner-Panels sind beim eigenen Spielen zu klein.
3. Die Kartenvorschau beim Anfahren ist zu klein zum Lesen.

## 1. Der Sieger im Spielende-Dialog

**Befund.** `WebGuiGame.finishGame()` nimmt den Namen aus `GameView.getWinningPlayerName()`.
Forge füllt dieses Feld in `GameView.updateGameOver` nur, wenn
`GameOutcome.getWinningLobbyPlayer()` einen Sitz findet, dessen `PlayerStatistics.getOutcome()`
auf „hat gewonnen" steht. In der gemessenen Partie (4 Sitze, Grund `AllOpponentsLost`, drei
Gegner auf 0 Leben) war das keiner — der Dialog zeigte „Spiel beendet" statt „KI 4 gewinnt",
während der Datensatz KI 4 korrekt als Sieger führt.

**Entscheidung.** Rückfall in der Bridge statt Eingriff in die Engine: Liefert Forge keinen
Namen und hat in der `GameView` genau EIN Sitz nicht verloren, ist das der Sieger. Sonst bleibt
es beim bisherigen Verhalten („Spiel beendet" bzw. „Unentschieden").

Genau ein Sitz, nicht „der erste Überlebende": bei einem echten Unentschieden stehen mehrere,
und dann ist „Unentschieden" die richtige Antwort, keine Behauptung.

Die Regel wohnt in einer eigenen, paketsichtbaren Funktion und nicht im Dialog-Aufbau — so
lässt sie sich ohne laufende Partie prüfen.

## 2. Ziehbare Trennlinie zwischen Gegnern und eigener Zone

**Heute.** `.table` ist ein Raster mit `grid-template-rows: auto minmax(0, 1fr)`: die
Gegner-Zeile nimmt ihre natürliche Höhe, die eigene Zone den Rest. Die Karten der Gegner sind
über `--card-w-opp: clamp(60px, 9vh, 92px)` gedeckelt.

**Entscheidung.** Ein schmaler Griff zwischen beiden Zeilen. Ziehen setzt die Höhe der
Gegner-Zeile, Doppelklick stellt „so viel wie nötig" wieder her.

- Grenzen: mindestens 120px, höchstens 70% der Fensterhöhe. Ohne Untergrenze zieht man die
  Zeile versehentlich auf null und kommt ohne Doppelklick nicht zurück.
- Gespeichert im `localStorage` je Gerät, nicht in `~/.mtg-player`: das ist eine Ansichtssache
  des Fensters, keine Spieldaten. Ein gelöschter Browserspeicher kostet nichts.
- Ungezogen bleibt die Zeile auf `auto` — wer den Griff nie anfasst, sieht die Oberfläche
  unverändert.
- Nur im eigenen Spiel. Der Zuschauer-Modus hat keine eigene Zone und damit nichts zu teilen.

## 3. Einen Sitz groß aufklappen

**Entscheidung.** In der Kopfzeile jedes Gegner-Panels ein Knopf („⤢", mit Beschriftung für
Vorleseprogramme). Klick zeigt diesen Sitz groß über dem Tisch, in derselben Darstellung wie
die eigene Zone (`compact={false}`), also in voller Kartengröße. Schließen per Klick auf den
Hintergrund oder mit Esc.

Bewusst ein eigener Knopf statt eines Klicks auf das Panel: auf den Karten liegen bereits
Klicks (Ziele wählen, Fähigkeiten), und ein Panel-Klick würde sie kapern.

## 4. Große Kartenvorschau am Mauszeiger

**Heute.** Die rechte Spalte zeigt beim Anfahren Bild und Text; das Bild ist über
`--detail-h` auf etwa 193px Breite begrenzt. Scryfall liefert 488×680 — die Auflösung ist da,
es fehlt nur die Fläche.

**Entscheidung.** Ein Pop-up, das frei über dem Tisch liegt, etwa 420px breit.

- Erscheint nach 120ms. Ohne Verzögerung flackert es beim Überstreichen einer Kartenreihe.
- Folgt dem Zeiger und bleibt vollständig im Bild: stößt es an einen Rand, klappt es auf die
  andere Seite des Zeigers bzw. wird an der Kante gehalten.
- `pointer-events: none` — es fängt keine Klicks ab, der Tisch bleibt bedienbar.
- Verdeckte Karten (`faceDown`) bekommen keins, wie schon im Detail-Panel.
- Die rechte Seitenleiste bleibt unverändert. Dort steht der Regeltext als echter Text:
  kopierbar, schärfer als jedes Bild und auch dann da, wenn das Bild nicht lädt.

**Mausposition.** Ein einziger `mousemove`-Zuhörer, der nur läuft, solange eine Karte
angefahren ist. Die Alternative — die Position an den Setz-Stellen durchreichen — berührt vier
Komponenten (`CardBox` zweimal, `Combat`, `Log`) und bringt nichts dafür.

## Nicht enthalten

- Größenänderung der rechten Spalte (eigene Sache, falls sie je stört).
- Ein Bild für verdeckte Spielsteine (`t:hidden` hat bei Scryfall keins; siehe die neue
  Logzeile vom 2026-10-08).
- Irgendeine Änderung am Zuschauer-Raster.

## Prüfung

- Teil 1: die Rückfall-Regel mit einem Test über eine Liste von Sitzen (ein Überlebender, zwei
  Überlebende, keiner).
- Teile 2–4: `vitest` für das, was Logik ist (Grenzen der Höhe, Seitenwahl des Pop-ups), und
  Screenshots aus einer echten Partie über Playwright gegen eine eigene Bridge auf eigenen
  Ports — so wie der Durchlauf vom 2026-10-08.
