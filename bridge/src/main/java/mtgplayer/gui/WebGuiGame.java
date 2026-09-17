package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gui.GuiBase;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableTypes;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import mtgplayer.protocol.Messages;
import mtgplayer.protocol.Snapshot;
import mtgplayer.protocol.StateSerializer;
import mtgplayer.protocol.ViewContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forges GUI-Schnittstelle für einen Browser-Sitz. Zustandsänderungen werden gebündelt als
 * {@link Snapshot} gepusht, synchrone Dialoge laufen über den {@link ChoiceBroker}.
 * Eingaben aus dem Browser kommen über die on*-Methoden – die müssen auf dem UI-Thread
 * aufgerufen werden ({@code GuiBase.getInterface().invokeInEdtLater}).
 */
public class WebGuiGame extends AbstractGuiGame {

    private final Transport out;
    private final ChoiceBroker broker;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile Snapshot.PromptSnap prompt = Snapshot.PromptSnap.EMPTY;

    public WebGuiGame(Transport out) {
        this.out = out;
        this.broker = new ChoiceBroker(out);
    }

    public ChoiceBroker broker() {
        return broker;
    }

    // ---- Zustand raus -------------------------------------------------------------

    /** Bündelt beliebig viele Updates zu einem Push auf dem UI-Thread. */
    private void push() {
        if (getGameView() == null) return;
        if (dirty.compareAndSet(false, true)) {
            GuiBase.getInterface().invokeInEdtLater(() -> {
                dirty.set(false);
                pushState();
            });
        }
    }

    /** Sofort einen Snapshot senden (z. B. nach Reconnect). Auf dem UI-Thread aufrufen. */
    public void pushState() {
        GameView gv = getGameView();
        if (gv == null) return;
        PlayerView me = getLocalPlayers().isEmpty() ? null : getLocalPlayers().iterator().next();
        ViewContext ctx = new ViewContext(me, this::mayView, this::isSelectable, this::isWeaklySelectable,
                this::isHighlighted, prompt);
        out.send(StateSerializer.snapshot(gv, ctx));
    }

    @Override public void setGameView(GameView gameView0) { super.setGameView(gameView0); push(); }
    @Override protected void updateCurrentPlayer(PlayerView player) { push(); }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { push(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { push(); }
    @Override public void updateCards(Iterable<CardView> cards) { push(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { push(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { push(); }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { push(); }
    @Override public void updateStack() { push(); }
    @Override public void updatePhase(boolean saveState) { push(); }
    @Override public void updateTurn(PlayerView player) { push(); }
    @Override public void updatePlayerControl() { push(); }
    @Override public void refreshField() { push(); }
    @Override public void showCombat() { push(); }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void flashIncorrectAction() { out.send(new Messages.ErrorMsg("Das geht gerade nicht.")); }
    @Override public void alertUser() { }
    @Override public void showManaPool(PlayerView player) { push(); }
    @Override public void hideManaPool(PlayerView player) { push(); }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) { super.setSelectables(cards, min, max); push(); }
    @Override public void clearSelectables() { super.clearSelectables(); push(); }
    @Override public void setWeaklySelectable(Iterable<CardView> cards) { super.setWeaklySelectable(cards); push(); }
    @Override public void clearWeaklySelectable() { super.clearWeaklySelectable(); push(); }
    @Override public void setHighlighted(Iterable<GameEntityView> entities, boolean b) { super.setHighlighted(entities, b); push(); }

    @Override
    public synchronized void showPromptMessage(PlayerView playerView, String message, CardView card) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(message == null ? "" : message, card == null ? null : card.getId(),
                p.okLabel(), p.cancelLabel(), p.okEnabled(), p.cancelEnabled());
        push();
    }

    @Override
    public synchronized void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(p.message(), p.card(), label1, label2, enable1, enable2);
        push();
    }

    @Override
    public void finishGame() {
        GameView gv = getGameView();
        String winner = gv == null ? null : gv.getWinningPlayerName();
        broker.cancelAll();
        // pushState() statt push(): finishGame läuft auf dem UI-Thread (Forges Event-Handler), der finale
        // Snapshot muss den Client also synchron vor GameOver erreichen statt erst später über invokeInEdtLater.
        pushState();
        out.send(new Messages.GameOver(winner));
    }

    @Override public void afterGameEnd() { }

    // ---- Eingaben rein (UI-Thread) ------------------------------------------------

    private CardView cardById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.CardViewType, id);
    }

    private PlayerView playerById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.PlayerViewType, id);
    }

    private static ITriggerEvent trigger(boolean alt) {
        return new ITriggerEvent() {
            @Override public int getButton() { return alt ? 3 : 1; }
            @Override public int getX() { return 0; }
            @Override public int getY() { return 0; }
        };
    }

    public void onSelectCard(int cardId, boolean alt) {
        CardView cv = cardById(cardId);
        IGameController c = getGameController();
        if (cv == null || c == null) return;
        if (!c.selectCard(cv, null, trigger(alt))) {
            flashIncorrectAction();
        }
    }

    public void onSelectPlayer(int playerId) {
        PlayerView pv = playerById(playerId);
        IGameController c = getGameController();
        if (pv == null || c == null) return;
        c.selectPlayer(pv, trigger(false));
    }

    public void onOk() {
        IGameController c = getGameController();
        if (c != null) c.selectButtonOk();
    }

    public void onCancel() {
        IGameController c = getGameController();
        if (c != null) c.selectButtonCancel();
    }

    public void onConcede() {
        if (getGameView() == null) return;
        concede();
    }

    // ---- Synchrone Dialoge (Game-Thread) -------------------------------------------

    private static <T> List<Messages.Option> options(List<T> choices, FSerializableFunction<T, String> display) {
        List<Messages.Option> out = new ArrayList<>();
        if (choices == null) return out;
        int i = 0;
        for (T t : choices) {
            String label = display != null ? display.apply(t) : String.valueOf(t);
            Integer card = t instanceof CardView cv ? cv.getId() : null;
            Integer player = t instanceof PlayerView pv ? pv.getId() : null;
            if (t instanceof SpellAbilityView sav && sav.getHostCard() != null) {
                card = sav.getHostCard().getId();
            }
            out.add(new Messages.Option(i++, label, card, player));
        }
        return out;
    }

    /** Index-Liste aus der Antwort; bei null/ungültig die ersten {@code min} Einträge, auf {@code max} gekappt. */
    private static List<Integer> indices(JsonNode value, int min, int max, int size) {
        List<Integer> out = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode n : value) {
                int i = n.asInt(-1);
                if (i >= 0 && i < size && !out.contains(i)) out.add(i);
            }
        } else if (value != null && value.isInt()) {
            int i = value.asInt();
            if (i >= 0 && i < size) out.add(i);
        }
        if (out.size() < Math.max(min, 0)) {
            out.clear();
            for (int i = 0; i < Math.min(Math.max(min, 0), size); i++) out.add(i);
        }
        if (max > 0 && out.size() > max) {
            out = new ArrayList<>(out.subList(0, max));
        }
        return out;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected,
                                  FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        // Forge nutzt min == max == -1 für "nur anzeigen" (reveal, z. B. revealAISkipCards bei
        // UI_SHOW_ACTIONABLE_HIGHLIGHTS) – informativ, blockiert den Game-Thread nicht.
        if (min < 0 && max < 0) {
            broker.notify(message, message, options(choices, display), null);
            return new ArrayList<>();
        }
        String kind = max == 1 ? "one" : "many";
        JsonNode v = broker.ask(kind, message, message, options(choices, display), min, max, null);
        List<T> out = new ArrayList<>();
        for (int i : indices(v, min, max, choices.size())) out.add(choices.get(i));
        return out;
    }

    /**
     * Zwei Modi, je nach Forges "remaining objects"-Vertrag (siehe AbstractGuiGame#many/order):
     * <ul>
     *   <li>{@code remainingObjectsMin == 0 && remainingObjectsMax == 0}: volle Umsortierung – Antwort ist
     *       eine Permutation aller Elemente (kind {@code order}), Sicherheitsnetz füllt fehlende Indizes auf.</li>
     *   <li>sonst: Teilauswahl – {@code remainingObjectsMin/Max} begrenzen, wie viele Elemente in der Quelle
     *       bleiben dürfen ({@code -1} = unbegrenzt); die Antwort ist nur die gewählte Teilmenge (kind
     *       {@code many}), ohne Sicherheitsnetz.</li>
     * </ul>
     */
    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> all = new ArrayList<>(sourceChoices == null ? List.of() : sourceChoices);
        if (destChoices != null) all.addAll(destChoices);
        if (all.isEmpty()) return new OrderResult<>(new ArrayList<>(), false);

        int n = all.size();
        Integer card = referenceCard == null ? null : referenceCard.getId();

        if (remainingObjectsMin == 0 && remainingObjectsMax == 0) {
            JsonNode v = broker.ask("order", title, top, options(all, null), n, n, card);
            List<Integer> idx = indices(v, n, n, n);
            List<T> ordered = new ArrayList<>();
            for (int i : idx) ordered.add(all.get(i));
            for (T t : all) if (!ordered.contains(t)) ordered.add(t); // Sicherheitsnetz bei unvollständiger Antwort
            return new OrderResult<>(ordered, false);
        }

        int pickMin = remainingObjectsMax >= 0 ? Math.max(0, n - remainingObjectsMax) : 0;
        int pickMax = remainingObjectsMin >= 0 ? Math.max(0, n - remainingObjectsMin) : n;
        JsonNode v = broker.ask("many", title, top, options(all, null), pickMin, pickMax, card);
        List<Integer> idx = indices(v, pickMin, pickMax, n);
        List<T> chosen = new ArrayList<>();
        for (int i : idx) chosen.add(all.get(i));
        return new OrderResult<>(chosen, false);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        List<Messages.Option> opts = List.of(new Messages.Option(0, yesButtonText, null, null),
                new Messages.Option(1, noButtonText, null, null));
        JsonNode v = broker.ask("confirm", title, message, opts, 1, 1, null);
        return v == null || v.isNull() ? defaultYes : v.asBoolean(defaultYes);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        String yes = options != null && options.size() > 0 ? options.get(0) : "Yes";
        String no = options != null && options.size() > 1 ? options.get(1) : "No";
        List<Messages.Option> opts = List.of(new Messages.Option(0, yes, null, null), new Messages.Option(1, no, null, null));
        JsonNode v = broker.ask("confirm", c == null ? "" : c.getCurrentState().getName(), question, opts, 1, 1,
                c == null ? null : c.getId());
        return v == null || v.isNull() ? defaultIsYes : v.asBoolean(defaultIsYes);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        JsonNode v = broker.ask("one", title, message, options(options, null), 1, 1, null);
        List<Integer> idx = indices(v, 0, 1, options.size());
        return idx.isEmpty() ? Math.max(0, defaultOption) : idx.get(0);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput,
                                  List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            JsonNode v = broker.ask("one", title, message, options(inputOptions, null), 1, 1, null);
            List<Integer> idx = indices(v, 0, 1, inputOptions.size());
            return idx.isEmpty() ? (initialInput == null ? inputOptions.get(0) : initialInput) : inputOptions.get(idx.get(0));
        }
        JsonNode v = broker.ask(isNumeric ? "number" : "text", title, message, List.of(), 0, 0, null);
        // Freie Eingabe: null/NullNode heißt Abbruch (Forges getInteger-Cutoff-Schleife re-prompt't sonst endlos).
        if (v == null || v.isNull()) return null;
        return v.isNumber() ? String.valueOf(v.asInt()) : v.asText();
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        JsonNode v = broker.ask("ability", hostCard == null ? "" : hostCard.getCurrentState().getName(),
                "Welche Fähigkeit?", options(abilities, null), 0, 1, hostCard == null ? null : hostCard.getId());
        List<Integer> idx = indices(v, 0, 1, abilities.size());
        return idx.isEmpty() ? null : abilities.get(idx.get(0));
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                                                      DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), isOptional ? 0 : 1, 1, null);
        List<Integer> idx = indices(v, isOptional ? 0 : 1, 1, list.size());
        return idx.isEmpty() ? null : list.get(idx.get(0));
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                                                        int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) return new ArrayList<>();
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), min, max, null);
        List<GameEntityView> out = new ArrayList<>();
        for (int i : indices(v, min, max, list.size())) out.add(list.get(i));
        return out;
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> out = new ArrayList<>();
        if (cards != null) for (CardView c : cards) out.add(c);
        return out; // M2: unverändert lassen; Dialog kommt in M3
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return new ArrayList<>(); // kein Sideboarding
    }

    /**
     * M2: automatische Verteilung – jedem Blocker in Reihenfolge tödlichen Schaden, Rest auf den letzten
     * (bzw. bei Trample auf den Verteidiger, Schlüssel null). Dialog kommt in M3.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        int rest = damage;
        if (blockers != null) {
            for (CardView b : blockers) {
                int lethal = Math.max(1, b.getLethalDamage());
                int give = Math.min(lethal, rest);
                out.put(b, give);
                rest -= give;
                if (rest <= 0) break;
            }
        }
        if (rest > 0) {
            boolean trample = attacker != null && attacker.getCurrentState().hasTrample();
            if (trample && defender != null) {
                out.put(null, rest);
            } else if (!out.isEmpty()) {
                CardView last = null;
                for (CardView b : out.keySet()) last = b;
                out.put(last, out.get(last) + rest);
            }
        }
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> out = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) return out;
        Object first = target.keySet().iterator().next();
        for (Object k : target.keySet()) out.put(k, 0);
        out.put(first, amount);
        return out; // M2: alles auf den ersten Eintrag; Dialog kommt in M3
    }

    // ---- Sonstiges ------------------------------------------------------------------

    @Override public void message(String message, String title) { out.send(new Messages.LogLine(title + ": " + message)); }
    @Override public void showErrorDialog(String message, String title) { out.send(new Messages.ErrorMsg(title + ": " + message)); }

    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones, Map<PlayerView, Object> players, boolean backupLastZones) {
        return null;
    }
    @Override public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) { }
    @Override public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { return zonesToUpdate; }
    @Override public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public GameState getGamestate() { return null; }

    /** M2: keine Stops – Forge passt per YIELD_AUTO_PASS_NO_ACTIONS selbst, wenn nichts spielbar ist. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        return false;
    }
}
