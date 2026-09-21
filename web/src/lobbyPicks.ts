import { EMPTY_PICK, type Pick as DeckPick } from "./deckref";
import type { DeckInfo } from "./protocol";

/** localStorage-Schluessel fuer die zuletzt gewaehlten Decks der Lobby (eigenes und je KI-Slot). */
const KEY = "mtg.lobby.picks";

const VALID_KINDS = new Set<DeckPick["kind"]>(["precon", "saved", "text", "archidekt"]);

export interface LobbyPicks { human: DeckPick; ais: DeckPick[] }

const DEFAULTS: LobbyPicks = { human: EMPTY_PICK, ais: [] };

/** Ein gespeicherter Pick, der nicht die erwartete Form hat, faellt einzeln auf EMPTY_PICK zurueck. */
function pickOf(raw: unknown): DeckPick {
  if (!raw || typeof raw !== "object") return EMPTY_PICK;
  const { kind, value, name } = raw as { kind?: unknown; value?: unknown; name?: unknown };
  if (!VALID_KINDS.has(kind as DeckPick["kind"]) || typeof value !== "string" || typeof name !== "string") return EMPTY_PICK;
  return { kind: kind as DeckPick["kind"], value, name };
}

/**
 * Laedt die zuletzt gespeicherte Deckauswahl - Getter statt Storage-Objekt aus demselben Grund wie in
 * aiSettings.ts (schon der Zugriff auf `localStorage` kann werfen). Fehlendes/kaputtes Storage -> Default.
 */
export function loadPicks(getStorage: () => Pick<Storage, "getItem">): LobbyPicks {
  try {
    const raw = getStorage().getItem(KEY);
    if (!raw) return DEFAULTS;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return DEFAULTS;
    return {
      human: pickOf(parsed.human),
      ais: Array.isArray(parsed.ais) ? parsed.ais.map(pickOf) : DEFAULTS.ais,
    };
  } catch {
    return DEFAULTS;
  }
}

/** Speichert die Deckauswahl - best effort, ein volles/deaktiviertes Storage darf nicht crashen. */
export function savePicks(getStorage: () => Pick<Storage, "setItem">, p: LobbyPicks): void {
  try {
    getStorage().setItem(KEY, JSON.stringify(p));
  } catch {
    // Speichern ist best effort - siehe Kommentar oben.
  }
}

/** Ein precon-/saved-Pick, dessen Deck die Bridge nicht (mehr) anbietet, wird zu EMPTY_PICK;
 * text/archidekt bleiben unberuehrt. */
export function dropMissing(p: LobbyPicks, precons: DeckInfo[], decks: DeckInfo[]): LobbyPicks {
  const has = (list: DeckInfo[], name: string) => list.some((d) => d.name === name);
  const check = (pick: DeckPick): DeckPick => {
    if (pick.kind === "precon") return has(precons, pick.value) ? pick : EMPTY_PICK;
    if (pick.kind === "saved") return has(decks, pick.value) ? pick : EMPTY_PICK;
    return pick;
  };
  return { human: check(p.human), ais: p.ais.map(check) };
}
