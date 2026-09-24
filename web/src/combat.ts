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

/** Ziel einer Angriffszeile als (key, label, sub) - nicht aufloesbar ergibt die Sammelgruppe "none". */
function target(state: Snapshot, a: AttackSnap): { key: string; label: string; sub?: string } {
  const pn = playerName(state, a.defenderPlayer);
  if (pn !== undefined) return { key: "p:" + a.defenderPlayer, label: pn };
  const c = cardOf(state, a.defenderCard);
  if (c) {
    const owner = playerName(state, c.controller);
    return { key: "c:" + c.id, label: c.name ?? "Karte", sub: owner ? "bei " + owner : undefined };
  }
  return { key: "none", label: "Angriff" };
}

/** Angreifer nach Ziel gruppiert, Reihenfolge nach dem ersten Vorkommen in state.combat.
 *  Karten, die nicht im Snapshot stehen, werden uebersprungen. */
export function combatGroups(state: Snapshot): CombatGroup[] {
  const groups: CombatGroup[] = [];
  const byKey = new Map<string, CombatGroup>();
  for (const a of state.combat ?? []) {
    const card = cardOf(state, a.attacker);
    if (!card) continue;
    const t = target(state, a);
    let g = byKey.get(t.key);
    if (!g) {
      g = { ...t, attackers: [] };
      byKey.set(t.key, g);
      groups.push(g);
    }
    const blockers = (a.blockers ?? []).map((id) => cardOf(state, id)).filter((c): c is CardSnap => !!c);
    g.attackers.push({ card, blockers });
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
