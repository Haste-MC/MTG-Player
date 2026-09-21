import type { AiPick } from "./protocol";
import { DEFAULT_AI } from "./lobbyPayload";

/** localStorage-Schlüssel für die zuletzt gewählten KI-Einstellungen der Lobby. */
const KEY = "mtg.lobby.ai";

const DEFAULTS: AiSettings = { picks: [], timeout: 5, bestOf: 0 };

const VALID_MODES = new Set<AiPick["mode"]>(["standard", "hybrid", "sim"]);
const VALID_BEST_OF: readonly AiSettings["bestOf"][] = [0, 3, 5, 7];

/** bestOf: Laenge der Serie ("Best of n"), 0 = keine Serie (Default). */
export interface AiSettings { picks: AiPick[]; timeout: number; bestOf: 0 | 3 | 5 | 7 }

/**
 * Lädt die zuletzt gespeicherte KI-Auswahl aus dem Storage - rein testbar mit injiziertem Storage.
 * Fehlendes/kaputtes/unlesbares Storage sowie ein ungültiger Wert je Feld fallen einzeln auf die
 * Defaults zurück (leere picks, Timeout 5, bestOf 0); ein Profilname, den es laut "profiles" nicht (mehr) gibt,
 * wird zu "Default".
 *
 * Nimmt einen Getter statt des Storage-Objekts direkt entgegen: schon der bloße Zugriff auf den
 * Bezeichner `localStorage` kann werfen, wenn der Storage vom Browser blockiert ist (nicht erst ein
 * Methodenaufruf darauf) - mit einem Getter passiert dieser Zugriff hier drin im try/catch, statt beim
 * Aufrufer außerhalb jedes try/catch.
 */
export function loadAiSettings(getStorage: () => Pick<Storage, "getItem">, profiles: string[]): AiSettings {
  try {
    const raw = getStorage().getItem(KEY);
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

    const bestOf = VALID_BEST_OF.find((n) => n === parsed.bestOf) ?? DEFAULTS.bestOf;

    return { picks, timeout, bestOf };
  } catch {
    return DEFAULTS;
  }
}

/** Speichert die aktuelle KI-Auswahl - best effort, ein volles/deaktiviertes Storage darf nicht crashen.
 *  Getter statt Storage-Objekt direkt, aus demselben Grund wie bei {@link loadAiSettings}. */
export function saveAiSettings(getStorage: () => Pick<Storage, "setItem">, s: AiSettings): void {
  try {
    getStorage().setItem(KEY, JSON.stringify(s));
  } catch {
    // Speichern ist best effort - siehe Kommentar oben.
  }
}

/**
 * Reine Logik hinter dem Wiederherstellen der KI-Slots beim Laden (aus Lobby.tsx gezogen, damit sie
 * ohne React testbar ist). `loaded` sind die gespeicherten Picks (`AiSettings.picks`), deren Länge die
 * gespeicherte Slot-Zahl ist. `bounds` sind die aktuell gültigen Grenzen (1–5 normal, 2–6 im
 * Zuschauermodus, siehe Lobby.tsx `minAis`/`maxAis`). Ergebnis: so viele Slots wie gespeichert, auf
 * die Grenzen geklemmt; fehlende Picks (bei mehr Slots als gespeichert) werden mit DEFAULT_AI aufgefüllt.
 */
export function restoreSlots(loaded: AiPick[], bounds: { min: number; max: number }): AiPick[] {
  const count = Math.min(bounds.max, Math.max(bounds.min, loaded.length));
  return Array.from({ length: count }, (_, i) => loaded[i] ?? DEFAULT_AI);
}
