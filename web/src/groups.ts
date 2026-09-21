import type { CardSnap } from "./protocol";

/** Ein Stapel gleichnamiger Karten in einer Spielfeldreihe (oder eine einzelne Karte, cards.length === 1). */
export interface Group {
  /** Karte, die den Stapel zeigt und den Klick bekommt: die erste ungetappte (bevorzugt eine mit actionable,
   *  damit der Klick etwas ausloest und der gruene Rahmen stimmt), sonst die erste. */
  card: CardSnap;
  cards: CardSnap[];
  tapped: number;
}

/**
 * Nur Karten ohne jede Auszeichnung duerfen in einen Stapel: sobald eine Karte waehlbar, hervorgehoben,
 * im Kampf, verdeckt, angehaengt ist oder Marken/Anhaenge/Schaden traegt, muss sie einzeln sichtbar bleiben
 * (Klickziel und Zustand sind dann nicht mehr fuer alle gleich). "actionable" (Faehigkeit spielbar, gruener
 * Rahmen) loest nicht auf - das ist bei Laendern der Normalfall; der Stapel zeigt dann eine actionable Karte.
 */
export function isStackable(c: CardSnap): boolean {
  if (c.faceDown || !c.name) return false;
  if (c.selectable || c.highlighted || c.attacking || c.blocking) return false;
  if (c.counters && Object.values(c.counters).some((v) => v > 0)) return false;
  if (c.attachments && c.attachments.length > 0) return false;
  if (c.attachedTo !== undefined) return false;
  if (c.damage) return false;
  return true;
}

/** Stapel-Schluessel: gleicher Name und gleicher sichtbarer Zustand (Typzeile, P/T, Token). Getappt zaehlt nicht. */
function keyOf(c: CardSnap): string {
  return [c.name, c.typeLine ?? "", c.power ?? "", c.toughness ?? "", c.token ? "t" : ""].join("|");
}

/**
 * Fasst gleichnamige Karten ohne Auszeichnung zu Stapeln zusammen; die Reihenfolge folgt dem ersten
 * Auftreten. Hat eine Karte des Namens eine Auszeichnung, wird der ganze Name aufgeloest (alle einzeln),
 * damit die ausgezeichnete Karte nicht neben einem Stapel derselben Karte steht.
 */
export function groupCards(cards: CardSnap[]): Group[] {
  const blocked = new Set<string>();
  for (const c of cards) if (!isStackable(c)) blocked.add(keyOf(c));
  const out: Group[] = [];
  const byKey = new Map<string, Group>();
  for (const c of cards) {
    const key = keyOf(c);
    if (blocked.has(key) || c.faceDown || !c.name) {
      out.push({ card: c, cards: [c], tapped: c.tapped ? 1 : 0 });
      continue;
    }
    let g = byKey.get(key);
    if (!g) {
      g = { card: c, cards: [], tapped: 0 };
      byKey.set(key, g);
      out.push(g);
    }
    g.cards.push(c);
    if (c.tapped) g.tapped++;
  }
  for (const g of byKey.values()) {
    g.card = g.cards.find((c) => !c.tapped && c.actionable) ?? g.cards.find((c) => !c.tapped) ?? g.cards[0];
  }
  return out;
}

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
