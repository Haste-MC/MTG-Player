package mtgplayer.images;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardEdition;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.ImageUtil;

import java.util.Optional;

/**
 * Forge-imageKey ("c:Name|SET|art" bzw. "t:script|SET[|nr]" fuer Spielsteine, optional "$alt") → Scryfall-Bild-URL.
 *
 * <p>Die eigentliche URL baut Forges {@link ImageUtil#getScryfallDownloadUrl}, damit
 * Sonderfaelle (Split/Transform/Meld/Specialize, umgeschriebene Planechase-Sets, "Funny"-
 * Sammlernummern, ...) nicht hier nochmal nachgebaut werden. Das Set-Kuerzel kommt, wo
 * bekannt, von Forges eigener {@link CardEdition} statt aus dem rohen Edition-Code.</p>
 */
public final class ImageKeys2Scryfall {

    private ImageKeys2Scryfall() { }

    private static final String SCRYFALL = "https://api.scryfall.com/cards/";

    public static Optional<String> url(String imageKey) {
        if (imageKey == null) {
            return Optional.empty();
        }
        boolean back = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
        String key = back ? imageKey.substring(0, imageKey.length() - ImageKeys.BACKFACE_POSTFIX.length()) : imageKey;
        if (key.startsWith(ImageKeys.TOKEN_PREFIX)) {
            return tokenUrl(key.substring(ImageKeys.TOKEN_PREFIX.length()), back);
        }
        if (!key.startsWith(ImageKeys.CARD_PREFIX)) {
            return Optional.empty();
        }
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
        String url = SCRYFALL + ImageUtil.getScryfallDownloadUrl(pc, back ? "back" : "front", code, "", false);
        return Optional.of(url);
    }

    /**
     * Token-Key ohne Praefix: "script|SET" oder "script|SET|nr". Wie Forges ImageFetcher: Sammlernummer aus
     * dem Key, sonst der erste Eintrag des Skripts im [tokens]-Block der Edition; Set = TokensCode der
     * Edition (Standard "T" + Set). Ohne Edition oder ohne Eintrag gibt es kein Bild.
     */
    private static Optional<String> tokenUrl(String key, boolean back) {
        String[] parts = key.split("\\|");
        if (parts.length < 2 || parts[0].isEmpty()) {
            return Optional.empty();
        }
        CardEdition ed = StaticData.instance().getEditions().get(parts[1]);
        if (ed == null || ed.getType() == CardEdition.Type.CUSTOM_SET) {
            return Optional.empty();
        }
        String num = parts.length > 2 ? parts[2] : null;
        if (num == null || num.isBlank()) {
            for (CardEdition.EditionEntry e : ed.getTokens().get(parts[0])) {
                if (e.collectorNumber() != null && !e.collectorNumber().isEmpty()) {
                    num = e.collectorNumber();
                    break;
                }
            }
        }
        if (num == null) {
            return Optional.empty();
        }
        return Optional.of(SCRYFALL + ImageUtil.getScryfallTokenDownloadUrl(num, ed.getTokensCode(), ed.getCardsLangCode(), back ? "back" : ""));
    }
}
