export interface PromptSnap {
  message: string;
  card?: number;
  okLabel: string;
  cancelLabel: string;
  okEnabled: boolean;
  cancelEnabled: boolean;
  seq: number;
}

export interface CardSnap {
  id: number;
  faceDown: boolean;
  name?: string;
  imageKey?: string;
  controller?: number;
  owner?: number;
  zone?: string;
  tapped?: boolean;
  sick?: boolean;
  power?: number;
  toughness?: number;
  damage?: number;
  counters?: Record<string, number>;
  attachedTo?: number;
  attachments?: number[];
  /** Id des verzauberten Spielers (Fluch u. Ä.), sonst fehlt das Feld. */
  attachedToPlayer?: number;
  text?: string;
  typeLine?: string;
  manaCost?: string;
  attacking?: boolean;
  blocking?: boolean;
  token?: boolean;
  selectable?: boolean;
  actionable?: boolean;
  highlighted?: boolean;
  /** true nur für Commander (CardView.isCommander), sonst fehlt das Feld. */
  commander?: boolean;
  /** true nur für Forges Effekt-Hilfskarten (CardView.isImmutable, GamePieceType.EFFECT), sonst fehlt das Feld. */
  effect?: boolean;
  /** true nur für Embleme (CardView.isEmblem), sonst fehlt das Feld. */
  emblem?: boolean;
}

export interface PlayerSnap {
  id: number;
  name: string;
  isAi: boolean;
  life: number;
  counters?: Record<string, number>;
  commanderDamage: Record<string, number>;
  hand: number[];
  librarySize: number;
  graveyard: number[];
  exile: number[];
  command: number[];
  battlefield: number[];
  manaPool: Record<string, number>;
  hasPriority: boolean;
  highlighted?: boolean;
  /** true, wenn der Spieler gerade als Ziel waehlbar ist (gestrichelter Rahmen statt vollem). */
  targetable?: boolean;
}

export interface StackSnap {
  index: number;
  text: string;
  sourceCard?: number;
  controller?: number;
  targetCards: number[];
  targetPlayers: number[];
}

export interface Snapshot {
  type: "state";
  turn: number;
  phase?: string;
  activePlayer?: number;
  priorityPlayer?: number;
  me?: number;
  gameOver: boolean;
  players: PlayerSnap[];
  stack: StackSnap[];
  cards: Record<string, CardSnap>;
  stops: { own: string[]; opp: string[] };
  fullControl: boolean;
  prompt: PromptSnap;
  /** true nur im KI-only-Zuschauer-Sitz (kein "me", alle Haende sichtbar); sonst fehlt das Feld. */
  spectator?: boolean;
}

export interface Option {
  index: number;
  label: string;
  card?: number;
  player?: number;
  detail?: CardSnap;
  max?: number;
  lethal?: number;
  movable?: boolean;
}

export type ChoiceKind = "one" | "many" | "order" | "confirm" | "number" | "text" | "ability" | "entities" | "reveal" | "damage" | "amount" | "cardlist";

export interface Choice {
  type: "choice";
  id: number;
  kind: ChoiceKind;
  title: string;
  message: string;
  options: Option[];
  min: number;
  max: number;
  card?: number;
  amount?: number;
  atLeastOne?: boolean;
  flags?: string[];
}

/** Ein Deck im Lobby-Angebot (Bridge: Messages.DeckInfo). imageKey ist Forges Bildschluessel
 * ("c:Name|SET|art"); archidekt ist die Archidekt-Deck-Id eines importierten Decks, null/fehlend sonst. */
export interface DeckInfo {
  name: string;
  commanders: { name: string; imageKey?: string }[];
  archidekt?: string | null;
}
export interface Lobby {
  type: "lobby";
  precons: DeckInfo[];
  decks?: DeckInfo[];
  aiModes?: string[];
  aiProfiles?: string[];
  aiTimeout?: number;
}
// "name" bei einem Gegner-Eintrag (siehe Outbound.startGame) ist der Spielername ("KI 1") -
// das ist NICHT der Speichername eines Textdecks, dafuer gibt es "deckName".
export type DeckRef =
  | { precon: string }
  | { saved: string }
  | { text: string; deckName?: string }
  | { archidekt: string; deckName?: string };
/** KI-Wahl je Gegner-Slot - "mode" steuert Forges Entscheidungspfad, "profile" den Namen des
 * AiConfig-Profils (siehe Lobby.aiModes/aiProfiles). Fehlt bei einem Gegner-Eintrag, gilt Standard/Default. */
export interface AiPick { mode: "standard" | "hybrid" | "sim"; profile: string }
export interface LogLine { type: "log"; text: string; kind?: string; card?: number; id?: number; }
export interface GameOver { type: "gameOver"; winner?: string; }
export interface ErrorMsg { type: "error"; text: string; }

export type Inbound = Snapshot | Choice | Lobby | LogLine | GameOver | ErrorMsg;

export type Outbound =
  // humanDeck fehlt bei spectate:true (KI-only-Modus, kein eigener Sitz - siehe lobbyPayload.ts)
  | { type: "startGame"; spectate?: boolean; humanDeck?: DeckRef; opponents: (DeckRef & { name: string; ai?: AiPick })[]; aiTimeout?: number }
  | { type: "selectCard"; id: number; alt?: boolean; seq?: number }
  | { type: "selectPlayer"; id: number; seq?: number }
  | { type: "ok"; seq?: number }
  | { type: "cancel"; seq?: number }
  | { type: "setStops"; own?: string[]; opp?: string[] }
  | { type: "fullControl"; value: boolean }
  | { type: "answer"; id: number; value: unknown }
  | { type: "concede" }
  | { type: "requestState" }
  // Gespeichertes Archidekt-Deck neu von Archidekt laden (Bridge antwortet mit einer frischen lobby-Nachricht oder error).
  | { type: "resyncDeck"; name: string };

export type StartGame = Extract<Outbound, { type: "startGame" }>;

export const PHASES: { id: string; short: string }[] = [
  { id: "UNTAP", short: "UT" }, { id: "UPKEEP", short: "UP" }, { id: "DRAW", short: "DR" },
  { id: "MAIN1", short: "M1" }, { id: "COMBAT_BEGIN", short: "BC" }, { id: "COMBAT_DECLARE_ATTACKERS", short: "DA" },
  { id: "COMBAT_DECLARE_BLOCKERS", short: "DB" }, { id: "COMBAT_FIRST_STRIKE_DAMAGE", short: "FS" },
  { id: "COMBAT_DAMAGE", short: "CD" }, { id: "COMBAT_END", short: "EC" }, { id: "MAIN2", short: "M2" },
  { id: "END_OF_TURN", short: "END" }, { id: "CLEANUP", short: "CL" },
];
