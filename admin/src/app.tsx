import FocusStatus from "./components/focus-status";
import HistoryTable from "./components/history-table";
import HookManager from "./components/hook-manager";

export default function App() {
  return (
    <div class="container">
      <h1>Coach Admin</h1>
      <FocusStatus />
      <HookManager />
      <HistoryTable />
    </div>
  );
}
