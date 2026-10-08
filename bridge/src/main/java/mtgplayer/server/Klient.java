package mtgplayer.server;

/**
 * Ein verbundener Browser - gleich, auf welchem Weg.
 *
 * <p>Es gibt zwei: den WebSocket ({@code WsServer}) und, wenn der nicht aufgeht, einen
 * Ereignisstrom auf dem HTTP-Port ({@code Ereignisstrom}). Fuer die Buchhaltung in
 * {@link Klienten} sind beide nicht zu unterscheiden - ein zusehender Browser am Strom zaehlt wie
 * einer am Socket, und steuern darf jeder von beiden.</p>
 */
public interface Klient {

    /** Eine fertige JSON-Nachricht hinausschicken. Wirft, wenn die Verbindung hin ist. */
    void sende(String json) throws Exception;

    /** Steht die Verbindung noch? */
    boolean offen();

    /** Verbindung beenden; darf mehrfach aufgerufen werden. */
    void schliesse();
}
