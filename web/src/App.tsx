import { useStore } from "./store";
import Lobby from "./components/Lobby";
import Table from "./components/Table";
import ChoiceDialog from "./components/ChoiceDialog";

export default function App() {
  const screen = useStore((s) => s.screen);
  const choice = useStore((s) => s.choice);
  return (
    <>
      {screen === "lobby" ? <Lobby /> : <Table />}
      {choice && <ChoiceDialog choice={choice} />}
    </>
  );
}
