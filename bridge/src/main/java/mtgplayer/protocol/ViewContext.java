package mtgplayer.protocol;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Alles, was der Serializer über den betrachtenden Sitz wissen muss. */
public record ViewContext(
        PlayerView me,
        /** Sitz-Id -> Teamnummer, leer wenn die Partie keine Teams hat (siehe WebGuiGame.teamsById). */
        Map<Integer, Integer> teams,
        Predicate<CardView> mayView,
        /**
         * Darf der Betrachter sehen, was unter einer VERDECKTEN Karte liegt? Getrennt von
         * {@code mayView}: eine verdeckte Kreatur auf dem Spielfeld ist fuer alle sichtbar
         * ({@code mayView} = true), aber nur ihr Beherrscher weiss, was sie ist. Forge beantwortet
         * das fuer jede Herkunft an einer Stelle ({@code AbstractGuiGame.mayFlip}).
         */
        Predicate<CardView> mayLookUnder,
        Predicate<CardView> selectable,
        Predicate<CardView> weaklySelectable,
        Predicate<GameEntityView> highlighted,
        Predicate<PlayerView> targetable,
        Snapshot.PromptSnap prompt,
        Messages.StopsMsg stops,
        boolean fullControl,
        boolean spectator) {

    /** Sicht eines Spielers ohne UI-Zustand – für Tests und den Lobby-Fall. */
    public static ViewContext plain(PlayerView me) {
        return new ViewContext(me, Map.of(), c -> c.canBeShownTo(me), c -> c.canFaceDownBeShownTo(me),
                c -> false, c -> false, e -> false, p -> false,
                Snapshot.PromptSnap.EMPTY, new Messages.StopsMsg(List.of(), List.of()), false, false);
    }
}
