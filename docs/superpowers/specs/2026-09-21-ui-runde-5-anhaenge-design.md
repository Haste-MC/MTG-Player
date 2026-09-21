# UI-Runde 5 – Anhänge (Auren/Equipment) und Grab-Popup – Design

Stand: 2026-09-21. Kevins Wünsche: (1) das aufgeklappte Grab in Zuschauer-Panels am linken Bildrand läuft aus
dem Bild; (2) eine sichtbare Darstellung angelegter Auren/Equipment an Kreaturen und an Spielern. Gewählt:
Variante A „Tabletop – untergeschoben".

## 1. Grab-/Exil-Popup (`Pile` in `PlayerZone.tsx`)

`.pile-list` öffnet heute immer nach links (`right: calc(100% + 14px)`). Neu: `Pile` entscheidet beim Öffnen
(`onToggle`) per `getBoundingClientRect()` der Vorschau: liegt ihre Mitte in der linken Hälfte des Viewports,
öffnet die Liste nach rechts (`left: calc(100% + 14px); right: auto`, Klasse `open-right`), sonst wie bisher.
Vertikal bleibt es beim Bestehenden (eigene Zone oben ausgerichtet, kompakt/Zuschauer unten). Damit stimmt es
für eigene Zone (rechts unten → links), Gegnerzeile (Stapel rechts → links) und Zuschauer-Panels der linken
Spalte (→ rechts) bzw. rechten Spalte (→ links).

## 2. Datenmodell

- Karten an Karten: schon da (`CardSnap.attachedTo`, `CardSnap.attachments`).
- Karten an Spielern (Flüche u. Ä.): neu `CardSnap.attachedToPlayer?: number` aus `CardView.getEnchantedPlayer()`
  (`StateSerializer.cardSnap`), nur gesetzt, wenn vorhanden. `Snapshot.CardSnap` bekommt das Feld, Test in
  `StateSerializerTest` (bestehende Struktur) bzw. Szene: Aura mit `Enchant player` (z. B. „Curse of the Bloody
  Tome") an Spieler B → Snap trägt `attachedToPlayer = B`.

## 3. Zuordnung im Panel (`PlayerZone.tsx`, neue reine Funktion in `groups.ts`)

`attachedBy(cards: Record<string, CardSnap>)` liefert `Map<hostId, CardSnap[]>` für alle Karten mit
`attachedTo`, deren Wirt existiert, nicht verdeckt ist und in `zone === "battlefield"` liegt; Reihenfolge nach der
`attachments`-Liste des Wirts (fehlende Einträge hinten, nach id). Ein Panel rendert im Spielfeld nur noch
Karten, die **nicht** in dieser Map als Anhang stehen (auch fremde Auren auf eigenen Kreaturen kommen so zum
Wirt, egal wessen Spielfeld sie zugeordnet sind). Anhänge ohne bekannten/sichtbaren Wirt bleiben, wo sie sind.
`splitRows` ist unverändert; Wirte mit Anhängen sind (wie bisher) nicht stapelbar (`isStackable`).

Spieler-Anhänge: Karten mit `attachedToPlayer === p.id` verschwinden aus dem Spielfeld des Kontrolleurs und
erscheinen im Kopf des verzauberten Spielers.

## 4. Darstellung Karte-an-Karte (`CardBox.tsx`, `styles.css`)

Neuer Prop `attached?: CardSnap[]`. Aufbau wie die Tabletop-Stapel: `.attach-layers` (absolut im `.card-frame`,
hinter `.card`) mit je einer `.attach-layer` pro Anhang (Bild bzw. Textfallback mit Name), aufrecht, um
`k * var(--attach-dy)` nach oben versetzt (`--attach-dy = calc(var(--h) * 0.22)`, k = 1..n, der erste Anhang
liegt direkt hinter dem Wirt und schaut am wenigsten heraus). Maximal 4 Ebenen; ab dem fünften trägt die vierte
Ebene ein Tag „+N". Jede Ebene ist hover- (`setHover(id)`) und klickbar (`selectCard` mit eigener id, Alt =
Rechtsklick), trägt `selectable`/`actionable`/`highlighted`-Rahmen der eigenen Karte und zeigt bei fehlendem Bild
den Namen. Der Slot bekommt `--attach-n` und `margin-top: calc(var(--attach-n) * var(--attach-dy))`, damit die
herausragenden Kanten die Reihe darüber nicht überlappen.

Getappter Wirt (`.bf-rows .card-frame.tapped`): Ebenen bleiben aufrecht und schauen weiter nach oben heraus
(bewusst vereinfacht – lesbar und ohne Seitenversatz; der Wirt dreht sich allein). Die Ebenen sind dabei an der
Unterkante des gedrehten Rahmens ausgerichtet (`bottom: 0` statt Zentrierung), damit nichts unten heraussteht.

Kompakte Gegnerzeile (`.player.compact:not(.spectator)`, keine Drehung, feste Zeilenhöhe, horizontales
Scrollen): Ebenen schauen **seitlich rechts** heraus (`translateX(k * var(--attach-dx))`, `--attach-dx =
calc(var(--w) * 0.28)`), der Slot wird entsprechend breiter (`width: calc(var(--w) + n * --attach-dx)`), Höhe
bleibt. Ein kleines Tag „⚔ n"/„✦ n" ist nicht nötig – die Kanten sind sichtbar.

## 5. Messung (`boardSize.ts`)

`RowSpec` bekommt `headroom?: number` (in Karteneinheiten, bezogen auf `w * scale`): der größte
`n * 0.22 * RATIO` unter den Slots der Reihe. `fits()` rechnet je Zeile der Reihe `lineH + headroom * w * scale`
(konservativ: jede umgebrochene Zeile bekommt den Zuschlag). `slotUnits` bleibt (Breite); ein neuer Helfer
`slotHeadroom(n: number)` liefert `Math.min(n, 4) * 0.22 * RATIO`. Test in `boardSize.test.ts`: eine Reihe mit
einem Wirt mit 2 Anhängen passt bei gleicher Breite erst bei kleinerer Kartenbreite als ohne.

## 6. Darstellung an Spielern (`PlayerZone.tsx`)

Im Kopf hinter den Badges: je Anhang ein Chip `.effect-chip.aura` (Muster `Effects`, Marke „Aura" wie die Emblem-Marke): Name, Tooltip „Aura von
<Kontrolleur>", Hover → Detail-Panel, Klick → `selectCard`. Kein Bild (wie Embleme/Effekte).

## 7. Nachweise

- `groups.test.ts`: `attachedBy` (Reihenfolge, fremder Wirt, Wirt außerhalb des Spielfelds → nicht zugeordnet).
- `boardSize.test.ts`: headroom.
- Fixture `fixtures/table-attach.json` (aus `table.json`): eigene Kreatur mit 2 Auren + 1 Equipment (eine Aura
  gehört dem Gegner), getappte Kreatur mit 1 Aura, Gegnerkreatur mit 1 Equipment, Fluch auf mir; Fixture
  `fixtures/spectator-attach.json` (aus `spectator-rows.json`) mit denselben Fällen in Panel 1 (linke Spalte)
  plus aufgeklapptem Grab (per Playwright-Klick im Layout-Check).
- `scripts/layout-check.mjs`: `.attach-layer` und `.pile-list` gehören zu den Elementen, die Regel 2/3
  (nicht von Vorfahren beschnitten / im Viewport) prüfen; neue Regel 12: aufgeklappte `.pile-list` liegt
  vollständig im Viewport.
- Screenshots 1600×900 und 1280×720 für Tisch (eigene Zone + Gegnerzeile) und Zuschauer (Panel links).

## Nicht in dieser Runde

Drehung der Anhänge mit dem Wirt, Pfeile/Linien zu fremden Wirten, Anhänge in Grab-/Exil-Listen, Fokus-Panel.
