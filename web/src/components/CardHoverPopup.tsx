import { useEffect, useRef, useState } from "react";
import { useStore } from "../store";
import { vorschauGroesse, vorschauPosition } from "../cardPreview";
import CardImage from "./CardImage";

/**
 * Die grosse Kartenvorschau am Mauszeiger.
 *
 * <p>Anlass: Kevin am 2026-10-08 - "kann den so klein nicht lesen". Das Bild in der rechten
 * Spalte ist ueber die feste Panel-Hoehe auf etwa 193px Breite begrenzt; Scryfall liefert
 * 488x680, die Aufloesung ist also da, es fehlte nur die Flaeche.
 *
 * <p>Die Seitenleiste bleibt daneben unveraendert: dort steht der Regeltext als echter Text,
 * kopierbar, schaerfer als jedes Bild und auch dann da, wenn das Bild nicht laedt.
 *
 * <p>Verzoegerung: ohne sie flackert die Vorschau beim Ueberstreichen einer Kartenreihe einmal je
 * Karte. {@link #VERZOEGERUNG_MS} ist kurz genug, dass ein Blick darauf sie trotzdem sofort bringt.
 */
const VERZOEGERUNG_MS = 120;

export default function CardHoverPopup() {
  const card = useStore((s) => (s.hover !== undefined ? s.state?.cards[String(s.hover)] : undefined));
  const zeigbar = !!card && !card.faceDown && !!card.imageKey;

  // Letzte Mausposition: ein Zuhoerer, der immer liegt, aber nur dann ein Neuzeichnen ausloest,
  // wenn wirklich eine Karte angefahren ist. Ohne den gemerkten Wert haette die Vorschau im
  // Moment des Erscheinens noch keine Position - der Zeiger steht dann ja gerade still.
  const [maus, setMaus] = useState({ x: 0, y: 0 });
  const mausRef = useRef(maus);
  const zeigbarRef = useRef(zeigbar);
  zeigbarRef.current = zeigbar;

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      mausRef.current = { x: e.clientX, y: e.clientY };
      if (zeigbarRef.current) setMaus(mausRef.current);
    };
    window.addEventListener("mousemove", onMove, { passive: true });
    return () => window.removeEventListener("mousemove", onMove);
  }, []);

  const [sichtbar, setSichtbar] = useState(false);
  useEffect(() => {
    if (!zeigbar) {
      setSichtbar(false);
      return;
    }
    setMaus(mausRef.current);
    const t = window.setTimeout(() => setSichtbar(true), VERZOEGERUNG_MS);
    return () => window.clearTimeout(t);
  }, [zeigbar, card?.id]);

  if (!zeigbar || !sichtbar) return null;
  const fenster = { breite: window.innerWidth, hoehe: window.innerHeight };
  const groesse = vorschauGroesse(fenster.hoehe);
  const { left, top } = vorschauPosition(maus, fenster, groesse);
  return (
    <div className="card-popup" style={{ left, top, width: groesse.breite }} aria-hidden>
      <CardImage key={card.imageKey} imageKey={card.imageKey} className="card-popup-art" />
    </div>
  );
}
