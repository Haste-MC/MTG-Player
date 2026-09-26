import { describe, expect, it } from "vitest";
import { updateBanner } from "./update";
import type { UpdateStateMsg, VersionMsg } from "./protocol";

const VERSION: VersionMsg = {
  type: "version", current: "1.40.0", latest: "1.41.0", url: "https://example.invalid/x.zip",
  sha256: "abc", notes: "- Neue Statistik-Ansicht",
};

describe("updateBanner", () => {
  it("ohne \"version\" gibt es nichts zu zeigen, auch nicht mit einem updateState", () => {
    expect(updateBanner(undefined, undefined)).toBeUndefined();
    const updateState: UpdateStateMsg = { type: "updateState", state: "laden", text: "" };
    expect(updateBanner(undefined, updateState)).toBeUndefined();
  });

  it("verfuegbares Update ohne updateState: Text nennt die neuere Fassung", () => {
    expect(updateBanner(VERSION, undefined)).toEqual({ state: "verfuegbar", text: "Version 1.41.0 verfügbar" });
  });

  it.each([
    ["laden", "Lädt herunter …"],
    ["pruefen", "Prüft die Datei …"],
    ["entpacken", "Entpackt …"],
    // "neustart" ist der letzte Zustand (die Bridge beendet sich danach selbst, siehe UpdateStateMsg) -
    // der Text muss das erklaeren, nicht wie ein Fortschritt wirken, der nie als "fertig" ankaeme. Fester
    // Textvergleich statt einer losen /neu/i-Regex, damit ein zufaellig anderes "neu" (z. B. "Neuer
    // Fehler") den Test nicht faelschlich gruen macht.
    ["neustart", "Update fertig, die App startet gleich neu …"],
  ] as const)("Zustand %s bekommt einen erklaerenden Text", (state, text) => {
    const updateState: UpdateStateMsg = { type: "updateState", state, text: "" };
    expect(updateBanner(VERSION, updateState)).toEqual({ state, text });
  });

  it("Zustand \"fehler\": Text kommt aus updateState.text (der Grund), nicht aus einer festen Konstante", () => {
    const updateState: UpdateStateMsg = { type: "updateState", state: "fehler", text: "Prüfsumme stimmt nicht" };
    expect(updateBanner(VERSION, updateState)).toEqual({ state: "fehler", text: "Prüfsumme stimmt nicht" });
  });
});
