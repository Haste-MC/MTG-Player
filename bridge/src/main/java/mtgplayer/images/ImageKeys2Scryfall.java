package mtgplayer.images;

import forge.ImageKeys;
import forge.item.PaperCard;
import forge.util.ImageUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Forge-imageKey ("c:Name|SET|art", optional "$alt") → Scryfall-Bild-URL. */
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
        if (pc == null || pc.getEdition() == null || pc.getCollectorNumber() == null || pc.getCollectorNumber().isBlank()) {
            return Optional.empty();
        }
        String set = pc.getEdition().toLowerCase();
        String num = URLEncoder.encode(pc.getCollectorNumber(), StandardCharsets.UTF_8);
        String url = "https://api.scryfall.com/cards/" + set + "/" + num + "?format=image&version=normal";
        return Optional.of(back ? url + "&face=back" : url);
    }
}
