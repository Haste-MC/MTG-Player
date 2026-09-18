import { useStore } from "./store";
import Lobby from "./components/Lobby";
import Table from "./components/Table";
import ChoiceDialog from "./components/ChoiceDialog";

export default function App() {
  const screen = useStore((s) => s.screen);
  const choice = useStore((s) => s.choices[0]);
  return (
    <>
      {screen === "lobby" ? <Lobby /> : <Table />}
      {choice && <ChoiceDialog key={choice.id} choice={choice} />}
    </>
  );
}
