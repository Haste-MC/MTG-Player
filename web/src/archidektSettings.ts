/** localStorage-Schlüssel für den zuletzt verwendeten Archidekt-Benutzernamen in der Lobby. */
const KEY = "mtg.lobby.archidekt";

/**
 * Lädt den zuletzt verwendeten Archidekt-Benutzernamen - rein testbar mit injiziertem Storage, Muster
 * wie {@link "./aiSettings".loadAiSettings}. Fehlendes/kaputtes/unlesbares Storage fällt auf "" zurück.
 *
 * Nimmt einen Getter statt des Storage-Objekts direkt entgegen: schon der bloße Zugriff auf den
 * Bezeichner `localStorage` kann werfen, wenn der Storage vom Browser blockiert ist - mit einem Getter
 * passiert dieser Zugriff hier drin im try/catch, statt beim Aufrufer außerhalb jedes try/catch.
 */
export function loadArchidektUser(getStorage: () => Pick<Storage, "getItem">): string {
  try {
    return getStorage().getItem(KEY) ?? "";
  } catch {
    return "";
  }
}

/** Speichert den Archidekt-Benutzernamen - best effort, ein volles/deaktiviertes Storage darf nicht
 *  crashen. Getter statt Storage-Objekt direkt, aus demselben Grund wie bei {@link loadArchidektUser}. */
export function saveArchidektUser(getStorage: () => Pick<Storage, "setItem">, username: string): void {
  try {
    getStorage().setItem(KEY, username);
  } catch {
    // Speichern ist best effort - siehe Kommentar oben.
  }
}
