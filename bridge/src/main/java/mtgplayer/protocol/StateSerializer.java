package mtgplayer.protocol;

import com.google.common.collect.Multiset;
import forge.card.MagicColor;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Übersetzt Forges Trackable-Views in einen {@link Snapshot}. Reine Funktion, kein Zustand. */
public final class StateSerializer {

    private StateSerializer() { }

    public static Snapshot snapshot(GameView gv, ViewContext ctx) {
        Map<Integer, Snapshot.CardSnap> cards = new LinkedHashMap<>();
        List<Snapshot.PlayerSnap> players = new ArrayList<>();
        FCollectionView<PlayerView> pvs = gv.getPlayers();
        Integer priority = null;
        if (pvs != null) {
            for (PlayerView p : pvs) {
                players.add(player(p, pvs, ctx, cards));
                if (p.getHasPriority()) {
                    priority = p.getId();
                }
            }
        }
        List<Snapshot.StackSnap> stack = new ArrayList<>();
        FCollectionView<StackItemView> items = gv.getStack();
        if (items != null) {
            int i = 0;
            for (StackItemView si : items) {
                stack.add(stackItem(i++, si, ctx, cards));
            }
        }
        PlayerView turn = gv.getPlayerTurn();
        return new Snapshot(
                Snapshot.TYPE,
                gv.getTurn(),
                gv.getPhase() == null ? null : gv.getPhase().name(),
                turn == null ? null : turn.getId(),
                priority,
                ctx.me() == null ? null : ctx.me().getId(),
                gv.isGameOver(),
                players,
                stack,
                cards,
                ctx.stops(),
                ctx.fullControl(),
                ctx.prompt());
    }

    private static Snapshot.PlayerSnap player(PlayerView p, FCollectionView<PlayerView> all, ViewContext ctx,
                                              Map<Integer, Snapshot.CardSnap> cards) {
        Map<Integer, Integer> cmdDamage = new LinkedHashMap<>();
        for (PlayerView other : all) {
            List<CardView> commanders = other.getCommanders();
            if (commanders == null) continue;
            for (CardView cmd : commanders) {
                int dmg = p.getCommanderDamage(cmd);
                if (dmg > 0) {
                    cmdDamage.put(cmd.getId(), dmg);
                }
            }
        }
        Map<String, Integer> mana = new LinkedHashMap<>();
        mana.put("W", p.getMana(MagicColor.WHITE));
        mana.put("U", p.getMana(MagicColor.BLUE));
        mana.put("B", p.getMana(MagicColor.BLACK));
        mana.put("R", p.getMana(MagicColor.RED));
        mana.put("G", p.getMana(MagicColor.GREEN));
        mana.put("C", p.getMana(MagicColor.COLORLESS));

        FCollectionView<CardView> library = p.getLibrary();
        return new Snapshot.PlayerSnap(
                p.getId(),
                p.getName(),
                p.isAI(),
                p.getLife(),
                counters(p.getCounters()),
                cmdDamage,
                ids(p.getHand(), ctx, cards),
                library == null ? 0 : library.size(),
                ids(p.getGraveyard(), ctx, cards),
                ids(p.getExile(), ctx, cards),
                ids(p.getCommand(), ctx, cards),
                ids(p.getBattlefield(), ctx, cards),
                mana,
                p.getHasPriority(),
                ctx.highlighted().test(p) ? Boolean.TRUE : null);
    }

    /** Sammelt IDs einer Zone und legt jede Karte einmal im Wörterbuch ab. */
    private static List<Integer> ids(Iterable<CardView> zone, ViewContext ctx, Map<Integer, Snapshot.CardSnap> cards) {
        List<Integer> out = new ArrayList<>();
        if (zone == null) return out;
        for (CardView cv : zone) {
            out.add(cv.getId());
            cards.computeIfAbsent(cv.getId(), id -> cardSnap(cv, ctx));
        }
        return out;
    }

    public static Snapshot.CardSnap cardSnap(CardView cv, ViewContext ctx) {
        if (!ctx.mayView().test(cv)) {
            return Snapshot.CardSnap.hidden(cv.getId());
        }
        CardStateView st = cv.getCurrentState();
        boolean creature = st.isCreature();
        List<Integer> attachments = new ArrayList<>();
        if (cv.hasCardAttachments()) {
            for (CardView a : cv.getAttachedCards()) {
                attachments.add(a.getId());
            }
        }
        CardView attachedTo = cv.getAttachedTo();
        boolean onBattlefield = cv.getZone() != null && cv.getZone().name().equals("Battlefield");
        return new Snapshot.CardSnap(
                cv.getId(),
                false,
                st.getName(),
                st.getImageKey(),
                cv.getController() == null ? null : cv.getController().getId(),
                cv.getOwner() == null ? null : cv.getOwner().getId(),
                cv.getZone() == null ? null : cv.getZone().name(),
                onBattlefield ? cv.isTapped() : null,
                onBattlefield && creature ? cv.isSick() : null,
                creature ? st.getPower() : null,
                creature ? st.getToughness() : null,
                onBattlefield ? cv.getDamage() : null,
                counters(cv.getCounters()),
                attachedTo == null ? null : attachedTo.getId(),
                attachments.isEmpty() ? null : attachments,
                cv.getText(),
                st.getType() == null ? null : st.getType().toString(),
                st.getManaCost() == null || st.getManaCost().isNoCost() ? null : st.getManaCost().getShortString(),
                cv.isAttacking() ? Boolean.TRUE : null,
                cv.isBlocking() ? Boolean.TRUE : null,
                cv.isToken() ? Boolean.TRUE : null,
                ctx.selectable().test(cv) ? Boolean.TRUE : null,
                ctx.weaklySelectable().test(cv) ? Boolean.TRUE : null,
                ctx.highlighted().test(cv) ? Boolean.TRUE : null);
    }

    private static Map<String, Integer> counters(Multiset<CounterType> counters) {
        if (counters == null || counters.isEmpty()) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Multiset.Entry<CounterType> e : counters.entrySet()) {
            if (e.getCount() > 0) {
                out.put(e.getElement().getName(), e.getCount());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Snapshot.StackSnap stackItem(int index, StackItemView si, ViewContext ctx,
                                                Map<Integer, Snapshot.CardSnap> cards) {
        CardView src = si.getSourceCard();
        if (src != null) {
            cards.computeIfAbsent(src.getId(), id -> cardSnap(src, ctx));
        }
        List<Integer> tc = new ArrayList<>();
        if (si.getTargetCards() != null) {
            for (CardView c : si.getTargetCards()) {
                tc.add(c.getId());
                cards.computeIfAbsent(c.getId(), id -> cardSnap(c, ctx));
            }
        }
        List<Integer> tp = new ArrayList<>();
        if (si.getTargetPlayers() != null) {
            for (PlayerView p : si.getTargetPlayers()) {
                tp.add(p.getId());
            }
        }
        return new Snapshot.StackSnap(
                index,
                si.getText(),
                src == null ? null : src.getId(),
                si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getId(),
                tc,
                tp);
    }
}
