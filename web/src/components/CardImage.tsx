import { useState } from "react";

/**
 * Kartenbild über die Bridge; bei 404 (Token, unbekannt) rendert der Aufrufer den Text-Fallback.
 *
 * Der interne "failed"-Zustand wird NICHT bei einem geänderten imageKey zurückgesetzt - der
 * Aufrufer muss dafür `key={imageKey}` setzen (siehe CardBox), damit React die Komponente bei
 * einer neuen Karte komplett neu montiert statt den alten "failed"-Zustand wiederzuverwenden.
 */
export default function CardImage({ imageKey, className, onFail }: { imageKey?: string; className?: string; onFail?: () => void }) {
  const [failed, setFailed] = useState(false);
  if (!imageKey || failed) return null;
  return (
    <img className={className} src={`/img/${encodeURIComponent(imageKey)}`} alt="" draggable={false}
      onError={() => { setFailed(true); onFail?.(); }} />
  );
}
