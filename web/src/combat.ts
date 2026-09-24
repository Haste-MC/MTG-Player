import type { AttackSnap, CardSnap, Snapshot } from "./protocol";

/** Ein Angreifer mit den Karten, die ihn blocken. */
export interface CombatAttacker { card: CardSnap; blockers: CardSnap[] }

/** Alle Angreifer, die demselben Ziel gelten. key: "p:<id>" (Spieler), "c:<id>" (Karte) oder "none". */
export interface CombatGroup { key: string; label: string; sub?: string; attackers: CombatAttacker[] }

/** Marke an der Karte im Spielfeld: kurzes Label, voller Text im Tooltip. */
export interface CombatTag { kind: "atk" | "blk"; label: string; title: string }

const MAX_NAME = 14;

/** Lange Kartennamen passen nicht in die Marke; der Tooltip traegt immer den vollen Namen.
 *  trimEnd, damit bei einem Schnitt hinter einem Leerzeichen kein "Teferi, Hero …" entsteht. */
function short(name: string): string {
  return name.length > MAX_NAME ? name.slice(0, 13).trimEnd() + "…" : name;
}

const cardOf = (state: Snapshot, id: number | undefined): CardSnap | undefined =>
  id === undefined ? undefined : state.cards[String(id)];

const playerName = (state: Snapshot, id: number | undefined): string | undefined =>
  state.players.find((p) => p.id === id)?.name;

/** Sitzplatz (Index in state.players) eines Spielers, oder +Infinity wenn nicht auffindbar - so landet
 *  er beim Sortieren immer hinten statt die Reihenfolge der anderen zu verfaelschen. */
function seat(state: Snapshot, id: number | undefined): number {
  const i = id === undefined ? -1 : state.players.findIndex((p) => p.id === id);
  return i === -1 ? Number.POSITIVE_INFINITY : i;
}

/** Position einer Karte im Spielfeld ihres Beherrschers, oder +Infinity wenn nicht auffindbar (Karte
 *  ohne bekannten Controller, oder Controller ohne diese Karte im battlefield-Array). */
function battlefieldPos(state: Snapshot, card: CardSnap): number {
  const controller = state.players.find((p) => p.id === card.controller);
  const i = controller?.battlefield.indexOf(card.id) ?? -1;
  return i === -1 ? Number.POSITIVE_INFINITY : i;
}

/** Ziel einer Angriffszeile als (key, label, sub, seat) - nicht aufloesbar ergibt die Sammelgruppe
 *  "none", die beim Sortieren ans Ende faellt (seat = +Infinity). */
function target(state: Snapshot, a: AttackSnap): { key: string; label: string; sub?: string; seat: number } {
  const pn = playerName(state, a.defenderPlayer);
  if (pn !== undefined) return { key: "p:" + a.defenderPlayer, label: pn, seat: seat(state, a.defenderPlayer) };
  const c = cardOf(state, a.defenderCard);
  if (c) {
    const owner = playerName(state, c.controller);
    return { key: "c:" + c.id, label: c.name ?? "Karte", sub: owner ? "bei " + owner : undefined, seat: seat(state, c.controller) };
  }
  return { key: "none", label: "Angriff", seat: Number.POSITIVE_INFINITY };
}

/** Angreifer nach Ziel gruppiert. Forges CombatView.getAttackers() liefert ein HashSet - die Reihenfolge
 *  haengt an den Karten-Ids und springt, sobald der Set intern waechst, ist also kein verlaesslicher
 *  Takt. Deshalb wird hier fest sortiert statt der Bridge-Reihenfolge zu vertrauen: Gruppen nach der
 *  Sitzreihenfolge aus state.players (Kartenziele nach dem Sitz ihres Beherrschers, "none" ans Ende),
 *  Angreifer je Gruppe stabil nach ihrer Position im Spielfeld ihres Beherrschers (Karten ohne
 *  auffindbare Position ans Ende). Karten, die nicht im Snapshot stehen, werden uebersprungen. */
export function combatGroups(state: Snapshot): CombatGroup[] {
  const groups: CombatGroup[] = [];
  const byKey = new Map<string, CombatGroup>();
  const seatByKey = new Map<string, number>();
  for (const a of state.combat ?? []) {
    const card = cardOf(state, a.attacker);
    if (!card) continue;
    const t = target(state, a);
    let g = byKey.get(t.key);
    if (!g) {
      g = { key: t.key, label: t.label, sub: t.sub, attackers: [] };
      byKey.set(t.key, g);
      seatByKey.set(t.key, t.seat);
      groups.push(g);
    }
    const blockers = (a.blockers ?? []).map((id) => cardOf(state, id)).filter((c): c is CardSnap => !!c);
    g.attackers.push({ card, blockers });
  }
  // Bei gleichem Sitz (Spielerziel und ein Kartenziel desselben Beherrschers) braucht es einen zweiten,
  // von der Eingabe unabhaengigen Schluessel - sonst bliebe die Reihenfolge unter den Gleichstaenden am
  // Auftreten in state.combat haengen, und genau das soll hier nicht mehr passieren. Das Spielerziel
  // selbst zuerst, Kartenziele danach aufsteigend nach Karten-Id.
  const rank = (key: string) => (key.startsWith("p:") ? 0 : key.startsWith("c:") ? 1 : 2);
  const cardId = (key: string) => (key.startsWith("c:") ? Number(key.slice(2)) : 0);
  groups.sort((x, y) => seatByKey.get(x.key)! - seatByKey.get(y.key)! || rank(x.key) - rank(y.key) || cardId(x.key) - cardId(y.key));
  for (const g of groups) {
    g.attackers.sort((x, y) => battlefieldPos(state, x.card) - battlefieldPos(state, y.card));
  }
  return groups;
}

/** Marke fuer eine Karte im Spielfeld, oder undefined wenn sie nicht am Kampf beteiligt ist. */
export function combatTag(state: Snapshot, cardId: number): CombatTag | undefined {
  const combat = state.combat ?? [];
  const attack = combat.find((a) => a.attacker === cardId);
  if (attack) {
    const t = target(state, attack);
    if (t.key === "none") return { kind: "atk", label: "Angriff", title: "greift an" };
    return { kind: "atk", label: "→ " + short(t.label), title: "greift " + t.label + " an" };
  }
  const blocked = combat.filter((a) => (a.blockers ?? []).includes(cardId));
  if (blocked.length === 0) return undefined;
  const first = cardOf(state, blocked[0].attacker)?.name;
  if (!first) return { kind: "blk", label: "Block", title: "blockt" };
  const more = blocked.length - 1;
  return {
    kind: "blk",
    label: "blockt " + short(first) + (more > 0 ? " +" + more : ""),
    title: "blockt " + first + (more > 0 ? ` und ${more} weitere${more === 1 ? "n" : ""} Angreifer` : ""),
  };
}
