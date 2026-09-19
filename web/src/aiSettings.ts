import type { AiPick } from "./protocol";
import { DEFAULT_AI } from "./lobbyPayload";

/** localStorage-Schlüssel für die zuletzt gewählten KI-Einstellungen der Lobby. */
const KEY = "mtg.lobby.ai";

const DEFAULTS: AiSettings = { picks: [], timeout: 5 };

const VALID_MODES = new Set<AiPick["mode"]>(["standard", "hybrid", "sim"]);

export interface AiSettings { picks: AiPick[]; timeout: number }

/**
 * Lädt die zuletzt gespeicherte KI-Auswahl aus dem Storage - rein testbar mit injiziertem Storage.
 * Fehlendes/kaputtes/unlesbares Storage sowie ein ungültiger Wert je Feld fallen einzeln auf die
 * Defaults zurück (leere picks, Timeout 5); ein Profilname, den es laut "profiles" nicht (mehr) gibt,
 * wird zu "Default".
 */
export function loadAiSettings(storage: Pick<Storage, "getItem">, profiles: string[]): AiSettings {
  try {
    const raw = storage.getItem(KEY);
    if (!raw) return DEFAULTS;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return DEFAULTS;

    const picks: AiPick[] = Array.isArray(parsed.picks)
      ? parsed.picks.map((p: unknown): AiPick => {
          const mode = p && typeof p === "object" && VALID_MODES.has((p as { mode?: unknown }).mode as AiPick["mode"])
            ? ((p as { mode: AiPick["mode"] }).mode)
            : DEFAULT_AI.mode;
          const rawProfile = p && typeof p === "object" ? (p as { profile?: unknown }).profile : undefined;
          const profile = typeof rawProfile === "string" && profiles.includes(rawProfile) ? rawProfile : "Default";
          return { mode, profile };
        })
      : DEFAULTS.picks;

    const timeout = typeof parsed.timeout === "number" && parsed.timeout >= 1 && parsed.timeout <= 60
      ? parsed.timeout
      : DEFAULTS.timeout;

    return { picks, timeout };
  } catch {
    return DEFAULTS;
  }
}

/** Speichert die aktuelle KI-Auswahl - best effort, ein volles/deaktiviertes Storage darf nicht crashen. */
export function saveAiSettings(storage: Pick<Storage, "setItem">, s: AiSettings): void {
  try {
    storage.setItem(KEY, JSON.stringify(s));
  } catch {
    // Speichern ist best effort - siehe Kommentar oben.
  }
}
