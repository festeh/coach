import { render } from "solid-js/web";
import { HashRouter, Route } from "@solidjs/router";
import "./index.css";
import App from "./app";
import FocusStatus from "./components/focus-status";
import HookManager from "./components/hook-manager";
import HistoryTable from "./components/history-table";

const root = document.getElementById("app");
if (root) {
  render(
    () => (
      <HashRouter root={App}>
        <Route path="/" component={FocusStatus} />
        <Route path="/hooks" component={HookManager} />
        <Route path="/history" component={HistoryTable} />
      </HashRouter>
    ),
    root
  );
}
