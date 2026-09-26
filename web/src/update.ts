// Update-Hinweis in der Lobby (Aufgabe 6): reine Uebergangsfunktion, ohne DOM, damit sie ohne Browser
// testbar ist (siehe Suggestions.tsx/suggestFreshness.ts fuer dasselbe Muster). Die Anzeige selbst
// (Knoepfe, Fortschritt) sitzt in Lobby.tsx.

import type { UpdateStateMsg, VersionMsg } from "./protocol";

/** "verfuegbar": ein Update ist bekannt, aber noch nicht angestossen - Knoepfe "Aktualisieren"/"Später".
 *  Die vier folgenden sind Bridge-Zustaende (Messages.UpdateStateMsg) in ihrer festen Reihenfolge,
 *  "fehler" kann an jeder Stelle statt des naechsten folgen. */
export type UpdateBannerState = "verfuegbar" | "laden" | "pruefen" | "entpacken" | "neustart" | "fehler";

export interface UpdateBanner {
  state: UpdateBannerState;
  text: string;
}

/** Text je Fortschrittszustand - die Bridge schickt hier bewusst kein `text` mit (nur bei "fehler"
 *  belegt, siehe Messages.UpdateStateMsg), der Client formuliert selbst. "neustart" erklaert extra, dass
 *  die Oberflaeche gleich verschwindet (die Bridge beendet sich selbst) - sonst wirkt eine Fortschritts-
 *  anzeige, die nie fertig wird, wie ein haengengebliebener Vorgang statt eines geplanten Endes. */
const PROGRESS_TEXT: Record<Exclude<UpdateBannerState, "verfuegbar" | "fehler">, string> = {
  laden: "Lädt herunter …",
  pruefen: "Prüft die Datei …",
  entpacken: "Entpackt …",
  neustart: "Update fertig, die App startet gleich neu …",
};

/** Baut aus der zuletzt bekannten "version"- und ggf. "updateState"-Nachricht Text und Zustand der
 *  Update-Leiste. Ohne `version` gibt es nichts zu zeigen (die Bridge schickt sie nur bei einer echten
 *  neueren Fassung, siehe VersionMsg) - dann liefert diese Funktion `undefined`, unabhaengig davon, ob
 *  zufaellig doch ein `updateState` vorliegt. */
export function updateBanner(version: VersionMsg | undefined, updateState: UpdateStateMsg | undefined): UpdateBanner | undefined {
  if (!version) return undefined;
  if (!updateState) return { state: "verfuegbar", text: `Version ${version.latest} verfügbar` };
  if (updateState.state === "fehler") return { state: "fehler", text: updateState.text };
  return { state: updateState.state, text: PROGRESS_TEXT[updateState.state] };
}
