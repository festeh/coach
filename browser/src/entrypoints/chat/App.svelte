<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { SendHorizontal, RefreshCw, ShieldOff, Trash2, Eye } from 'lucide-svelte';
	import { CoachState } from '../../lib/coachState.svelte';
	import { OverridePhase } from '../../lib/background/types';
	import { ChatClient } from '../../lib/chat/client.svelte';
	import { chatWsUrl } from '../../lib/chat/protocol';
	import { getOrCreateThreadId } from '../../lib/chat/thread';
	import { formatCountdown, formatTime } from '../../lib/format';

	const agentsUrl = import.meta.env.VITE_AGENTS as string;
	const coachToken = (import.meta.env.VITE_COACH_TOKEN as string | undefined) ?? '';

	const coach = new CoachState();
	// Set when the blocked-page redirect brought the user here: which wall they hit.
	const blockedHost = new URLSearchParams(location.search).get('blocked') ?? '';
	let client = $state<ChatClient | null>(null);
	let draft = $state('');
	let scroller = $state<HTMLElement | null>(null);

	const phase = $derived(coach.livePhase);
	// Cooling waits; idle needs the reason typed; ready takes a bare click.
	const overrideDisabled = $derived(
		phase === OverridePhase.Cooling || (phase === OverridePhase.Idle && !draft.trim())
	);

	onMount(async () => {
		const threadId = await getOrCreateThreadId();
		client = new ChatClient(chatWsUrl(agentsUrl, threadId, coachToken));
		client.connect();
	});

	onDestroy(() => client?.disconnect());

	// Keep the newest message in view as history loads and chunks stream in.
	$effect(() => {
		client?.messages;
		client?.streamText;
		const el = scroller;
		if (el) requestAnimationFrame(() => el.scrollTo({ top: el.scrollHeight }));
	});

	// The wall came down: go back to the page whose blocking brought us here.
	// Focus gates this too — a release granted mid-focus (impossible now, but
	// stale frames exist) must not navigate into a still-blocked page.
	let wasBlocked = true;
	$effect(() => {
		const open = !coach.focus && coach.agentReleaseTimeLeft !== null;
		if (open && wasBlocked && blockedHost) {
			location.href = `https://${blockedHost}`;
		}
		wasBlocked = !open;
	});

	function submit(event: Event): void {
		event.preventDefault();
		if (!client) return;
		client.send(draft);
		draft = '';
	}

	// One button, whatever the phase; the server decides what the click means.
	// The first click of the day grants outright, later ones arm a countdown
	// (the typed message is the recorded reason either way), and a click on a
	// ripe request redeems it bare. Rides the background's coach socket, so it
	// works even if the agent chat is down — the coach is bypassed, not consulted.
	function override(): void {
		const reason = draft.trim();
		if (phase === OverridePhase.Idle) {
			if (!reason) return;
			browser.runtime.sendMessage({ type: 'override', message: reason });
			draft = '';
			return;
		}
		browser.runtime.sendMessage({ type: 'override', message: '' });
	}

	// The peek: 30 seconds, no reason asked, never refused. The nag of coming
	// back every half-minute is the only price.
	function peek(): void {
		browser.runtime.sendMessage({ type: 'short_unblock' });
	}

	function overrideTitle(): string {
		if (phase === OverridePhase.Ready) {
			return `Take your 15 minutes — ${formatCountdown(coach.liveRedeemRemaining)} left to claim.`;
		}
		if (phase === OverridePhase.Cooling) {
			return `Waiting — ${formatCountdown(coach.liveCooldownRemaining)} until the override is yours.`;
		}
		if (coach.override.overrides_today === 0) {
			return 'Override: take 15 minutes. Your typed message becomes the recorded reason.';
		}
		return `Request override: starts a ${formatTime(coach.override.override_cooldown_seconds)} wait, then come back to take it. Your typed message becomes the recorded reason.`;
	}

	function lockLine(): string {
		if (coach.agentReleaseTimeLeft === null) return 'Agent lock engaged';
		return `Released — ${formatTime(coach.agentReleaseTimeLeft)} left`;
	}

	function clearChat(): void {
		if (confirm('Clear this conversation? The coach forgets this thread; the ledger keeps its rows.')) {
			client?.clear();
		}
	}
</script>

<!-- fixed inset-0: pinned to the viewport itself, immune to %-height chain
     breakage under browser zoom (Firefox detached the chain at ≠100%). -->
<main class="fixed inset-0 flex flex-col max-w-2xl mx-auto">
	<header
		class="shrink-0 flex items-center justify-between px-4 py-3 border-b"
		style:border-color="var(--color-line)"
	>
		<button
			type="button"
			class="p-1.5 rounded-lg transition-colors disabled:opacity-40"
			style:color="var(--color-ink-subtle)"
			disabled={!client || client.status !== 'open'}
			onclick={clearChat}
			title="Clear conversation"
			aria-label="Clear conversation"
		>
			<Trash2 size={14} />
		</button>
		<span class="text-xs" style:color="var(--color-ink-muted)">{lockLine()}</span>
	</header>

	{#if blockedHost}
		<div
			class="shrink-0 px-4 py-2 text-xs text-center border-b"
			style:border-color="var(--color-line)"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
		>
			Blocked: <span style:color="var(--color-bad)">{blockedHost}</span> — state your case, or
			type a reason and request the override.
		</div>
	{/if}

	<section bind:this={scroller} class="flex-1 min-h-0 overflow-y-auto px-4 py-4 flex flex-col gap-3">
		{#if client}
			{#if client.messages.length === 0 && !client.streamText && client.status === 'open'}
				<p class="text-sm text-center my-auto" style:color="var(--color-ink-subtle)">
					State your case. The coach decides.
				</p>
			{/if}

			{#each client.messages as message}
				{#if message.role === 'error'}
					<p class="text-xs text-center" style:color="var(--color-bad)">{message.content}</p>
				{:else if message.role === 'notice'}
					<p class="text-xs text-center" style:color="var(--color-ink-muted)">{message.content}</p>
				{:else}
					<div
						class="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap {message.role ===
						'human'
							? 'self-end'
							: 'self-start'}"
						style:background-color={message.role === 'human'
							? 'var(--color-accent-soft)'
							: 'var(--color-surface-2)'}
					>
						{message.content}
					</div>
				{/if}
			{/each}

			{#if client.streamText}
				<div
					class="max-w-[85%] self-start rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap"
					style:background-color="var(--color-surface-2)"
				>
					{client.streamText}
				</div>
			{:else if client.awaitingReply}
				<div
					class="self-start rounded-2xl px-4 py-2.5 text-sm animate-pulse"
					style:background-color="var(--color-surface-2)"
					style:color="var(--color-ink-muted)"
				>
					…
				</div>
			{/if}

			{#if client.status === 'connecting'}
				<p class="text-xs text-center" style:color="var(--color-ink-subtle)">Connecting…</p>
			{:else if client.status === 'closed'}
				<button
					type="button"
					class="self-center inline-flex items-center gap-2 text-xs rounded-lg px-3 py-1.5"
					style:background-color="var(--color-surface-2)"
					style:color="var(--color-ink-muted)"
					onclick={() => client?.connect()}
				>
					<RefreshCw size={12} />
					Disconnected — reconnect
				</button>
			{/if}
		{/if}
	</section>

	{#if phase !== OverridePhase.Idle || coach.focus}
		<div
			class="shrink-0 px-4 py-2 text-xs text-center border-t"
			style:border-color="var(--color-line)"
			style:background-color="var(--color-surface-2)"
			style:color={phase === OverridePhase.Ready
				? 'var(--color-accent-strong)'
				: 'var(--color-ink-muted)'}
		>
			{#if phase === OverridePhase.Cooling}
				Override in {formatCountdown(coach.liveCooldownRemaining)} — come back for it.
			{:else if phase === OverridePhase.Ready}
				Override ready — {formatCountdown(coach.liveRedeemRemaining)} left to take it.
			{/if}
			{#if coach.focus}
				Focus session running — any release ends it.
			{/if}
		</div>
	{/if}

	<form
		class="shrink-0 flex items-center gap-2 px-4 py-3 border-t"
		style:border-color="var(--color-line)"
		onsubmit={submit}
	>
		<input
			type="text"
			class="flex-1 rounded-xl px-4 py-2.5 text-sm outline-none"
			style:background-color="var(--color-surface-2)"
			placeholder="Ask for a release…"
			bind:value={draft}
			disabled={!client || client.status !== 'open'}
		/>
		<button
			type="submit"
			class="p-2.5 rounded-xl transition-colors disabled:opacity-40"
			style:background-color="var(--color-accent-soft)"
			style:color="var(--color-accent-strong)"
			disabled={!client || client.status !== 'open' || client.awaitingReply || !draft.trim()}
			aria-label="Send"
		>
			<SendHorizontal size={16} />
		</button>
		<button
			type="button"
			class="p-2.5 rounded-xl transition-colors disabled:opacity-40"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
			onclick={peek}
			title="Peek: 30 seconds, no reason asked."
			aria-label="Peek — 30 seconds"
		>
			<Eye size={16} />
		</button>
		<button
			type="button"
			class="p-2.5 rounded-xl transition-colors disabled:opacity-40"
			style:background-color={phase === OverridePhase.Ready
				? 'var(--color-accent-soft)'
				: 'var(--color-surface-2)'}
			style:color="var(--color-bad)"
			disabled={overrideDisabled}
			onclick={override}
			title={overrideTitle()}
			aria-label="Override"
		>
			<ShieldOff size={16} />
		</button>
	</form>
</main>
