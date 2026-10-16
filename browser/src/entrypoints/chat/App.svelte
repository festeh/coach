<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { SendHorizontal, RefreshCw, Trash2 } from 'lucide-svelte';
	import { blockedTargetOf } from '../../lib/blocking';
	import { CoachState } from '../../lib/coachState.svelte';
	import { ChatClient } from '../../lib/chat/client.svelte';
	import { chatWsUrl, liveOfferIndex, releaseFor, type ChatMessage } from '../../lib/chat/protocol';
	import { clockTime, dayKey, dayLabel, formatTime } from '../../lib/format';
	import { getStorage, setStorage } from '../../lib/storage';

	const agentsUrl = import.meta.env.VITE_AGENTS as string;
	const coachToken = (import.meta.env.VITE_COACH_TOKEN as string | undefined) ?? '';

	const coach = new CoachState();
	// Set when the blocked-page redirect brought the user here: the page they
	// asked for, and its site for the coach and the banner.
	const blocked = blockedTargetOf(location.search);
	let client = $state<ChatClient | null>(null);
	let draft = $state('');
	let scroller = $state<HTMLElement | null>(null);

	// Ticked so a stale OK disappears while the page sits open.
	let now = $state(Date.now());
	const offerIndex = $derived(
		client && !client.awaitingReply ? liveOfferIndex(client.messages, now) : -1
	);

	onMount(async () => {
		const { chat_cleared_at } = await getStorage('chat_cleared_at');
		client = new ChatClient(
			chatWsUrl(agentsUrl, coachToken),
			blocked ? { blocked: blocked.host } : undefined,
			chat_cleared_at
		);
		client.connect();
	});

	// A day label goes above the first message of each day.
	function startsDay(messages: ChatMessage[], index: number): boolean {
		const at = messages[index].at;
		if (!Number.isFinite(at)) return false;
		const previous = messages[index - 1]?.at;
		return previous === undefined || dayKey(new Date(at)) !== dayKey(new Date(previous));
	}

	onMount(() => {
		const tick = setInterval(() => (now = Date.now()), 10_000);
		return () => clearInterval(tick);
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
		if (open && wasBlocked && blocked) {
			location.href = blocked.url;
		}
		wasBlocked = !open;
	});

	function submit(event: Event): void {
		event.preventDefault();
		if (!client) return;
		client.send(draft);
		draft = '';
	}

	// The OK: rides the background's coach socket, which opens the lock; the
	// release frame that comes back takes the user on to the blocked page.
	function accept(index: number): void {
		if (!client) return;
		const release = releaseFor(client.messages, index);
		if (!release) return;
		client.takeOffer(index);
		browser.runtime.sendMessage(release);
	}

	function lockLine(): string {
		if (coach.agentReleaseTimeLeft === null) return 'Agent lock engaged';
		return `Released — ${formatTime(coach.agentReleaseTimeLeft)} left`;
	}

	// Tidies this browser only: the coach still remembers the last 30 days.
	function clearChat(): void {
		if (!client || client.awaitingReply) return;
		if (!confirm('Clear the chat in this browser? The coach still remembers it.')) return;
		const now = Date.now();
		client.clear(now);
		setStorage({ chat_cleared_at: now });
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
			disabled={!client || client.messages.length === 0 || client.awaitingReply}
			onclick={clearChat}
			title="Clear conversation"
			aria-label="Clear conversation"
		>
			<Trash2 size={14} />
		</button>
		<span class="text-xs" style:color="var(--color-ink-muted)">{lockLine()}</span>
	</header>

	{#if blocked}
		<div
			class="shrink-0 px-4 py-2 text-xs text-center border-b"
			style:border-color="var(--color-line)"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
		>
			What brings you to <span style:color="var(--color-ink)">{blocked.host}</span>?
		</div>
	{/if}

	<section bind:this={scroller} class="flex-1 min-h-0 overflow-y-auto px-4 py-4 flex flex-col gap-3">
		{#if client}
			{#if client.messages.length === 0 && !client.streamText && client.status === 'open'}
				<p class="text-sm text-center my-auto" style:color="var(--color-ink-subtle)">
					Tell the coach why you're here. Any honest reason works.
				</p>
			{/if}

			{#each client.messages as message, index}
				{#if startsDay(client.messages, index)}
					<p class="text-xs text-center pt-1" style:color="var(--color-ink-subtle)">
						{dayLabel(new Date(message.at), new Date(now))}
					</p>
				{/if}
				{#if message.role === 'error'}
					<p class="text-xs text-center" style:color="var(--color-bad)">{message.content}</p>
				{:else if message.role === 'notice'}
					<p class="text-xs text-center" style:color="var(--color-ink-muted)">{message.content}</p>
				{:else if message.content}
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
						{#if Number.isFinite(message.at)}
							<span class="block mt-1 text-right text-[10px]" style:color="var(--color-ink-subtle)">
								{clockTime(message.at)}
							</span>
						{/if}
					</div>
				{/if}
				{#if index === offerIndex && message.offer}
					<button
						type="button"
						class="{message.role === 'notice'
							? 'self-center'
							: 'self-start'} rounded-xl px-4 py-2 text-sm font-medium transition-colors"
						style:background-color="var(--color-accent-soft)"
						style:color="var(--color-accent-strong)"
						onclick={() => accept(index)}
					>
						OK · {message.offer.minutes} min
					</button>
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

	{#if coach.focus}
		<div
			class="shrink-0 px-4 py-2 text-xs text-center border-t"
			style:border-color="var(--color-line)"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
		>
			Focus session running — any release ends it.
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
			placeholder="Your reason…"
			bind:value={draft}
			disabled={!client || client.status === 'connecting'}
		/>
		<button
			type="submit"
			class="p-2.5 rounded-xl transition-colors disabled:opacity-40"
			style:background-color="var(--color-accent-soft)"
			style:color="var(--color-accent-strong)"
			disabled={!client || client.status === 'connecting' || client.awaitingReply || !draft.trim()}
			aria-label="Send"
		>
			<SendHorizontal size={16} />
		</button>
	</form>
</main>
