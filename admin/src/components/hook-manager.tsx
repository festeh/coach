import { createSignal, createResource, For, Show } from "solid-js";
import {
  fetchHooks,
  updateHookConfig,
  triggerHook,
  fetchHookContext,
  type HookInfo,
  type ParamDef,
} from "../api";

const FREQUENCY_PRESETS = [
  { label: "15 min", value: "15m" },
  { label: "30 min", value: "30m" },
  { label: "1 hour", value: "1h" },
  { label: "2 hours", value: "2h" },
  { label: "4 hours", value: "4h" },
];

function HookCard(props: { hook: HookInfo; onSaved: () => void }) {
  const cfg = () => props.hook.config;

  const [enabled, setEnabled] = createSignal(cfg()?.enabled ?? false);
  const [firstRun, setFirstRun] = createSignal(cfg()?.first_run ?? "09:00");
  const [lastRun, setLastRun] = createSignal(cfg()?.last_run ?? "21:00");
  const [frequency, setFrequency] = createSignal(cfg()?.frequency ?? "2h");
  const [params, setParams] = createSignal<Record<string, string>>(
    cfg()?.params ?? {}
  );
  const [saving, setSaving] = createSignal(false);
  const [triggering, setTriggering] = createSignal(false);
  const [status, setStatus] = createSignal("");
  const [contextText, setContextText] = createSignal<string | null>(null);
  const [loadingContext, setLoadingContext] = createSignal(false);

  const getParamValue = (p: ParamDef) => params()[p.key] || p.default;

  const setParamValue = (key: string, value: string) => {
    setParams((prev) => ({ ...prev, [key]: value }));
  };

  const save = async () => {
    setSaving(true);
    setStatus("");
    try {
      await updateHookConfig(props.hook.id, {
        enabled: enabled(),
        trigger: "scheduled",
        first_run: firstRun(),
        last_run: lastRun(),
        frequency: frequency(),
        params: params(),
      });
      setStatus("Saved");
      props.onSaved();
    } catch (e: any) {
      setStatus(`Error: ${e.message}`);
    }
    setSaving(false);
  };

  const toggleContext = async () => {
    if (contextText() !== null) {
      setContextText(null);
      return;
    }
    setLoadingContext(true);
    try {
      const text = await fetchHookContext(props.hook.id);
      setContextText(text);
    } catch (e: any) {
      setStatus(`Error: ${e.message}`);
    }
    setLoadingContext(false);
  };

  const trigger = async () => {
    setTriggering(true);
    setStatus("");
    try {
      await triggerHook(props.hook.id);
      setStatus("Triggered");
    } catch (e: any) {
      setStatus(`Error: ${e.message}`);
    }
    setTriggering(false);
  };

  return (
    <div class="hook-card">
      <div class="hook-header">
        <div>
          <strong>{props.hook.name}</strong>
          <p class="muted">{props.hook.description}</p>
        </div>
        <label class="toggle">
          <input
            type="checkbox"
            checked={enabled()}
            onChange={(e) => setEnabled(e.currentTarget.checked)}
          />
          <span>{enabled() ? "Enabled" : "Disabled"}</span>
        </label>
      </div>

      <div class="hook-schedule">
        <h4>Schedule</h4>
        <div class="schedule-grid">
          <div class="field">
            <label>First Run</label>
            <input
              type="time"
              value={firstRun()}
              onInput={(e) => setFirstRun(e.currentTarget.value)}
            />
          </div>
          <div class="field">
            <label>Last Run</label>
            <input
              type="time"
              value={lastRun()}
              onInput={(e) => setLastRun(e.currentTarget.value)}
            />
          </div>
          <div class="field">
            <label>Frequency</label>
            <select
              value={frequency()}
              onChange={(e) => setFrequency(e.currentTarget.value)}
            >
              <For each={FREQUENCY_PRESETS}>
                {(preset) => (
                  <option value={preset.value}>{preset.label}</option>
                )}
              </For>
            </select>
          </div>
        </div>
      </div>

      <Show when={props.hook.params.length > 0}>
        <div class="hook-params">
          <h4>Parameters</h4>
          <For each={props.hook.params}>
            {(param) => (
              <div class="field">
                <label>{param.name}</label>
                {param.type === "textarea" ? (
                  <textarea
                    rows={3}
                    value={getParamValue(param)}
                    onInput={(e) =>
                      setParamValue(param.key, e.currentTarget.value)
                    }
                  />
                ) : param.type === "select" ? (
                  <select
                    value={getParamValue(param)}
                    onChange={(e) =>
                      setParamValue(param.key, e.currentTarget.value)
                    }
                  >
                    <For each={param.options ?? []}>
                      {(opt) => <option value={opt}>{opt}</option>}
                    </For>
                  </select>
                ) : (
                  <input
                    type="text"
                    value={getParamValue(param)}
                    onInput={(e) =>
                      setParamValue(param.key, e.currentTarget.value)
                    }
                  />
                )}
              </div>
            )}
          </For>
        </div>
      </Show>

      <div class="hook-actions">
        <button class="btn-save" onClick={save} disabled={saving()}>
          {saving() ? "Saving..." : "Save"}
        </button>
        <button class="btn-trigger" onClick={trigger} disabled={triggering()}>
          {triggering() ? "Running..." : "Trigger Now"}
        </button>
        <button class="btn-trigger" onClick={toggleContext} disabled={loadingContext()}>
          {loadingContext() ? "Loading..." : contextText() !== null ? "Hide Context" : "Show Context"}
        </button>
        <Show when={status()}>
          <span class="status-msg">{status()}</span>
        </Show>
      </div>
      <Show when={contextText() !== null}>
        <pre class="context-preview">{contextText()}</pre>
      </Show>
    </div>
  );
}

export default function HookManager() {
  const [hooks, { refetch }] = createResource(fetchHooks);

  return (
    <section class="card">
      <h2>Hooks</h2>
      {hooks.loading && <p class="muted">Loading...</p>}
      {hooks.error && <p class="error">Failed to load hooks</p>}
      <Show when={hooks() && hooks()!.length === 0}>
        <p class="muted">No hooks registered</p>
      </Show>
      <For each={hooks()}>
        {(hook) => <HookCard hook={hook} onSaved={refetch} />}
      </For>
    </section>
  );
}
