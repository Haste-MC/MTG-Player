package mtgplayer.images;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardEdition;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.ImageUtil;

import java.util.Optional;

/**
 * Forge-imageKey ("c:Name|SET|art", optional "$alt") → Scryfall-Bild-URL.
 *
 * <p>Die eigentliche URL baut Forges {@link ImageUtil#getScryfallDownloadUrl}, damit
 * Sonderfaelle (Split/Transform/Meld/Specialize, umgeschriebene Planechase-Sets, "Funny"-
 * Sammlernummern, ...) nicht hier nochmal nachgebaut werden. Das Set-Kuerzel kommt, wo
 * bekannt, von Forges eigener {@link CardEdition} statt aus dem rohen Edition-Code.</p>
 */
public final class ImageKeys2Scryfall {

    private ImageKeys2Scryfall() { }

    public static Optional<String> url(String imageKey) {
        if (imageKey == null || !imageKey.startsWith(ImageKeys.CARD_PREFIX)) {
            return Optional.empty();
        }
        boolean back = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
        String key = back ? imageKey.substring(0, imageKey.length() - ImageKeys.BACKFACE_POSTFIX.length()) : imageKey;
        PaperCard pc;
        try {
            pc = ImageUtil.getPaperCardFromImageKey(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (pc == null || pc.getEdition() == null) {
            return Optional.empty();
        }
        String num = pc.getCollectorNumber();
        if (num == null || num.isBlank() || IPaperCard.NO_COLLECTOR_NUMBER.equals(num)) {
            return Optional.empty();
        }
        CardEdition ed = StaticData.instance().getCardEdition(pc.getEdition());
        String code = ed != null ? ed.getScryfallCode() : pc.getEdition().toLowerCase();
        String url = "https://api.scryfall.com/cards/"
                + ImageUtil.getScryfallDownloadUrl(pc, back ? "back" : "front", code, "", false);
        return Optional.of(url);
    }
}
