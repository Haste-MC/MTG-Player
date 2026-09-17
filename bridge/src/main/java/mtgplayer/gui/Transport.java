package mtgplayer.gui;

/** Ausgang zum Browser. Implementierungen müssen thread-sicher sein (Game-, UI- und Socket-Thread senden). */
public interface Transport {
    void send(Object message);
}
