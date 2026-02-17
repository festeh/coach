import { A } from "@solidjs/router";
import type { RouteSectionProps } from "@solidjs/router";

export default function App(props: RouteSectionProps) {
  return (
    <div class="container">
      <h1>Coach Admin</h1>
      <nav class="nav">
        <A href="/" end>Status</A>
        <A href="/hooks">Hooks</A>
        <A href="/history">History</A>
      </nav>
      {props.children}
    </div>
  );
}
