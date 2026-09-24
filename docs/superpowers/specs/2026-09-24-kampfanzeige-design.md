# Kampfanzeige – wer greift wen an – Design

Stand: 2026-09-24. Kevins Punkt: „kann man noch eine visuelle anzeige machen wer wen angreift".

Heute überträgt die Bridge je Karte nur zwei Schalter, `attacking` und `blocking`
(`StateSerializer.cardSnap`), und das UI macht daraus die Marke „Angriff" bzw. „Block". **Wer** das Ziel
ist und **welcher Blocker zu welchem Angreifer** gehört, steht nirgends – weder im Protokoll noch am
Bildschirm. Forge kennt beides (`GameView.getCombat()` → `CombatView`), es wird nur nicht serialisiert.

Entschieden: Ziel **und** Blockerzuordnung (keine Schadensrechnung), Darstellung als Panel in der rechten
Spalte (kein Band über dem Brett, damit die gemessene Kartengröße aus `boardSize.ts` bei Kampfbeginn nicht
springt). Pfeile quer über den Tisch bleiben als mögliche zweite Runde offen und sind hier nicht enthalten.

## 1. Protokoll

`Snapshot` bekommt ein neues, letztes Feld `combat`: eine Zeile je Angreifer, `null` wenn kein Kampf läuft
(kein leeres Array – das UI prüft nur auf „da oder nicht").

```java
public record AttackSnap(
        int attacker,
        /** Id des verteidigenden Spielers, wenn der Angriff einem Spieler gilt, sonst null. */
        Integer defenderPlayer,
        /** Id der verteidigenden Karte (Planeswalker, Battle), sonst null. */
        Integer defenderCard,
        /** Blocker dieses Angreifers, null wenn keiner zugeteilt ist. */
        List<Integer> blockers) { }
```

Genau eines von `defenderPlayer` / `defenderCard` ist gesetzt; liefert Forge kein Ziel, sind beide `null`
(die Zeile bleibt trotzdem erhalten, siehe §4).

TypeScript-Gegenstück in `web/src/protocol.ts`:

```ts
export interface AttackSnap {
  attacker: number;
  defenderPlayer?: number;
  defenderCard?: number;
  blockers?: number[];
}
```
und `combat?: AttackSnap[]` in `Snapshot`.

## 2. Bridge (`StateSerializer`)

Neu in `snapshot(GameView gv, ViewContext ctx)`, nach dem Stack:

- `CombatView cv = gv.getCombat();` – ist `cv` null oder `cv.getNumAttackers() == 0`, bleibt `combat` null.
- Sonst je Angreifer aus `cv.getAttackers()` eine `AttackSnap`:
  - Ziel: `cv.getDefender(attacker)` liefert ein `GameEntityView`; `instanceof PlayerView` → `defenderPlayer`,
    `instanceof CardView` → `defenderCard`, sonst beide null.
  - Blocker: `cv.getBlockers(attacker)`; ist die Liste leer oder null, ersatzweise
    `cv.getPlannedBlockers(attacker)` – solange der Mensch zuteilt, führt Forge die Zuordnung dort, und
    genau in diesem Moment ist die Anzeige am nützlichsten. Bleibt auch die leer, ist `blockers` null.
- Angreifer, Verteidigerkarte und Blocker werden über die bestehende `cards.computeIfAbsent(...)`-Logik in
  die Kartentabelle des Snapshots aufgenommen, falls sie dort noch fehlen; das UI darf sich aber nicht
  darauf verlassen (§4).
- Reihenfolge: wie von Forge geliefert, nicht umsortiert – so bleibt die Liste zwischen zwei Snapshots
  desselben Kampfes stabil.

Die Karten-Schalter `attacking` / `blocking` bleiben unverändert erhalten (ältere Clients, Fixtures, Tests).

## 3. UI

### 3.1 `web/src/combat.ts` – reine Logik, ohne DOM

```ts
export interface CombatAttacker { card: CardSnap; blockers: CardSnap[] }
export interface CombatGroup {
  key: string;          // "p:3" | "c:214" | "none"
  label: string;        // "Tom" | "Teferi" | "Angriff"
  sub?: string;         // bei Kartenzielen: "bei Tom"
  attackers: CombatAttacker[];
}
export function combatGroups(state: Snapshot): CombatGroup[];

export type CombatTag = { kind: "atk" | "blk"; label: string; title: string };
export function combatTag(state: Snapshot, cardId: number): CombatTag | undefined;
```

- `combatGroups` gruppiert nach Ziel, Reihenfolge der Gruppen nach dem ersten Vorkommen in `combat`.
- `combatTag` liefert für einen Angreifer `{ kind: "atk", label: "→ Tom", title: "greift Tom an" }`,
  für einen Blocker `{ kind: "blk", label: "blockt Krenko", title: "blockt Krenko, the Mob Boss" }`.
  Der Kartenname im Label wird bei mehr als 14 Zeichen auf 13 Zeichen plus „…" gekürzt, der Tooltip
  trägt den vollen Namen. Blockt eine Kreatur mehrere Angreifer (Verband), nennt das Label den ersten
  und hängt „+N" an.
- Beide Funktionen fallen auf `undefined` bzw. eine leere Liste zurück, wenn `state.combat` fehlt.

### 3.2 `web/src/components/Combat.tsx`

Panel in der bestehenden `.side`-Spalte **über** dem Stack, in Tisch- und Zuschauersicht dieselbe Stelle
(`Table.tsx` rendert `.side` in beiden Zweigen). Sichtbar nur, wenn `combatGroups` nicht leer ist.

- Titel „Kampf" mit Zähler = Anzahl Angreifer, im Stil des bestehenden `.panel-title` mit `.count`.
- Je Gruppe eine Überschrift: „Angriff auf **Tom**" bzw. bei Karten „Angriff auf **Teferi** · bei Tom" –
  dieselbe Form für beide Zielarten, damit die Überschriften untereinander gleich anfangen.
- Je Angreifer eine Zeile: Miniaturbild (`CardImage`, wie `.stack-thumb`), Name, P/T. Darunter eingerückt
  die Blocker in derselben Form, kleiner; ohne Blocker steht dort die Marke „ungeblockt".
- Hover setzt `setHover(card.id)` wie auf dem Brett (Detail-Panel), Verlassen `setHover(undefined)`.
- Klick (auch Rechtsklick) sendet `{ type: "selectCard", id, alt, seq }` – dieselbe Nachricht wie ein
  Klick auf dem Brett, damit während der Blockzuteilung aus der Liste heraus gewählt werden kann.
- Karten ohne Bild zeigen den Namen im Thumbnail, wie `Pile` es bereits tut.

### 3.3 Marken auf den Karten (`CardBox.tsx`)

Die heutige Zeile

```tsx
{(card.attacking || card.blocking) && (
  <div className={"tag combat-tag " + (card.attacking ? "atk" : "blk")}>{card.attacking ? "Angriff" : "Block"}</div>
)}
```

nutzt künftig `combatTag`: Label und Tooltip kommen aus der reinen Funktion, die Klassen bleiben
(`combat-tag atk` / `blk`). Fehlt `state.combat` (ältere Bridge, Fixtures ohne Kampfdaten), greift der
bisherige Text „Angriff" / „Block" als Rückfall – die Marke verschwindet nie.

## 4. Grenzfälle

- **Unbekannte Id:** Karten, die nicht in `state.cards` stehen, werden übersprungen; ein Angreifer ohne
  auflösbare Karte erzeugt keine Zeile, ein unbekannter Blocker fehlt nur in seiner Zeile.
- **Ziel nicht auflösbar** (beide Felder null oder Spieler/Karte unbekannt): Gruppe `key: "none"` mit der
  Überschrift „Angriff" – die Angreifer verschwinden nicht.
- **Verband (Banding):** Forge führt Blocker je Band; dieselben Blocker erscheinen dann bei jedem Angreifer
  des Verbands. Bewusst so belassen – Banding ist selten genug, um dafür keine Bandlogik zu bauen.
- **Kampfende:** Forge setzt `CombatView` zurück, `combat` fehlt im nächsten Snapshot, Panel und Marken
  verschwinden von selbst.
- **Zuschauer:** dieselbe Anzeige; die Kampfdaten sind keine verdeckte Information.

## 5. Tests

- **Java (`bridge/src/test/java/mtgplayer/protocol/`)**: Test, der eine Forge-Partie in den Kampf bringt
  (Angreifer und Blocker gesetzt), `StateSerializer.snapshot` aufruft und prüft: eine Zeile je Angreifer,
  `defenderPlayer` zeigt auf den verteidigenden Sitz, `blockers` enthält den zugeteilten Blocker, und ohne
  Kampf ist `combat` null. Baut auf dem Szenen-Rahmen der bestehenden Tests unter `mtgplayer/ai` auf.
- **TypeScript (`web/src/combat.test.ts`)**: Gruppierung nach Spieler und nach Karte, Angreifer ohne Ziel,
  unbekannte Kartenid, Blockerzuordnung, Markentexte samt Kürzung und „+N" bei Mehrfachblock, Rückfall
  ohne `combat`.
- **Screenshot:** `web/fixtures/table.json` (steht bereits in `COMBAT_DECLARE_BLOCKERS`) und
  `web/fixtures/spectator.json` um `combat` erweitern, dann `node scripts/shot.mjs` laufen lassen und das
  Bild ansehen – Panelhöhe, Lesbarkeit der Marken, kein Überlauf der Seitenspalte.

## 6. Nicht enthalten

- Schadensrechnung („wieviel kommt durch", „was stirbt") – abgewählt.
- Pfeile/Linien über dem Tisch – mögliche zweite Runde.
- Änderungen an der Kampfeingabe selbst (Angreifer/Blocker erklären läuft weiter über das Brett und die
  Prompt-Leiste).
