import { render } from "solid-js/web";
import { HashRouter, Route } from "@solidjs/router";
import "./index.css";
import App from "./app";
import FocusStatus from "./components/focus-status";
import HistoryTable from "./components/history-table";
import Usage from "./components/usage";

const root = document.getElementById("app");
if (root) {
  render(
    () => (
      <HashRouter root={App}>
        <Route path="/" component={FocusStatus} />
        <Route path="/history" component={HistoryTable} />
        <Route path="/usage" component={Usage} />
      </HashRouter>
    ),
    root
  );
}
