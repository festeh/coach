import assert from "node:assert/strict";
import { test } from "node:test";
import { groupByDay, releaseLabel, type Release } from "../src/lib/releases";

function release(id: number, created: Date, kind = "grant", source = "chat"): Release {
  return {
    id,
    kind,
    source,
    user_message: "one video",
    agent_message: "Enjoy it.",
    duration_seconds: 600,
    created: created.toISOString()
  };
}

const NOW = new Date(2026, 9, 12, 15, 0);

test("rows fold into local days, newest first, labelled relative to now", () => {
  const days = groupByDay(
    [
      release(4, new Date(2026, 9, 12, 14, 0)),
      release(3, new Date(2026, 9, 12, 9, 30)),
      release(2, new Date(2026, 9, 11, 22, 0)),
      release(1, new Date(2026, 9, 8, 12, 0))
    ],
    NOW
  );

  assert.deepEqual(
    days.map((d) => [d.key, d.releases.map((r) => r.id)]),
    [
      ["2026-10-12", [4, 3]],
      ["2026-10-11", [2]],
      ["2026-10-08", [1]]
    ]
  );
  assert.equal(days[0].label, "Today");
  assert.equal(days[1].label, "Yesterday");
  assert.notEqual(days[2].label, "Today");
});

test("a day split across pages continues when the older page is appended", () => {
  const firstPage = [release(3, new Date(2026, 9, 12, 14, 0)), release(2, new Date(2026, 9, 12, 10, 0))];
  const olderPage = [release(1, new Date(2026, 9, 12, 8, 0))];

  const days = groupByDay([...firstPage, ...olderPage], NOW);

  assert.equal(days.length, 1);
  assert.deepEqual(days[0].releases.map((r) => r.id), [3, 2, 1]);
});

test("labels say how the lock came off", () => {
  const at = new Date(2026, 9, 12);
  assert.equal(releaseLabel(release(1, at)), "OK");
  assert.equal(releaseLabel(release(1, at, "grant", "fallback")), "fallback");
  assert.equal(releaseLabel(release(1, at, "grant", "agent")), "granted");
  assert.equal(releaseLabel(release(1, at, "override", "agent")), "override");
  assert.equal(releaseLabel(release(1, at, "short_unblock", "agent")), "peek");
  assert.equal(releaseLabel(release(1, at, "denial", "agent")), "denied");
});
