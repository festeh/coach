import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { blockedTargetOf, blockPage, cancelPendingBlock, forgetTab, noteTabCreated } from "../src/lib/blocking";

let tabId = 0;
let attached: boolean;
let storage: { focusing: boolean; agent_release_time_left: number | null; whitelist: string[] };
let redirects: string[];
let api: {
  debugger?: { getTargets: () => Promise<{ tabId: number; attached: boolean }[]> };
  storage: { local: { get: () => Promise<typeof storage> } };
  runtime: { getURL: (path: string) => string };
  tabs: { update: (id: number, options: { url: string }) => Promise<void> };
};

beforeEach(() => {
  tabId++;
  attached = false;
  storage = { focusing: true, agent_release_time_left: null, whitelist: ["allowed.example"] };
  redirects = [];
  api = {
    debugger: { getTargets: async () => [{ tabId, attached }] },
    storage: { local: { get: async () => ({ ...storage }) } },
    runtime: { getURL: path => `chrome-extension://coach${path}` },
    tabs: { update: async (_id, options) => { redirects.push(options.url); } },
  };
  (globalThis as unknown as { browser: typeof api }).browser = api;
});

afterEach(() => forgetTab(tabId));

const visit = (url = "https://blocked.example/") => blockPage({ tabId, url });

test("an attached agent can navigate to an otherwise blocked URL", async () => {
  attached = true;
  assert.equal((await visit()).blocked, false);
  assert.deepEqual(redirects, []);
});

test("ordinary tabs still redirect into Coach", async () => {
  assert.deepEqual(await visit(), { blocked: true, target: "blocked.example" });
  assert.deepEqual(redirects, ["chrome-extension://coach/chat.html?blocked=https%3A%2F%2Fblocked.example%2F"]);
});

test("the chat page reads back the full blocked URL", async () => {
  await visit("https://blocked.example/watch?v=1#t=30");
  const target = blockedTargetOf(new URL(redirects[0]).search);
  assert.deepEqual(target, { url: "https://blocked.example/watch?v=1#t=30", host: "blocked.example" });
});

test("the chat page never navigates to a non-web blocked target", () => {
  assert.equal(blockedTargetOf("?blocked=javascript%3Aalert(1)"), null);
  assert.equal(blockedTargetOf("?blocked=blocked.example"), null);
  assert.equal(blockedTargetOf(""), null);
});

test("another tab's debugger does not exempt this tab", async () => {
  api.debugger!.getTargets = async () => [{ tabId: tabId + 100, attached: true }];
  assert.equal((await visit()).blocked, true);
});

test("an agent attaching after a new tab starts navigating is allowed", async () => {
  noteTabCreated(tabId);
  const result = visit();
  await delay(75);
  attached = true;
  assert.equal((await result).blocked, false);
  assert.deepEqual(redirects, []);
});

test("new ordinary tabs are blocked once the bounded grace period ends", async () => {
  noteTabCreated(tabId);
  const started = Date.now();
  assert.equal((await visit()).blocked, true);
  assert.ok(Date.now() - started >= 950, "must wait for possible agent attachment");
  assert.equal(redirects.length, 1);
});

test("a newer allowed navigation cancels an older pending redirect", async () => {
  noteTabCreated(tabId);
  const oldVisit = visit();
  await delay(10);
  assert.equal((await visit("https://allowed.example/")).blocked, false);
  assert.equal((await oldVisit).blocked, false);
  assert.deepEqual(redirects, []);
});

test("navigation to a browser page cancels a pending redirect", async () => {
  noteTabCreated(tabId);
  const result = visit();
  await delay(10);
  cancelPendingBlock(tabId);
  assert.equal((await result).blocked, false);
  assert.deepEqual(redirects, []);
});

test("closing a tab cancels a pending redirect", async () => {
  noteTabCreated(tabId);
  const result = visit();
  await delay(10);
  forgetTab(tabId);
  assert.equal((await result).blocked, false);
  assert.deepEqual(redirects, []);
});

test("a release during the grace period is respected", async () => {
  noteTabCreated(tabId);
  const result = visit();
  await delay(10);
  storage.focusing = false;
  storage.agent_release_time_left = 30;
  assert.equal((await result).blocked, false);
  assert.deepEqual(redirects, []);
});

test("a whitelist edit during the grace period is respected", async () => {
  noteTabCreated(tabId);
  const result = visit();
  await delay(10);
  storage.whitelist.push("blocked.example");
  assert.equal((await result).blocked, false);
  assert.deepEqual(redirects, []);
});

test("detaching the debugger restores blocking on the next navigation", async () => {
  attached = true;
  assert.equal((await visit()).blocked, false);
  attached = false;
  assert.equal((await visit()).blocked, true);
});

test("Firefox without the debugger API still blocks normally", async () => {
  delete api.debugger;
  noteTabCreated(tabId);
  assert.equal((await visit()).blocked, true);
  assert.equal(redirects.length, 1);
});

test("a debugger API failure does not exempt the tab", async (t) => {
  t.mock.method(console, "warn", () => {});
  api.debugger!.getTargets = async () => { throw new Error("permission unavailable"); };
  assert.equal((await visit()).blocked, true);
});
