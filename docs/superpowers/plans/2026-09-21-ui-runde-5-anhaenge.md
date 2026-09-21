# UI-Runde 5 – Anhänge (Auren/Equipment) und Grab-Popup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Angelegte Auren/Equipment liegen sichtbar hinter ihrem Wirt (nach oben herausschauend, Tabletop-Stil), Flüche auf Spielern stehen als Chip im Panelkopf, und die aufgeklappte Grab-/Exil-Liste bleibt immer im Bild.

**Architecture:** Bridge liefert zusätzlich `attachedToPlayer`. `groups.ts` bekommt die reine Zuordnung `attachedBy` (Wirt → Anhänge), `boardSize.ts` rechnet je Reihe einen Höhenzuschlag (`headroom`). `PlayerZone` filtert Anhänge aus den Reihen und reicht sie dem Wirt-`CardBox` als `attached`; `CardBox` rendert sie als `.attach-layer` hinter der Karte (Muster der Tabletop-Stapel). `Pile` wählt die Öffnungsseite per Viewport-Position. Spec: `docs/superpowers/specs/2026-09-21-ui-runde-5-anhaenge-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5), React 18, TypeScript, Vite, Vitest, Playwright (headless Chromium), CSS-Variablen.

## Global Constraints

- Branch `feature/ui-runde-5-anhaenge` (existiert, Spec ist committet). Commit-Messages deutsch, Kleinschreibung, Präfix `ui:`/`bridge:`/`test:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` – keine weiteren Co-Authors.
- Kevins laufende Bridge (mvn exec:java, Ports 8080/8081) **nicht** anfassen; nie `mvn clean` im Bridge-Ordner; nur ein Maven-Prozess gleichzeitig (Timeout 600000 ms). Bridge-Tests: `cd bridge && mvn -q test -Dtest=<Klasse>`.
- Frontend-Suiten grün: `cd web && npm test`, `npm run build`, `npm run layout-check -- <url> fixtures/<f>.json` für **alle** Fixtures (`table`, `table-1v1`, `table-cmd-cast`, `table-attach`, `spectator`, `spectator-heavy`, `spectator-rows`, `spectator-attach`) bei `VIEWPORT=1280x720`, `1600x900`, `1920x1080`.
- Screenshots: Vite-Dev-Server `cd web && npm run dev` (Port 5173, proxyt `/img` auf Kevins Bridge 8080) im Hintergrund, URL `http://127.0.0.1:5173`; läuft 5173 schon (curl 200), den nutzen. Fällt 8080 aus (Bilder fehlen), ist das kein Fehler des Layouts – notieren. Dateien unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/anhaenge/`, jede mit Read ansehen und Befund in den Report schreiben.
- Maße: `--attach-dy = calc(var(--h) * 0.22)` (Versatz nach oben je Ebene, gemessene Panels), `--attach-dx = calc(var(--w) * 0.28)` (seitlich, kompakte Gegnerzeile), max. 4 Ebenen (`MAX_LAYERS` wie Stapel), ab der fünften Ebene Tag „+N" auf der vierten. Headroom je Reihe = `min(n, 4) * 0.22 * 1.4` Karteneinheiten.
- Kein Kartenname und kein Projektbezug in Forge-Code (hier nicht betroffen: der Fork bleibt unverändert).

---

### Task 1: Bridge – `attachedToPlayer` im Snapshot, Protokoll im Web

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/protocol/Snapshot.java` (Record `CardSnap`, `hidden`)
- Modify: `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java:114-157` (`cardSnap`)
- Test: `bridge/src/test/java/mtgplayer/protocol/StateSerializerTest.java`
- Modify: `web/src/protocol.ts` (`CardSnap`)

**Interfaces:**
- Produces: `CardSnap.attachedToPlayer: Integer` (Java) / `attachedToPlayer?: number` (TS) – Spieler-Id, nur gesetzt, wenn die Karte einen Spieler verzaubert (`CardView.getEnchantedPlayer()`), sonst `null`/fehlt.

- [ ] **Step 1: Failing Test** – in `StateSerializerTest` ein Feld `private static Card myCurse;` und im `@BeforeAll` nach `myEffect` anlegen:

```java
        // Fluch auf dem Gegner: Aura mit "Enchant player", per attachToEntity(..., overwrite=true) direkt
        // angelegt (kein canBeAttached-Check, der ohne SpellAbility mit NPE scheitern kann).
        forge.item.IPaperCard cursePaper = forge.model.FModel.getMagicDb().getCommonCards().getCard("Curse of the Bloody Tome");
        if (cursePaper == null) {
            forge.StaticData.instance().attemptToLoadCard("Curse of the Bloody Tome");
            cursePaper = forge.model.FModel.getMagicDb().getCommonCards().getCard("Curse of the Bloody Tome");
        }
        myCurse = Card.fromPaperCard(cursePaper, me);
        me.getZone(ZoneType.Battlefield).add(myCurse);
        myCurse.attachToEntity(foe, null, true);
```

und den Test:

```java
    @Test
    void fluchTraegtAttachedToPlayerNormaleKarteNicht() {
        Snapshot s = StateSerializer.snapshot(game.getView(), ViewContext.plain(me.getView()));
        Snapshot.CardSnap curse = s.cards().get(myCurse.getId());
        assertEquals(foe.getId(), curse.attachedToPlayer());
        assertNull(curse.attachedTo());
        assertNull(s.cards().get(myPermanent.getId()).attachedToPlayer());
    }
```

(`assertNull` statisch importieren, falls noch nicht.)

- [ ] **Step 2: Test läuft rot** – `cd bridge && mvn -q test -Dtest=StateSerializerTest` → Kompilierfehler `attachedToPlayer()`.

- [ ] **Step 3: Implementieren** – `Snapshot.CardSnap`: nach `List<Integer> attachments,` die Komponente

```java
            /** Id des verzauberten Spielers (CardView.getEnchantedPlayer), sonst {@code null}. */
            Integer attachedToPlayer,
```

`hidden(...)`: ein weiteres `null` an der passenden Stelle (Reihenfolge der Komponenten!). `StateSerializer.cardSnap`: nach `attachments.isEmpty() ? null : attachments,` die Zeile

```java
                cv.getEnchantedPlayer() == null ? null : cv.getEnchantedPlayer().getId(),
```

Alle anderen `new Snapshot.CardSnap(` -Aufrufe im Bridge-Code suchen (`grep -rn "new Snapshot.CardSnap\|new CardSnap" bridge/src`) und anpassen.

- [ ] **Step 4: Test grün** – `mvn -q test -Dtest=StateSerializerTest` → alle grün. Danach die Klassen, die `CardSnap` konstruieren, mit `mvn -q test -Dtest='StateSerializer*,Snapshot*'` (falls vorhanden) prüfen.

- [ ] **Step 5: Web-Protokoll** – `web/src/protocol.ts` nach `attachments?: number[];`:

```ts
  /** Id des verzauberten Spielers (Fluch u. Ä.), sonst fehlt das Feld. */
  attachedToPlayer?: number;
```

`cd web && npm run build` grün.

- [ ] **Step 6: Commit** – `git add bridge/src web/src/protocol.ts && git commit -m "bridge: attachedToPlayer im cardsnap (flueche auf spielern)"` mit Trailer.

---

### Task 2: `groups.ts` `attachedBy`, `boardSize.ts` `headroom`

**Files:**
- Modify: `web/src/groups.ts`
- Modify: `web/src/boardSize.ts`
- Test: `web/src/groups.test.ts`, `web/src/boardSize.test.ts`

**Interfaces:**
- Produces:
  ```ts
  // groups.ts
  export function attachedBy(cards: Record<string, CardSnap>): Map<number, CardSnap[]>
  // boardSize.ts
  export interface RowSpec { units: number[]; scale: number; headroom?: number }
  export function slotHeadroom(attachedCount: number): number   // min(n, 4) * 0.22 * 1.4
  ```

- [ ] **Step 1: Failing Tests** – `groups.test.ts` anhängen:

```ts
import { attachedBy } from "./groups";

describe("attachedBy", () => {
  const bf = (id: number, extra: Partial<CardSnap> = {}): CardSnap => ({ id, faceDown: false, name: "C" + id, zone: "battlefield", ...extra });
  it("ordnet anhaenge dem wirt in der reihenfolge seiner attachments-liste zu", () => {
    const cards = {
      "1": bf(1, { attachments: [3, 2] }),
      "2": bf(2, { attachedTo: 1 }),
      "3": bf(3, { attachedTo: 1 }),
    };
    const m = attachedBy(cards);
    expect(m.get(1)?.map((c) => c.id)).toEqual([3, 2]);
  });
  it("haengt anhaenge ohne eintrag in der liste hinten an, nach id", () => {
    const cards = { "1": bf(1, { attachments: [4] }), "5": bf(5, { attachedTo: 1 }), "4": bf(4, { attachedTo: 1 }), "2": bf(2, { attachedTo: 1 }) };
    expect(attachedBy(cards).get(1)?.map((c) => c.id)).toEqual([4, 2, 5]);
  });
  it("fremde aura auf eigener kreatur gehoert zum wirt", () => {
    const cards = { "1": bf(1, { controller: 1, attachments: [2] }), "2": bf(2, { controller: 2, attachedTo: 1 }) };
    expect(attachedBy(cards).get(1)?.map((c) => c.id)).toEqual([2]);
  });
  it("wirt verdeckt, unbekannt oder nicht im spiel: keine zuordnung", () => {
    const cards = {
      "1": bf(1, { faceDown: true }), "2": bf(2, { attachedTo: 1 }),
      "3": bf(3, { attachedTo: 99 }),
      "4": bf(4, { zone: "graveyard" }), "5": bf(5, { attachedTo: 4 }),
    };
    expect(attachedBy(cards).size).toBe(0);
  });
});
```

`boardSize.test.ts` anhängen:

```ts
import { slotHeadroom } from "./boardSize";

describe("headroom", () => {
  it("slotHeadroom: 0.22 * 1.4 je anhang, gedeckelt bei 4", () => {
    expect(slotHeadroom(0)).toBe(0);
    expect(slotHeadroom(1)).toBeCloseTo(0.308);
    expect(slotHeadroom(6)).toBeCloseTo(4 * 0.308);
  });
  it("eine reihe mit headroom braucht bei gleicher hoehe eine kleinere kartenbreite", () => {
    const ohne = fitCardWidth(600, 200, [{ units: [1, 1], scale: 1 }]);
    const mit = fitCardWidth(600, 200, [{ units: [1, 1], scale: 1, headroom: slotHeadroom(2) }]);
    expect(mit).toBeLessThan(ohne);
    // 200 = 1.4 * w * (1 + 0.616) -> w = 88
    expect(mit).toBe(88);
  });
});
```

(Falls `fitCardWidth` in der Testdatei noch nicht importiert ist, Import ergänzen.)

- [ ] **Step 2: Tests rot** – `cd web && npm test` → `attachedBy`/`slotHeadroom` nicht exportiert.

- [ ] **Step 3: Implementieren** – `groups.ts` ans Ende:

```ts
/**
 * Wirt-Id -> angelegte Karten (Auren, Equipment, Befestigungen), in der Reihenfolge der
 * attachments-Liste des Wirts; Anhaenge ohne Listeneintrag hinten, nach id. Zugeordnet wird nur, wenn
 * der Wirt bekannt, offen und im Spiel ist - sonst bleibt der Anhang in seiner eigenen Reihe.
 * Der Kontrolleur spielt keine Rolle: eine gegnerische Aura auf der eigenen Kreatur liegt beim Wirt.
 */
export function attachedBy(cards: Record<string, CardSnap>): Map<number, CardSnap[]> {
  const out = new Map<number, CardSnap[]>();
  for (const c of Object.values(cards)) {
    if (c.attachedTo === undefined) continue;
    const host = cards[String(c.attachedTo)];
    if (!host || host.faceDown || host.zone?.toLowerCase() !== "battlefield") continue;
    let list = out.get(host.id);
    if (!list) { list = []; out.set(host.id, list); }
    list.push(c);
  }
  for (const [hostId, list] of out) {
    const order = cards[String(hostId)].attachments ?? [];
    const rank = (c: CardSnap) => { const i = order.indexOf(c.id); return i < 0 ? order.length : i; };
    list.sort((a, b) => rank(a) - rank(b) || a.id - b.id);
  }
  return out;
}
```

`boardSize.ts`: `RowSpec` um `headroom?: number` erweitern (Doc: „Höhenzuschlag der Reihe in Karteneinheiten, bezogen auf w * scale – für nach oben herausschauende Anhänge"), Konstante `const ATTACH_DY = 0.22;`, Export

```ts
/** Hoehenzuschlag (in Karteneinheiten, bezogen auf w * scale) fuer einen Wirt mit n Anhaengen: jede der
 *  max. 4 Ebenen schaut um ATTACH_DY der Kartenhoehe (RATIO * w) nach oben heraus. */
export function slotHeadroom(attachedCount: number): number {
  return Math.min(attachedCount, MAX_LAYERS) * ATTACH_DY * RATIO;
}
```

und in `fits()`: `const lineH = RATIO * w * r.scale + (r.headroom ?? 0) * w * r.scale;` (konservativ: jede umgebrochene Zeile der Reihe bekommt den Zuschlag). `useBoardSize`-Signatur `sig` um `r.headroom` ergänzen: `r.scale + ":" + (r.headroom ?? 0) + ":" + r.units.join(",")`.

- [ ] **Step 4: Tests grün** – `npm test`.

- [ ] **Step 5: Commit** – `git commit -m "ui: attachedBy (wirt -> anhaenge) und headroom in der reihenmessung"` mit Trailer.

---

### Task 3: `CardBox` Anhang-Ebenen, `PlayerZone`-Verdrahtung, Spieler-Chips, Fixtures, Screenshots

**Files:**
- Modify: `web/src/components/CardBox.tsx`
- Modify: `web/src/components/PlayerZone.tsx`
- Modify: `web/src/styles.css`
- Create: `web/fixtures/table-attach.json`, `web/fixtures/spectator-attach.json`

**Interfaces:**
- Consumes: `attachedBy`, `slotHeadroom`, `RowSpec.headroom` aus Task 2; `attachedToPlayer` aus Task 1.
- Produces: `CardBox` Prop `attached?: CardSnap[]`; CSS-Klassen `.attach-layers`, `.attach-layer`, `.card-slot.has-attach`, `--attach-n`, `--attach-dy`, `--attach-dx`; Chip `.effect-chip.aura`.

- [ ] **Step 1: Fixtures** – `table-attach.json` = Kopie von `table.json` mit diesen Änderungen in `cards` (Ids frei, Bilder über vorhandene `imageKey`-Muster `c:<Name>|<Set>|1`):
  - 211 Managorger Hydra (eigen): `attachments: [220, 221, 222]`; 220 `Rancor` (Enchantment — Aura, controller 1, `attachedTo: 211`), 221 `Pacifism` (Enchantment — Aura, **controller 2**, owner 2, `attachedTo: 211`) – trotzdem in `players[0].battlefield`? Nein: Forge führt die Aura im Spielfeld des Kontrolleurs → in `players[1].battlefield` eintragen; 222 `Lightning Greaves` (Artifact — Equipment, controller 1, `attachedTo: 211`) in `players[0].battlefield`.
  - 210 Llanowar Elves (eigen, getappt): `attachments: [223]`; 223 `Utopia Sprawl`? (Aura auf Land – nein) → `Blanchwood Armor` (Aura, controller 1, `attachedTo: 210`).
  - 304 Llanowar Elves (KI 1): `attachments: [321]`; 321 `Swiftfoot Boots` (Equipment, controller 2, `attachedTo: 304`).
  - Fluch: 230 `Curse of the Bloody Tome` (Aura, controller 3, owner 3, `attachedToPlayer: 1`) in `players[2].battlefield`.
  - Alle neuen Karten: `zone: "battlefield"`, `faceDown: false`, `typeLine`, `text` (kurz), `manaCost`.
  `spectator-attach.json` = Kopie von `spectator-rows.json`; im Panel des **ersten** Spielers (linke Spalte) dieselben Fälle (eine Kreatur mit 3 Anhängen, eine getappte mit 1) plus ein Fluch auf diesem Spieler von einem anderen; zusätzlich muss dieser Spieler mindestens 3 Karten im Friedhof haben (für das Popup in Task 4).

- [ ] **Step 2: `CardBox`** – Prop `attached?: CardSnap[]`. Nach `layers`:

```tsx
  // Anhaenge (Auren/Equipment, groups.ts attachedBy): bis zu 4 Ebenen hinter dem Wirt, jede um
  // --attach-dy weiter nach oben versetzt (kompakte Gegnerzeile: seitlich, siehe CSS). Jede Ebene ist
  // eine eigene Karte: hover-/klickbar, mit eigenem waehlbar/spielbar-Rahmen.
  const att = attached ?? [];
  const attLayers = att.slice(0, 4);
  const attMore = att.length - attLayers.length;
```

Im Slot-`className` zusätzlich `(att.length > 0 ? " has-attach" : "")`, im Slot-`style` zusätzlich `"--attach-n": attLayers.length` (Style-Objekt zusammenführen: `style={{ ...(layers > 0 ? { "--layers": layers } : {}), ...(attLayers.length > 0 ? { "--attach-n": attLayers.length } : {}) } as CSSProperties}`; bei leerem Objekt `undefined` übergeben ist nicht nötig).
Im `.card-frame` (Klasse zusätzlich `attached` wenn `att.length > 0`), **vor** `.stack-layers`:

```tsx
        {attLayers.length > 0 && (
          <div className="attach-layers">
            {attLayers.map((a, i) => (
              <div key={a.id}
                className={"attach-layer" + (a.selectable ? " selectable" : "") + (a.actionable ? " actionable" : "") + (a.highlighted ? " highlighted" : "")}
                style={{ "--k": attLayers.length - i } as CSSProperties}
                title={a.text ?? ""}
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: a.id, alt: e.button === 2, seq }); }}
                onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: a.id, alt: true, seq }); }}
                onMouseEnter={() => setHover(a.id)} onMouseLeave={() => setHover(undefined)}>
                {a.imageKey && <CardImage key={a.imageKey} imageKey={a.imageKey} className="art" />}
                <span className="attach-name">{a.name}</span>
                {i === attLayers.length - 1 && attMore > 0 && <span className="tag attach-more">+{attMore}</span>}
              </div>
            ))}
          </div>
        )}
```

Reihenfolge: `attLayers[0]` ist der erste Anhang (liegt direkt hinter dem Wirt, `--k` = n, schaut am **wenigsten** heraus? – nein: Spec „der erste Anhang liegt direkt hinter dem Wirt und schaut am wenigsten heraus" → `--k` = i + 1 für Versatz, aber z-index absteigend, damit der erste oben liegt). Also: `style={{ "--k": i + 1, zIndex: attLayers.length - i }}`. Der Name `.attach-name` ist nur im Textfallback sichtbar (`.attach-layer:has(.art) .attach-name { display: none }`).

- [ ] **Step 3: CSS** (nach dem Stapel-Block, ca. Zeile 410):

```css
/* Anhaenge (Auren/Equipment, groups.ts attachedBy): aufrechte Ebenen hinter dem Wirt, Ebene k um
   k * --attach-dy nach oben versetzt; der Slot bekommt oben Rand in gleicher Hoehe, damit die Ebenen
   die Reihe darueber nicht ueberlappen und nicht vom .bf-rows-Scrollkasten beschnitten werden. Ebenen
   sind eigene Karten (Hover/Klick), deshalb pointer-events: auto (anders als .stack-layers). */
.card-frame.attached { isolation: isolate; }
.attach-layers { position: absolute; left: 50%; bottom: 0; width: var(--w); height: var(--h); translate: -50% 0; z-index: -1; }
.attach-layer { position: absolute; inset: 0; border-radius: 7px; background: #0c1013; border: 1px solid var(--line-2); box-shadow: var(--shadow-card); overflow: hidden; cursor: pointer;
  --attach-dy: calc(var(--h) * 0.22); transform: translateY(calc(var(--k) * -1 * var(--attach-dy))); transition: transform .15s ease; }
.attach-layer .art { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; filter: brightness(0.9); }
.attach-layer:hover { transform: translateY(calc(var(--k) * -1 * var(--attach-dy) - 3px)); z-index: 3; }
.attach-layer:hover .art { filter: none; }
.attach-layer .attach-name { position: absolute; left: 4px; right: 4px; top: 3px; font-size: 9px; font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.attach-layer:has(.art) .attach-name { display: none; }
.attach-layer.selectable { box-shadow: 0 0 0 2px var(--gold), 0 0 12px 2px rgba(242, 193, 78, 0.45); }
.attach-layer.actionable { box-shadow: 0 0 0 2px var(--green), 0 0 12px 2px rgba(62, 207, 142, 0.4); }
.attach-layer.highlighted { box-shadow: 0 0 0 2px var(--blue); }
.attach-layer .tag.attach-more { top: 3px; right: 3px; left: auto; }
.card-slot.has-attach { --attach-dy: calc(var(--h) * 0.22); margin-top: calc(var(--attach-n, 0) * var(--attach-dy)); }
/* Getappter Wirt: der Rahmen ist --h breit und --w hoch; die Ebenen bleiben aufrecht und an der Unterkante
   ausgerichtet (bottom: 0), der Wirt dreht sich allein. */
.bf-rows .card-frame.tapped .attach-layers { bottom: 0; }
/* Kompakte Gegnerzeile: feste Zeilenhoehe, horizontales Scrollen - Ebenen schauen seitlich rechts heraus. */
.player.compact:not(.spectator) .card-slot.has-attach { --attach-dx: calc(var(--w) * 0.28); margin-top: 0; width: calc(var(--w) + var(--attach-n, 0) * var(--attach-dx)); justify-content: flex-start; }
.player.compact:not(.spectator) .attach-layer { transform: translateX(calc(var(--k) * var(--attach-dx, calc(var(--w) * 0.28)))); }
.player.compact:not(.spectator) .attach-layer:hover { transform: translateX(calc(var(--k) * var(--attach-dx, calc(var(--w) * 0.28)))) translateY(-2px); }
```

Getappter Wirt in der Gegnerzeile (aufrecht, abgedunkelt) braucht keine Sonderregel. `.effect-chip.aura`:

```css
.effect-chip.aura { color: #ffd2a8; border-color: rgba(255, 170, 100, 0.4); background: rgba(255, 170, 100, 0.1); display: inline-flex; align-items: center; gap: 6px; padding-left: 4px; }
.effect-chip.aura:hover { background: rgba(255, 170, 100, 0.2); border-color: rgba(255, 170, 100, 0.65); }
.effect-chip.aura .mark { font-size: 8.5px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; padding: 0 5px; border-radius: 999px; background: rgba(255, 170, 100, 0.25); color: #ffe9d6; line-height: 1.5; }
```

- [ ] **Step 4: `PlayerZone`** –
  - `const hosted = useMemo(() => attachedBy(state.cards), [state.cards]);` (Import aus `../groups`), `const isHosted = (c: CardSnap) => c.attachedTo !== undefined && hosted.has(c.attachedTo) || c.attachedToPlayer !== undefined;`
  - `const bf = cards(p.battlefield).filter((c) => !isChip(c) && !isHosted(c));`
  - `Stacks` bekommt `hosted` durch: `<CardBox key=… card={g.card} stack=… attached={hosted.get(g.card.id)} />` (Signatur `Stacks({ groups, hosted })`).
  - `rowSpecs`: je Reihe `headroom: Math.max(0, ...groups.map((g) => g.card.tapped ? 0 : slotHeadroom(hosted.get(g.card.id)?.length ?? 0)))` – getappte Wirte: Ebenen bleiben aufrecht und an der Unterkante des `--w` hohen Rahmens; sie ragen dann um `(--h - --w) + n*dy` über den Rahmen → Headroom für getappte Wirte = `(RATIO - 1) + n * 0.22 * RATIO`; dafür in `boardSize.ts` `slotHeadroom(n, tapped = false)` erweitern: `const base = tapped ? RATIO - 1 : 0; return base + Math.min(n, 4) * ATTACH_DY * RATIO;` (Test in Task 2 entsprechend um `slotHeadroom(1, true) ≈ 0.708` ergänzen) und das CSS `.card-slot.has-attach.tapped-slot { margin-top: calc(var(--attach-n) * var(--attach-dy) + (var(--h) - var(--w))); }` – im `.bf-rows`-Bereich.
  - Spieler-Chips: `const auras = Object.values(state.cards).filter((c) => c.attachedToPlayer === p.id);` und im Kopf nach den Badges (`{cmdDmg && …}`):

```tsx
          {auras.map((c) => {
            const by = state.players.find((q) => q.id === c.controller)?.name;
            return (
              <button key={c.id} type="button" className="effect-chip aura" title={(c.text ?? "") + (by ? `\nAura von ${by}` : "")}
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: c.id, alt: e.button === 2, seq }); }}
                onMouseEnter={() => setHover(c.id)} onMouseLeave={() => setHover(undefined)}>
                <span className="mark">Aura</span>{c.name}
              </button>
            );
          })}
```

(`setHover` aus dem Store holen wie in `Effects`; `send` importieren.)

- [ ] **Step 5: Build + Tests** – `npm test`, `npm run build` grün.

- [ ] **Step 6: Screenshots** – Dev-Server prüfen/starten (siehe Global Constraints), dann aus `web/`:
  `VIEWPORT=1600x900 node scripts/shot.mjs http://127.0.0.1:5173 <scratch>/anhaenge/table-1600.png fixtures/table-attach.json`,
  dasselbe mit `VIEWPORT=1280x720` (`table-1280.png`), und `spectator-attach.json` bei 1600x900 und 1920x1080 (`spectator-1600.png`, `spectator-1920.png`). Jede Datei mit Read ansehen. Sollzustand: Hydra mit drei herausschauenden Kanten (Rancor, Pacifism, Greaves – Namensbalken lesbar), getappte Elves mit aufrechter Blanchwood-Kante über dem gedrehten Wirt, in der Gegnerzeile Elves mit Boots seitlich, Fluch-Chip „Aura Curse of the Bloody Tome" im eigenen Kopf; nichts überlappt die Reihe darüber, nichts ragt aus dem Panel. Bei Abweichungen CSS nachbessern, erneut schießen.

- [ ] **Step 7: Layout-Check** – alle Fixtures × 3 Viewports (Global Constraints), Ausgabe im Report. Erwartung: 0 Probleme (Regel 3 prüft `.card`; Anhang-Ebenen kommen in Task 4 dazu).

- [ ] **Step 8: Commit** – `git commit -m "ui: auren/equipment als ebenen hinter dem wirt, flueche als chip im panelkopf, fixtures"` mit Trailer.

---

### Task 4: Grab-Popup-Seite, Layout-Check-Regeln, README

**Files:**
- Modify: `web/src/components/PlayerZone.tsx` (`Pile`)
- Modify: `web/src/styles.css` (`.pile-list.open-right`)
- Modify: `web/scripts/layout-check.mjs`
- Modify: `README.md` (Abschnitt Oberfläche/Tisch: ein Satz zu Anhängen und Chips)

- [ ] **Step 1: `Pile`** –

```tsx
function Pile({ label, cards }: { label: string; cards: CardSnap[] }) {
  const top = cards[cards.length - 1];
  const [openRight, setOpenRight] = useState(false);
  // Oeffnungsseite beim Aufklappen: liegt die Vorschau in der linken Viewport-Haelfte (Zuschauer-Panels
  // der linken Spalte), oeffnet die Liste nach rechts - sonst wie bisher nach links.
  const onToggle = (e: SyntheticEvent<HTMLDetailsElement>) => {
    if (!e.currentTarget.open) return;
    const b = e.currentTarget.getBoundingClientRect();
    setOpenRight(b.left + b.width / 2 < window.innerWidth / 2);
  };
  return (
    <details className="pile" onToggle={onToggle}>
      …
      <div className={"pile-list" + (openRight ? " open-right" : "")}>
```

CSS: `.pile-list.open-right { right: auto; left: calc(100% + 14px); }`.

- [ ] **Step 2: Layout-Check** – Regel 3 zusätzlich für `.attach-layer` (Selektor um `.mine .battlefield .attach-layer, .opponents .attach-layer, .spectator-grid .attach-layer` erweitern; `name()` liest `.attach-name` mit: `el.querySelector(".name, .attach-name")?.textContent || …`). Neue Regel 12 nach Regel 9 (Pendel): für jedes Fixture, dessen Name `attach` enthält, das erste `.pile` mit Karten im ersten `.player` per Playwright klicken (`page.locator(".player .pile:has(.pile-thumb:not(.empty)) > summary").first().click()`), 300 ms warten, dann prüfen, dass `.pile-list` (sichtbar) vollständig im Viewport liegt (`left >= 0, right <= vw, top >= 0, bottom <= vh`), sonst `problems.push("Regel 12: Grab-/Exil-Liste ragt aus dem Viewport: …")`. Danach dieselbe Prüfung für das **letzte** `.player` (rechte Spalte): Liste vollständig im Viewport.

- [ ] **Step 3: Läufe** – alle Fixtures × 3 Viewports; `table-attach`/`spectator-attach` müssen mit Regel 12 grün sein. Screenshot des aufgeklappten Grabs in `spectator-attach` (linke Spalte) 1600x900: in `shot.mjs` gibt es keinen Klick – dafür einmalig `node -e`/kleines Inline-Playwright-Skript im Scratchpad (Kopie von `shot.mjs` mit `await page.locator(".player .pile > summary").first().click()` vor dem Screenshot), Datei `<scratch>/anhaenge/pile-left-1600.png`, ansehen.

- [ ] **Step 4: README** – im Abschnitt zur Tischansicht ein Absatz: „Angelegte Auren/Equipment liegen hinter ihrem Wirt und schauen oben heraus (in der Gegnerzeile seitlich); jede Kante ist hover-/klickbar. Flüche auf Spielern stehen als „Aura"-Chip im Panelkopf des verzauberten Spielers. Die Grab-/Exil-Liste öffnet zur Bildmitte hin."

- [ ] **Step 5: Tests/Build** – `npm test`, `npm run build`, Layout-Checks grün.

- [ ] **Step 6: Commit** – `git commit -m "ui: grab-liste oeffnet zur bildmitte, layout-check fuer anhang-ebenen und popup, readme"` mit Trailer.
