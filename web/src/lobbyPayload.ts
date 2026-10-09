import { NO_TEAM } from "./lineup";
import type { AiPick, DeckRef, StartGame } from "./protocol";

/** Wird nicht mitgeschickt, wenn ein Gegner genau darauf steht - Forge nimmt Standard/Default selbst an. */
export const DEFAULT_AI: AiPick = { mode: "standard", profile: "Default" };

function isDefaultAi(p: AiPick): boolean {
  return p.mode === DEFAULT_AI.mode && p.profile === DEFAULT_AI.profile;
}

/**
 * Reine Übersetzung der Lobby-Auswahl in die startGame-Nachricht - testbar ohne React/Socket.
 * Menschliches Spiel: humanDeck muss aufgelöst sein, jeder KI-Slot ebenso.
 * KI-only-Zuschauer-Modus (spectate): kein humanDeck (Lobby.tsx blendet "Dein Deck" dafür aus),
 * mindestens 2 aufgelöste KI-Decks (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
 * Undefined, solange nicht spielbereit - der "Spiel starten"-Button bleibt dann disabled.
 * aiPicks/aiTimeout: KI-Modus+Profil je Slot und die KI-Bedenkzeit - Standard/Default/5 werden nicht
 * mitgeschickt (Payload bleibt für den Normalfall byte-identisch zum alten Format).
 * teams: Teamnummern in Sitzreihenfolge (Mensch zuerst, im Zuschauer-Modus nur KIs), NO_TEAM = keins.
 * Was nicht gesetzt ist, fehlt in der Nachricht ganz (kein team/humanTeam); revealPartnerHand geht nur
 * mit, wenn der menschliche Sitz in einem Team ist. Ob die Aufstellung gueltig ist, prueft lineupValid.
 */
export function buildStartGame(
  spectate: boolean,
  human: DeckRef | undefined,
  ais: (DeckRef | undefined)[],
  aiPicks: AiPick[],
  aiTimeout: number,
  teams: number[] = [],
  revealPartnerHand = false,
): StartGame | undefined {
  if (ais.some((r) => r === undefined)) return undefined;
  // Der menschliche Sitz steht in der Aufstellung vorn; im Zuschauer-Modus gibt es ihn nicht.
  const aiTeams = spectate ? teams : teams.slice(1);
  const opponents = ais.map((r, i) => {
    const pick = aiPicks[i] ?? DEFAULT_AI;
    const team = aiTeams[i] ?? NO_TEAM;
    return {
      ...(r as DeckRef),
      name: `AI ${i + 1}`,
      ...(isDefaultAi(pick) ? {} : { ai: pick }),
      ...(team !== NO_TEAM ? { team } : {}),
    };
  });
  const timeoutField = aiTimeout === 5 ? {} : { aiTimeout };

  if (spectate) {
    if (ais.length < 2) return undefined;
    return { type: "startGame", spectate: true, opponents, ...timeoutField };
  }
  if (human === undefined) return undefined;
  const humanTeam = teams[0] ?? NO_TEAM;
  return {
    type: "startGame",
    humanDeck: human,
    ...(humanTeam !== NO_TEAM ? { humanTeam } : {}),
    ...(humanTeam !== NO_TEAM && revealPartnerHand ? { revealPartnerHand: true } : {}),
    opponents,
    ...timeoutField,
  };
}
