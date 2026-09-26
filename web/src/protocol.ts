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

/** Ein Angreifer mit Ziel und Blockern (Bridge: Snapshot.AttackSnap). Genau eines von defenderPlayer und
 *  defenderCard ist gesetzt; fehlt beides, ist das Ziel nicht aufloesbar. */
export interface AttackSnap {
  attacker: number;
  defenderPlayer?: number;
  defenderCard?: number;
  blockers?: number[];
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
  /** Zeilen je Angreifer, solange ein Kampf laeuft; sonst fehlt das Feld. */
  combat?: AttackSnap[];
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
 * ("c:Name|SET|art"); archidekt ist die Archidekt-Deck-Id eines importierten Decks, null/fehlend sonst;
 * archidektUpdated ist der beim Import/Resync gespeicherte Archidekt-Stand (ISO-String), null/fehlend
 * ohne archidekt-Tag.
 *
 * bracket ist das Commander-Bracket 1-5 (aus Archidekt oder von Hand gesetzt, siehe setDeckBracket).
 * Es FEHLT bzw. ist null, wenn es unbekannt ist - Precons haben nie eines. Die Gegnerwahl des Sparrings
 * haengt daran (Spec Stueck 3 §2): "unbekannt" ist ein eigener Topf, kein Bracket 0. */
export interface DeckInfo {
  name: string;
  commanders: { name: string; imageKey?: string }[];
  archidekt?: string | null;
  archidektUpdated?: string | null;
  bracket?: number | null;
}

/** Ein Deck-Eintrag aus Archidekt.listDecks (Bridge). art ist die Vorschau-URL (customFeatured/featured),
 * null/fehlend ohne Bild. */
export interface ArchidektEntry { id: number; name: string; updatedAt: string; art?: string | null }
/** Antwort auf Outbound.archidektList. */
export interface ArchidektDecks { type: "archidektDecks"; username: string; decks: ArchidektEntry[] }
/** Laufender archidektImport: current ist der Name des gerade importierten Decks, fehlt/null zwischen
 * den Decks (u. a. am Ende, wenn done === total). Jackson laesst null-Felder weg - current kann im JSON
 * also fehlen, nicht nur null sein. */
export interface ArchidektProgress { type: "archidektProgress"; done: number; total: number; current?: string | null; errors: string[] }

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
/** Die Bridge rechnet und am Tisch passiert sichtbar nichts (mtgplayer.gui.ThinkingTicker): seconds ist
 * die Dauer der bisherigen Stille, player der Sitz mit Prioritaet - er fehlt/ist null, wenn kein Snapshot
 * ihn hergibt. seconds: 0 beendet die Anzeige (kommt einmalig, sobald wieder etwas passiert). */
export interface Thinking { type: "thinking"; player?: number | null; seconds: number }
export interface GameOver { type: "gameOver"; winner?: string; }
export interface ErrorMsg { type: "error"; text: string; }

/** Ein Punkt der Zeitachse eines Sitzes (MatchRecord.TurnPoint, ab v2): Stand zu Beginn eines EIGENEN
 * Zuges. `turn` bedeutet ab v3 den EIGENEN Zug des Sitzes (1, 2, 3 ...), nicht mehr Forges globale
 * Zugnummer: in einer Vierer-Runde lag ein Sitz sonst bei 1, 5, 9 ... und der naechste bei 2, 6, 10 ... -
 * die Kurven zweier Sitze lagen damit versetzt statt uebereinander. In einem v1/v2-Datensatz steht hier
 * WEITERHIN Forges globale Zugnummer (wie MatchRecord.turns/eliminatedTurn) - alte Datensaetze werden
 * nicht umgerechnet, siehe MatchTimeline.tsx (beschriftet die x-Achse je nach Formatversion des
 * Datensatzes). lands/creatures sind die eigenen Bleibenden IM SPIEL (Bestand, nicht kumulierte
 * Abgaben - dafuer gibt es landsByTurn), life das Leben, hand die Handkarten.
 *
 * `spells` faellt aus der Reihe: die anderen vier sind ein Stand ZU Zugbeginn, spells ist die Zahl der
 * Zauber, die der Sitz in dem Zug-ABSCHNITT gewirkt hat, den dieser Punkt eroeffnet (bis zum Beginn
 * seines naechsten eigenen Zuges). Die Summe ueber alle Punkte kann kleiner sein als seat.spells:
 * Zauber vor dem ersten eigenen Zug haben keinen Punkt, und hinter dem Deckel der Bridge
 * (MatchRecorder.TIMELINE_MAX) faellt jeder weitere Zug weg. */
export interface TurnPoint { turn: number; lands: number; creatures: number; life: number; hand: number; spells: number }

/** Ein Sitz aus mtgplayer.stats.MatchRecord.Seat (Bridge). ai fehlt/null bei einem menschlichen Sitz.
 * eliminatedTurn/firstMissedLandDrop/firstCommanderTurn fehlen/null, wenn das Ereignis nicht eintrat.
 * landsByTurn zaehlt die EIGENEN Zuege des Sitzes und hat genau ownTurns+1 Eintraege (Index 0 = vor dem
 * ersten eigenen Zug, kumulativ) - fragt eine Kennzahl nach einem spaeteren Zug, gehoert diese Partie nicht
 * in den Mittelwert (auslassen, nicht klemmen; siehe matchStats.landsTurn).
 *
 * ACHTUNG, Formatversion: alle Felder ab `spellsCountered` sind die Vorfall-Kennzahlen aus Runde B und
 * gibt es erst ab MatchRecord.v >= 2. In einem v1-Datensatz wurden sie NIE gezaehlt - die Bridge schickt
 * sie dort trotzdem als 0 mit (Jackson serialisiert die int-Felder des Records). Eine 0 in einem
 * v1-Datensatz heisst also "keine Daten", nicht "null Vorfaelle"; wer sie liest, prueft vorher v >= 2
 * (siehe matchStats.isV2). Optional sind sie hier nur, damit Fixtures und Tests einen v1-Sitz ohne 27
 * Nullen hinschreiben koennen. */
export interface MatchSeat {
  name: string; deck: string; human: boolean; ai?: { mode: string; profile: string } | null;
  winner: boolean; lossReason?: string | null; eliminatedTurn?: number | null; mulligans: number; lands: number;
  landsByTurn: number[]; missedLandDrops: number; firstMissedLandDrop?: number | null; spells: number; spellMana: number;
  commanderCasts: number; commanderTax: number; firstCommanderTurn?: number | null; damageDealt: number;
  damageTaken: number; combatDamageTaken: number; lifeEnd: number; poisonEnd: number;
  // --- ab v2 (Runde B), siehe oben: in einem v1-Datensatz bedeutungslos.
  /** Eigene Zauber, die gekontert wurden bzw. mangels Ziel verpufften. */
  spellsCountered?: number; spellsFizzled?: number;
  /** Eigene Counterspells und eigene Entfernungszauber (Naeherung, siehe MatchRecorder.classifyCast). */
  counterspellsCast?: number; removalCast?: number;
  cardsDrawn?: number;
  /** JEDER Weg Hand -> Friedhof, nicht nur das Abwerfen im Wortsinn (auch Kosten). */
  cardsDiscarded?: number;
  cardsMilled?: number;
  /** Laender in der nach allen Mulligans behaltenen Starthand. */
  openingLands?: number;
  /** Handkarten beim Ausscheiden (bzw. am Partieende, wenn der Sitz ueberlebt hat). */
  handEnd?: number;
  /** Verlorene eigene Bleibende; creaturesLostInCombat/-Other sind Teilmengen davon (nur Kreaturen). */
  permanentsLost?: number; creaturesLostInCombat?: number; creaturesLostOther?: number;
  /** Groesster eigener Verlust in einem Aufloesungsfenster und Zahl der Fenster ab drei Verlusten
   * (= erlittene Massenentfernung). */
  biggestSweep?: number; sweepsSuffered?: number;
  tokensCreated?: number;
  /** Jede Deklaration eines eigenen Angreifers (zwei Kampfphasen zaehlen doppelt) bzw. nur die Zuege mit
   * mindestens einem Angriff; attackersFaced sind gegnerische Angreifer gegen diesen Sitz. */
  attacksDeclared?: number; attackedTurns?: number; attackersFaced?: number; blocksDeclared?: number;
  /** Aufteilung des Kampfschadens am Sitz nach Schluesselwort der Quelle (Fliegen vor Trampelschaden);
   * die drei ergeben zusammen combatDamageTaken, mit damageTakenNonCombat zusammen damageTaken. */
  damageTakenFlying?: number; damageTakenTrample?: number; damageTakenOther?: number; damageTakenNonCombat?: number;
  /** Zusammen damageDealt. */
  damageDealtCombat?: number; damageDealtNonCombat?: number;
  /** Summe ueber ALLE gegnerischen Commander zusammen - die 21-Punkte-Regel laesst sich daraus nicht ableiten. */
  commanderDamageTaken?: number;
  lifeGained?: number;
  /** Zeitachse (ab v2). Das Feld FEHLT in der matches-Liste (die Bridge laesst es weg, siehe
   * MatchRecord.withoutTimeline) und kommt erst mit matchDetail - "fehlt" heisst also "nicht geladen",
   * eine leere Liste dagegen "geladen, aber keine Punkte". */
  timeline?: TurnPoint[];
}
/** Eine gespielte Partie aus mtgplayer.stats.MatchRecord (Bridge). reason ist Forges Spielende-Grund
 * (z. B. "AllOpponentsLost"). counted/excludeReason: automatisch nicht gewertete Partien (zu kurz,
 * aufgegeben, Zugdeckel, abgebrochen, Absturz) tragen counted:false und einen Grund in excludeReason.
 * v ist die Formatversion des Datensatzes (aktuell 3). Ab v2 traegt jeder Sitz die Vorfall-Kennzahlen aus
 * Runde B (gekonterte Zauber, Verluste, Kampf und Schaden, Zeitachse - siehe MatchSeat). v < 2 heisst fuer
 * die: KEINE DATEN, nicht "0" - dort wurde nie gezaehlt, und eine Quote daraus waere erfunden. Ab v3
 * zaehlt TurnPoint.turn den eigenen Zug des Sitzes statt Forges globaler Zugnummer (siehe TurnPoint) -
 * alle Pruefungen der Form v >= 2 gelten davon unberuehrt unveraendert weiter.
 * Datensaetze ganz ohne Feld gelten der Bridge als v1. */
export interface MatchRecord {
  v?: number; id: string; startedAt: string; endedAt: string; durationMs: number;
  source: "live" | "spectate" | "sparring";
  /** KI-Bedenkzeit je Entscheidung in Sekunden, mit der die Partie lief; fehlt/null bei aelteren Datensaetzen. */
  aiTimeout?: number | null;
  turns: number; reason: string; draw: boolean; counted: boolean;
  excludeReason?: string | null; seats: MatchSeat[];
}
/** Bei Verbindung und nach jeder Aenderung: die Partienliste (neueste zuletzt), siehe MatchStore. Die
 * Bridge schickt hoechstens die letzten 300 Datensaetze und JEDEN davon OHNE Zeitachse (siehe
 * MatchSeat.timeline); total ist die Gesamtzahl gespeicherter Partien - ist total groesser als
 * matches.length, sind aeltere Partien nicht mitgeschickt worden. total fehlt bei einer Bridge vor
 * Runde A. */
export interface Matches { type: "matches"; matches: MatchRecord[]; total?: number }

/** Fortschritt eines Sparring-Laufs (Bridge: Messages.SparringProgress, Spec Stueck 3 §3): einmal zu
 * Beginn mit done: 0 und danach nach jedem Spielende. current ist der Gegner der gerade laufenden
 * Partie - er FEHLT (Jackson laesst null weg), sobald keine Partie mehr folgt. errors traegt je
 * gescheiterter Partie "<Gegner>: <Grund>"; eine gescheiterte Partie zaehlt trotzdem in done mit.
 * running ist genau in der letzten Nachricht eines Laufs false, auch nach einem Abbruch. */
export interface SparringProgress {
  type: "sparringProgress"; done: number; total: number; current?: string | null; errors: string[]; running: boolean;
}
/** Antwort auf matchDetail: EIN vollstaendiger Datensatz, inklusive Zeitachse je Sitz. */
export interface MatchMsg { type: "match"; match: MatchRecord }

/** Was in einem Deck steckt - Antwort der Bridge auf analyzeDeck (mtgplayer.decks.DeckAnalysis). Rein aus
 * Forges Kartendatenbank (Typen, Manakosten, Orakeltext), ohne eine einzige gespielte Partie.
 *
 * Die Einordnung in categories ist Textmustererkennung, keine Semantik: die Zahlen sind
 * Groessenordnungen, keine Wahrheit (eine Karte darf mehrere Kategorien treffen, "draw cards equal to …"
 * wird nicht erkannt, jede Manaquelle gilt als ramp). Das Board schreibt diese Einschraenkung dazu.
 *
 * cards/lands/basics: Karten in Haupt- und Kommandeursektion. avgCmc: Ø Manabetrag der Nicht-Laender.
 * curve: Nicht-Laender je Manabetrag, Schluessel "0".."6" und "7+". sources: Farbquellen je Farbe
 * ("W","U","B","R","G") plus "any". identity: Farbidentitaet der Kommandeure. unclassified: Nicht-Laender
 * ohne jede Kategorie. */
export interface DeckAnalysis {
  cards: number; lands: number; basics: number; avgCmc: number;
  curve: Record<string, number>; sources: Record<string, number>;
  identity: string[];
  categories: {
    ramp: number; draw: number; removal: number; wipes: number; counters: number;
    flyerDefense: number; wipeProtection: number; recursion: number; tutors: number;
  };
  unclassified: number;
}
/** Antwort auf analyzeDeck; deck ist der angefragte Deckname (Precon- oder Speichername). */
export interface DeckAnalysisMsg { type: "deckAnalysis"; deck: string; analysis: DeckAnalysis }

/** Eine Karte, die im Schnitt fuer den Vorschlag weichen wuerde - nur als Denkanstoss, keine Aufforderung
 *  (siehe CardSuggestion.cut). */
export interface CardCut { name: string; reason: string }

/** Ein Kartenvorschlag der Bridge (Stueck 2, mtgplayer.decks.Suggestions). Jackson laesst fehlende Werte
 *  komplett weg (NON_NULL) - sie kommen also nie als null an, sondern fehlen als Property. */
export interface CardSuggestion {
  name: string; role: string; manaCost?: string; cmc: number;
  /** Anteil der vergleichbaren Decks auf EDHREC (0..1); fehlt ohne EDHREC-Daten (Quelle "db"). */
  share?: number;
  gameChanger?: boolean; imageKey?: string; text?: string; cut?: CardCut;
}

/** Antwort auf suggestCards. source unterscheidet den EDHREC-Fall (mit share/cut) vom Datenbank-Rueckfall
 *  ohne Netz; note traegt eine Erklaerung dazu (z. B. "EDHREC nicht erreichbar"), wenn es sie gibt.
 *  fetched: Abrufdatum des EDHREC-Stands (ISO), fehlt bei source "db" - kein EDHREC-Stand, kein Datum. */
export interface CardSuggestionsMsg {
  type: "cardSuggestions"; deck: string; source: "edhrec" | "db"; note?: string;
  suggestions: CardSuggestion[]; fetched?: string;
}

/** Eine Kartenzeile der Deck-Auswertung (Stueck 6, Bridge: mtgplayer.stats.CardStats.Card). Alle Zaehler
 *  beziehen sich auf CardStatsMsg.withCardData, NICHT auf games - die int-Felder kommen wegen Jackson
 *  immer mit an (auch als 0), fehlen also nie. avgCastTurn fehlt, wenn die Karte nie gewirkt wurde
 *  (Schnitt ueber 0 Werte waere sinnlos); imageKey/manaCost/cmc fehlen, wenn Forges Kartendatenbank den
 *  Namen nicht kennt - die Zeile bleibt trotzdem stehen. */
export interface CardStat {
  name: string; handGames: number; castGames: number; avgCastTurn?: number; stuckGames: number;
  neverDrawnGames: number; counteredGames: number; lostGames: number;
  imageKey?: string; manaCost?: string; cmc?: number;
}

/** Antwort auf deckCards (Stueck 6). games: gewertete Partien des Decks; withCardData: davon die mit
 *  lesbarer Kartendatei - die Aufzeichnung beginnt erst mit neuen Partien, alte haben keine Datei. Das
 *  senkt NUR withCardData, nicht games. enough ist withCardData >= CardStats.MIN_GAMES (5) - darunter
 *  zeigt das UI einen Hinweis statt der Tabelle. cards kommt bereits in der Reihenfolge an, die zuerst
 *  zu zeigen ist (groesster Handlungsbedarf zuerst, siehe CardStats.of auf der Bridge). */
export interface CardStatsMsg {
  type: "cardStats"; deck: string; games: number; withCardData: number; enough: boolean; cards: CardStat[];
}

/** Einmalig gemeldet, wenn die Bridge beim Start eine echt neuere Fassung erkennt (Aufgabe 5/6, Bridge:
 *  Messages.VersionMsg) - ohne ein wirkliches Update kommt diese Nachricht gar nicht erst. current ist
 *  die eigene, latest die neuere Fassung (jeweils reine Ziffern-Abschnitte, ohne "v"-Vorsilbe); notes ist
 *  der Release-Text (leer ohne einen). Anwenden geschieht ueber Outbound "applyUpdate". */
export interface VersionMsg {
  type: "version"; current: string; latest: string; url: string; sha256: string; notes: string;
}
/** Fortschritt/Fehler eines laufenden applyUpdate (Aufgabe 5/6, Bridge: Messages.UpdateStateMsg): state
 *  durchlaeuft "laden" -> "pruefen" -> "entpacken" -> "neustart" in dieser Reihenfolge; ein Fehlschlag an
 *  jeder Stelle endet stattdessen mit "fehler". text traegt nur bei "fehler" einen Klartextgrund - die
 *  anderen Zustaende formuliert der Client selbst (siehe update.ts). Beim Zustand "neustart" beendet
 *  sich die App selbst, danach kommt keine weitere Nachricht mehr. */
export interface UpdateStateMsg {
  type: "updateState"; state: "laden" | "pruefen" | "entpacken" | "neustart" | "fehler"; text: string;
}

export type Inbound = Snapshot | Choice | Lobby | LogLine | Thinking | GameOver | ErrorMsg | ArchidektDecks | ArchidektProgress | Matches | MatchMsg | DeckAnalysisMsg | SparringProgress | CardSuggestionsMsg | CardStatsMsg | VersionMsg | UpdateStateMsg;

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
  | { type: "resyncDeck"; name: string }
  // Gespeichertes Deck loeschen (nur eigene Decks, keine Precons; Bridge antwortet mit einer frischen lobby-Nachricht oder error).
  | { type: "deleteDeck"; name: string }
  // Oeffentliche Commander-Decks eines Archidekt-Kontos auflisten (Bridge antwortet mit archidektDecks oder error).
  | { type: "archidektList"; username: string }
  // Ausgewaehlte Decks importieren/resyncen; die Bridge meldet den Fortschritt ueber archidektProgress.
  | { type: "archidektImport"; ids: number[] }
  // Partie loeschen bzw. gewertet/nicht gewertet umschalten; die Bridge antwortet mit einer frischen
  // matches-Nachricht oder error ("Partie <id>: ...").
  | { type: "deleteMatch"; id: string }
  | { type: "setMatchCounted"; id: string; counted: boolean }
  // Eine einzelne Partie vollstaendig (inkl. Zeitachse) nachladen; die Bridge antwortet mit "match"
  // oder error ("Partie <id>: unbekannte Partie").
  | { type: "matchDetail"; id: string }
  // Deckanalyse anfordern (Precon- oder Speichername); die Bridge antwortet mit deckAnalysis oder error.
  | { type: "analyzeDeck"; deck: string }
  // Bracket eines gespeicherten Decks setzen (null = unbekannt); die Bridge antwortet mit einer frischen
  // lobby-Nachricht oder error ("Bracket <name>: ...").
  | { type: "setDeckBracket"; name: string; bracket: number | null }
  // Sparring starten (Spec Stueck 3 §3): games 1vs1-Partien des gespeicherten Decks gegen zufaellige
  // Gegner aus seinem Bracket. ai/timeout/maxTurns sind optional (Bridge: standard/Default, 5 s, 60
  // Zuege). Die Bridge antwortet mit sparringProgress und nach jeder Partie mit matches - oder mit
  // error ("Sparring: keine Gegner im Bracket 3: ...", "Sparring läuft noch").
  | { type: "sparringStart"; deck: string; games: number; ai?: AiPick; timeout?: number; maxTurns?: number }
  // Laufendes Sparring abbrechen; die laufende Partie wird abgeschossen, der Lauf endet mit running: false.
  | { type: "sparringCancel" }
  // Kartenvorschlaege fuer die angegebenen Rollen anfordern (Stueck 2, Spec §8); roles kommt aus
  // roleGaps() in findings.ts. Dort ist Role ein enger String-Typ - hier bewusst string[], denn
  // protocol.ts haengt an keiner anderen Quelldatei (wie schon bei CardSuggestion.role oben). Die
  // Bridge antwortet mit cardSuggestions oder error.
  | { type: "suggestCards"; deck: string; roles: string[] }
  // Kartentabelle eines Decks anfordern (Stueck 6, Bridge: mtgplayer.stats.CardStats). Die Bridge
  // antwortet mit cardStats oder error.
  | { type: "deckCards"; deck: string }
  // Das ueber "version" gemeldete Update anwenden (Aufgabe 5/6); ohne vorherige "version" lehnt die
  // Bridge mit error ab. Fortschritt/Fehler kommen als updateState, ein Erfolg beendet die Bridge selbst
  // (Zustand "neustart" ist die letzte Nachricht).
  | { type: "applyUpdate" };

export type StartGame = Extract<Outbound, { type: "startGame" }>;

export const PHASES: { id: string; short: string }[] = [
  { id: "UNTAP", short: "UT" }, { id: "UPKEEP", short: "UP" }, { id: "DRAW", short: "DR" },
  { id: "MAIN1", short: "M1" }, { id: "COMBAT_BEGIN", short: "BC" }, { id: "COMBAT_DECLARE_ATTACKERS", short: "DA" },
  { id: "COMBAT_DECLARE_BLOCKERS", short: "DB" }, { id: "COMBAT_FIRST_STRIKE_DAMAGE", short: "FS" },
  { id: "COMBAT_DAMAGE", short: "CD" }, { id: "COMBAT_END", short: "EC" }, { id: "MAIN2", short: "M2" },
  { id: "END_OF_TURN", short: "END" }, { id: "CLEANUP", short: "CL" },
];
