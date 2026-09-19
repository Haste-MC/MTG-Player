# Spielfeld-Reihen und Kartengröße (UI-Runde 4) – Design

Stand: 2026-09-19. Befund von Kevin (Zuschauer, 6 Spieler, 1920×1080): Karten im Spielfeld winzig
(~90 px), Länder kleben unter den Kreaturen, darunter ein Drittel Leere; getappte Karten nur
abgedunkelt mit Badge – „sieht bissl doof aus".

## Ziel

Das Spielfeld eines Spielers nutzt die Panelhöhe: drei Reihen (Kreaturen, übrige bleibende Karten,
Länder unten), Kartenbreite so groß, wie Breite **und** Höhe des Panels hergeben, getappte Karten
gedreht. Gilt für **Zuschauer-Panels** und die **eigene Zone** der Tischansicht. Die kompakten
Gegnerzeilen der Tischansicht bleiben unverändert (einzeilig, Badge statt Drehung) – Beschluss A.

## Reihen

Aus `p.battlefield` (ohne Effekt-/Emblem-Chips, weiterhin per `groupCards` gestapelt):

| Reihe | Karten | Klasse |
|---|---|---|
| 1 (oben) | `typeLine` enthält „Creature" | `.bf-creatures` |
| 2 | alles andere ohne „Land" (Artefakte, Verzauberungen, Planeswalker, Schlachten) | `.bf-other` |
| 3 (unten) | `typeLine` enthält „Land" (Kreatur-Länder, z. B. Dryad Arbor, zählen als Kreatur) | `.bf-lands` |

Leere Reihen werden nicht gerendert. Reihen brechen um (`flex-wrap`), scrollen nie. Die
Länderreihe sitzt an der Unterkante des Spielfelds (`margin-top: auto` im Reihen-Container); die
anderen Reihen beginnen oben. Länder haben den Faktor **0,8** der Kartenbreite.

## Kartengröße per Messung

Neuer Hook `useBoardSize(ref, rows, enabled)` in `web/src/boardSize.ts` (+ reine Funktion
`fitCardWidth(w, h, rows, opts)` für Vitest):

- Eingabe: Innenmaß des Reihen-Containers (`ResizeObserver` auf `.rows`, gemessen ohne Padding)
  und je Reihe die Liste der Slot-Breiten in Karteneinheiten: ungetappt = 1, getappt = 1,4
  (gedreht), Länder-Reihe mit Faktor 0,8.
- `fitCardWidth` sucht per Binärsuche (ganzzahlig, 50–180 px) die größte Breite `w`, bei der
  jede Reihe, zeilenweise umgebrochen (Slot-Breite `w·faktor(+gap)`, Zeilenhöhe `1,4·w·faktor + gap`),
  in die Containerbreite passt und die Summe aller Zeilenhöhen plus Reihenabstände ≤ Höhe ist.
  Ungetappte Karte im Umbruch: eine Zeile enthält immer mindestens einen Slot. Ergebnis wird
  als `--w` (Karten) und `--w-land` (0,8·`--w`) auf `.rows` gesetzt.
- Läuft nach jedem Render mit geänderter Reihen-Signatur (Karten-IDs + getappt) und bei Resize.
  Kein Nachbesser-Loop mehr: `useFit`, `--fit`, `--w-spec`, `--w-fit`, `--w-land-fit`, `--n-bf`,
  `--n-lands` im Zuschauer-CSS entfallen. Zuschauer-Hand behält `--w-spec-hand` → wird zu
  `calc(var(--w) * 0.68)`, `--hand-max` analog.
- Eigene Zone: derselbe Hook; `--card-w`/`--land-w` werden Obergrenzen (die Formel clampt auf
  max. 180 px, darunter greift die Messung). Bei 1280×720 muss die Hand-Zeile ihre heutige Höhe
  behalten – die Messung gilt nur für `.rows`, deren Höhe das Grid vorgibt.

## Getappt = gedreht

- CSS-Regeln für Rotation (`.card-slot.tapped-slot { width: var(--h) }`, `.card-frame.tapped`,
  `.card.tapped { rotate 90deg }`, `.tapped-tag { display: none }`) gelten nicht mehr nur für
  `.player.own .battlefield`, sondern für alle Reihen mit Klasse `.bf-rows` (Zuschauer + eigene Zone).
  Kompakte Gegnerzeilen (`.player.compact:not(.spectator)`) behalten Badge + Abdunklung.
- Gedreht: Bild `brightness(0.82)` wie heute in der eigenen Zone; Tags bleiben aufrecht (liegen
  im `.card-frame`).
- Stapel: gedreht, wenn `tapped === cards.length` (alle getappt). `groups.ts` unverändert;
  `CardBox` bekommt aus `stack` die Info und setzt `tapped`-Klassen entsprechend (die gezeigte
  Karte ist ohnehin die erste ungetappte).

## Tabletop-Stapel

Ein Stapel (`stack.count > 1`, siehe `groups.ts`) sieht aus wie ein echter Kartenstapel:

- Hinter der obersten Karte liegen bis zu **4 Ebenen** (`min(count − 1, 4)`), jede um 5 px nach
  oben und rechts versetzt, mit demselben Bild (`CardImage` mit gleichem `imageKey`; kein
  Text-Fallback nötig – Ebenen ohne Bild sind leere Kartenrahmen). Das Badge „×N" bleibt.
- **Getappte Karten im Stapel liegen gedreht unter den ungetappten** und ragen links/rechts
  hervor: die untersten `min(tapped, 4)` Ebenen sind um 90° gedreht und abgedunkelt wie
  getappte Karten. Das Badge „N getappt" entfällt. Sind alle getappt, dreht sich der ganze
  Stapel (oberste Karte + Ebenen).
- Die Ebenen sind keine Klickziele (`pointer-events: none`); Klick und Hover gehen wie bisher
  an die oberste Karte (erste ungetappte).
- Slot-Breite: ohne getappte Karten `w + 5 px · Ebenen` (nach rechts; der Slot wächst um den
  Versatz), mit getappten Karten `1,4·w` (gedrehte Ebenen). Für `fitCardWidth` zählt ein Stapel
  mit getappten Karten 1,4 Einheiten, sonst 1 + 0,04·Ebenen.
- Der bisherige Schatten-Versatz (`.card-frame.stacked::before`) entfällt.
- Kompakte Gegnerzeilen (Tischansicht) zeigen die Ebenen ebenfalls, aber ohne Drehung – dort
  bleibt „N getappt" als Badge, weil die Zeile keine gedrehten Karten kennt (Beschluss A).

## Bestand

- Kommandozone links oben, Zuschauer-Hand links unten (überlappend, max. 2,2 Kartenbreiten wie bisher),
  Friedhof/Exil rechts – Grid bleibt.
- Effekt-Chips, Token-Rahmen, Prompt, Log: unverändert.

## Tests und Abnahme

- Vitest `boardSize.test.ts` (zusätzlich: (7) Stapel mit getappten Karten zählt 1,4, ohne 1 + 0,04·Ebenen): (1) leere Reihen → Maximum; (2) eine Reihe mit 4 Karten in 600×500
  → Breite aus der Höhe (Wrap in 2 Zeilen ist besser als 4 in einer Zeile); (3) getappte Karten
  verbreitern; (4) Länder-Faktor; (5) nie unter 50 / über 180; (6) Höhe bindend.
- `layout-check.mjs` bekommt zusätzlich: in jedem `.player` mit `.bf-creatures` von ≤ 5 Stapeln
  bei Viewport ≥ 1600 breit ist die gemessene Kartenbreite ≥ 110 px (`spectator-heavy` mit 6
  Spielern) und ≥ 120 px bei 1920×1080; getappte Karten liegen vollständig im Panel (bestehende
  Clipping-Prüfung). Neue Fixture `spectator-rows.json`: 6 Spieler, Boards mit 3–8 Kreaturen,
  2–4 Artefakten/Verzauberungen, 6–12 Ländern, mindestens die Hälfte getappt, darunter Basic-Stapel mit teils/ganz getappten Karten.
- Playwright-Screenshots (`polish4/`): `spectator-rows` bei 1920×1080 und 1280×720,
  `table` 1600×900 und 1280×720 mit getappten Ländern/Kreaturen in der eigenen Zone. Kevins
  Screenshot als Referenz: `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/images/4.webp`.
- Suiten: `npm test`, `npm run build`, `npm run layout-check` (alle Fixtures, 1280×720 / 1600×900 /
  1920×1080); Bridge unberührt.
