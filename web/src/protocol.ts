export interface PromptSnap {
  message: string;
  card?: number;
  okLabel: string;
  cancelLabel: string;
  okEnabled: boolean;
  cancelEnabled: boolean;
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
  text?: string;
  typeLine?: string;
  manaCost?: string;
  attacking?: boolean;
  blocking?: boolean;
  token?: boolean;
  selectable?: boolean;
  actionable?: boolean;
  highlighted?: boolean;
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
  prompt: PromptSnap;
}

export interface Option {
  index: number;
  label: string;
  card?: number;
  player?: number;
}

export type ChoiceKind = "one" | "many" | "order" | "confirm" | "number" | "text" | "ability" | "entities" | "reveal";

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
}

export interface Lobby { type: "lobby"; precons: string[]; }
export interface LogLine { type: "log"; text: string; }
export interface GameOver { type: "gameOver"; winner?: string; }
export interface ErrorMsg { type: "error"; text: string; }

export type Inbound = Snapshot | Choice | Lobby | LogLine | GameOver | ErrorMsg;

export type Outbound =
  | { type: "startGame"; humanDeck: { precon: string }; opponents: { precon: string; name: string }[] }
  | { type: "selectCard"; id: number; alt?: boolean }
  | { type: "selectPlayer"; id: number }
  | { type: "ok" }
  | { type: "cancel" }
  | { type: "answer"; id: number; value: unknown }
  | { type: "concede" }
  | { type: "requestState" };
