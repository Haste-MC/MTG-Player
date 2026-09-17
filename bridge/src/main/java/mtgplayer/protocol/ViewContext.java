package mtgplayer.protocol;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;

import java.util.function.Predicate;

/** Alles, was der Serializer über den betrachtenden Sitz wissen muss. */
public record ViewContext(
        PlayerView me,
        Predicate<CardView> mayView,
        Predicate<CardView> selectable,
        Predicate<CardView> weaklySelectable,
        Predicate<GameEntityView> highlighted,
        Snapshot.PromptSnap prompt) {

    /** Sicht eines Spielers ohne UI-Zustand – für Tests und den Lobby-Fall. */
    public static ViewContext plain(PlayerView me) {
        return new ViewContext(me, c -> c.canBeShownTo(me), c -> false, c -> false, e -> false, Snapshot.PromptSnap.EMPTY);
    }
}
