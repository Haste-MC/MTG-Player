package mtgplayer.images;

import forge.StaticData;
import forge.item.IPaperCard;
import forge.item.PaperCard;

import java.util.Comparator;
import java.util.Optional;

/**
 * Eine Karte ohne Sammlernummer aus Forges Datenbank - fuer die Tests des Namens-Rueckfalls.
 *
 * <p><b>Warum nicht einfach ein Name im Test.</b> Genau das stand hier vorher: "Avacyn, Angel of
 * Horror" und "Samut, Hazoret's Champion", damals Vorschaukarten aus {@code cardsfolder/upcoming}
 * ohne Druck. Mit Forge 2.0.15 sind beide Sets erschienen, beide Karten haben eine Sammlernummer, und
 * drei Tests waren rot - ohne dass sich an der Bridge etwas geaendert hatte. Welche Karte noch
 * unveroeffentlicht ist, entscheidet jede Forge-Fassung neu; ein Test darf sich darauf nicht stuetzen.
 * Gesucht wird deshalb die EIGENSCHAFT (keine Sammlernummer), nicht der Name.</p>
 */
final class Vorschaukarten {

    private Vorschaukarten() { }

    /**
     * Eine Karte ohne Sammlernummer, nach Namen die erste - damit zwei Laeufe dieselbe nehmen.
     *
     * @throws AssertionError wenn Forge keine einzige solche Karte kennt; dann ist nicht der Test
     *                        kaputt, sondern die Annahme, dass es Vorschaukarten gibt
     */
    static PaperCard irgendeine() {
        Optional<PaperCard> pc = StaticData.instance().getCommonCards().getUniqueCards().stream()
                .filter(c -> IPaperCard.NO_COLLECTOR_NUMBER.equals(c.getCollectorNumber()))
                .min(Comparator.comparing(PaperCard::getName));
        if (pc.isEmpty()) {
            throw new AssertionError("Forge kennt keine Karte ohne Sammlernummer mehr -"
                    + " cardsfolder/upcoming leer oder alle Sets erschienen?");
        }
        return pc.get();
    }
}
