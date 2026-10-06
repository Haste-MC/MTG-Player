package mtgplayer.images;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardSplitType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.ImageUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Forge-imageKey ("c:Name|SET|art" bzw. "t:script|SET[|nr]" fuer Spielsteine, optional "$alt") → Scryfall-Bild-URL.
 *
 * <p>Die eigentliche URL baut Forges {@link ImageUtil#getScryfallDownloadUrl}, damit
 * Sonderfaelle (Split/Transform/Meld/Specialize, umgeschriebene Planechase-Sets, "Funny"-
 * Sammlernummern, ...) nicht hier nochmal nachgebaut werden. Das Set-Kuerzel kommt, wo
 * bekannt, von Forges eigener {@link CardEdition} statt aus dem rohen Edition-Code.</p>
 *
 * <p>Karten ohne Sammlernummer (Vorschaukarten aus {@code cardsfolder/upcoming}, Platzhalter
 * {@link IPaperCard#NO_COLLECTOR_NUMBER}) werden ueber ihren Namen gesucht, siehe {@link #nameUrl}.</p>
 */
public final class ImageKeys2Scryfall {

    private ImageKeys2Scryfall() { }

    private static final String SCRYFALL = "https://api.scryfall.com/cards/";

    /**
     * Ergebnis der Aufloesung: entweder eine URL oder - ohne URL - ein Grund in Klartext. Ein fehlendes Bild
     * ist sonst nicht von einem nie angefragten zu unterscheiden; {@link ImageCache} legt den Grund ins Log.
     *
     * @param reason {@code null}, wenn eine URL vorliegt
     */
    public record Result(Optional<String> url, String reason) {
        static Result of(String url) {
            return new Result(Optional.of(url), null);
        }

        static Result keine(String reason) {
            return new Result(Optional.empty(), reason);
        }
    }

    public static Optional<String> url(String imageKey) {
        return resolve(imageKey).url();
    }

    public static Result resolve(String imageKey) {
        if (imageKey == null) {
            return Result.keine("Bildschluessel ist null");
        }
        boolean back = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
        String key = back ? imageKey.substring(0, imageKey.length() - ImageKeys.BACKFACE_POSTFIX.length()) : imageKey;
        if (key.startsWith(ImageKeys.TOKEN_PREFIX)) {
            return tokenUrl(key.substring(ImageKeys.TOKEN_PREFIX.length()), back);
        }
        if (!key.startsWith(ImageKeys.CARD_PREFIX)) {
            return Result.keine("Bildschluessel ist weder Karte (c:) noch Spielstein (t:)");
        }
        PaperCard pc;
        try {
            pc = ImageUtil.getPaperCardFromImageKey(key);
        } catch (RuntimeException e) {
            return Result.keine("Karte nicht aufloesbar (" + e + ")");
        }
        if (pc == null) {
            return Result.keine("Forge kennt die Karte nicht");
        }
        if (pc.getEdition() == null) {
            return Result.keine("Karte ohne Edition");
        }
        String num = pc.getCollectorNumber();
        if (num == null || num.isBlank() || IPaperCard.NO_COLLECTOR_NUMBER.equals(num)) {
            // Vorschaukarten (cardsfolder/upcoming) haben keinen Druck und keine Sammlernummer; Scryfall kennt
            // sie meist doch, nur unter ihrem echten Set - also ueber den Namen.
            return Result.of(nameUrl(pc, back));
        }
        CardEdition ed = StaticData.instance().getCardEdition(pc.getEdition());
        String code = ed != null ? ed.getScryfallCode() : pc.getEdition().toLowerCase();
        return Result.of(SCRYFALL + ImageUtil.getScryfallDownloadUrl(pc, back ? "back" : "front", code, "", false));
    }

    /**
     * Rueckfall ohne Sammlernummer: {@code cards/named?exact=<Name>}. Das {@code face} folgt Forges Regel im
     * Nummernweg ({@link ImageUtil#getScryfallDownloadUrl}), aber an der Kartenart, nicht nur am Rueckseiten-Suffix:
     * <ul>
     * <li>Meld bekommt immer {@code face=front}, auch fuer die Rueckseite. Forge erreicht die Rueckseite (das
     *     Meld-Ergebnis) dort ueber eine andere Sammlernummer; hier ueber den Namen des Ergebnisses
     *     ({@link ImageUtil#getNameToUse}) - sonst kaeme fuer die Rueckseite nur wieder die Vorderseite.</li>
     * <li>Transform und Modal haben zwei echte Seiten: {@code face=back} nur fuer die Rueckseite.</li>
     * <li>Adventure, Omen, Prepare, Split und Flip sind bei Scryfall ein einziges Bild; {@code face=back} wuerde dort
     *     mit 422 beantwortet. Auch bei verlangter Rueckseite bleibt es {@code face=front}.</li>
     * <li>Specialize und Karten ohne andere Seite bekommen kein {@code face}.</li>
     * </ul>
     */
    static String nameUrl(PaperCard pc, boolean back) {
        CardSplitType split = pc.getRules().getSplitType();
        String name = pc.getName();
        String face = "";
        if (split == CardSplitType.Meld) {
            String ergebnis = back ? ImageUtil.getNameToUse(pc, "back") : null;
            if (ergebnis != null) {
                name = ergebnis;
            }
            face = "&face=front";
        } else if (split != CardSplitType.Specialize && pc.getRules().getOtherPart() != null) {
            // face=back nur bei zwei echten Seiten
            boolean zweiSeiten = split == CardSplitType.Transform || split == CardSplitType.Modal;
            face = back && zweiSeiten ? "&face=back" : "&face=front";
        }
        return SCRYFALL + "named?exact=" + encode(name) + "&format=image&version=normal" + face;
    }

    /** Prozent-Kodierung fuer einen Query-Wert: Leerzeichen als %20 (nicht '+'), Komma/Apostroph/'*' kodiert. */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
    }

    /**
     * Token-Key ohne Praefix: "script|SET" oder "script|SET|nr". Wie Forges ImageFetcher: Sammlernummer aus
     * dem Key, sonst der erste Eintrag des Skripts im [tokens]-Block der Edition; Set = TokensCode der
     * Edition (Standard "T" + Set). Ohne Edition oder ohne Eintrag gibt es kein Bild.
     */
    private static Result tokenUrl(String key, boolean back) {
        String[] parts = key.split("\\|");
        if (parts.length < 2 || parts[0].isEmpty()) {
            return Result.keine("Spielstein-Key ohne Skript oder Edition");
        }
        CardEdition ed = StaticData.instance().getEditions().get(parts[1]);
        if (ed == null || ed.getType() == CardEdition.Type.CUSTOM_SET) {
            return Result.keine("Spielstein-Edition " + parts[1] + " unbekannt oder eigenes Set");
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
            return Result.keine("Spielstein " + parts[0] + " hat in Edition " + parts[1] + " keine Sammlernummer");
        }
        return Result.of(SCRYFALL + ImageUtil.getScryfallTokenDownloadUrl(num, ed.getTokensCode(), ed.getCardsLangCode(), back ? "back" : ""));
    }
}
