<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { Plus, Check, Copy, FolderOpen } from 'lucide-svelte';
	import { getVisits, type Visit } from '../../lib/visits';
	import { getStorage, onStorageChanged } from '../../lib/storage';
	import {
		editMode as detectEditMode,
		type EditMode,
		getConnectedHandle,
		connectFile,
		addSite
	} from '../../lib/whitelistFile';

	// Runes mode on purpose — see options/App.svelte for the cautionary tale.
	let mode = $state<EditMode>('none');
	let visits = $state<Visit[]>([]);
	let whitelist = $state<string[]>([]);
	let fileConnected = $state(false);
	let copiedHost = $state('');
	let addedHost = $state('');
	let refreshTimer: ReturnType<typeof setInterval> | null = null;

	const canOfferAdd = $derived(mode !== 'none');

	const unsubscribe = onStorageChanged((changes) => {
		if (changes.whitelist) whitelist = changes.whitelist.newValue ?? [];
	});
	onDestroy(() => {
		unsubscribe();
		if (refreshTimer) clearInterval(refreshTimer);
	});

	async function refresh() {
		visits = await getVisits();
	}

	onMount(async () => {
		const data = await getStorage('whitelist');
		whitelist = data.whitelist;
		mode = await detectEditMode();
		if (mode === 'picker') fileConnected = (await getConnectedHandle()) !== null;
		await refresh();
		refreshTimer = setInterval(refresh, 5000);
	});

	function isWhitelisted(host: string): boolean {
		return whitelist.some((site) => host === site || host.endsWith(`.${site}`));
	}

	function timeOf(at: number): string {
		return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	}

	async function connect() {
		try {
			await connectFile();
			fileConnected = true;
		} catch {
			// Picker dismissed — nothing to do.
		}
	}

	async function add(host: string) {
		if (mode === 'picker' && !fileConnected) {
			await connect();
			if (!fileConnected) return;
		}
		if (await addSite(host)) {
			addedHost = host;
			setTimeout(() => {
				if (addedHost === host) addedHost = '';
			}, 1500);
		}
	}

	async function copyHost(host: string) {
		await navigator.clipboard.writeText(host);
		copiedHost = host;
		setTimeout(() => {
			if (copiedHost === host) copiedHost = '';
		}, 1500);
	}
</script>

<main class="mx-auto max-w-[640px] px-8 py-12">
	<header class="mb-8">
		<div
			class="text-[11px] font-medium uppercase tracking-[0.2em] mb-2"
			style:color="var(--color-accent)"
		>
			Coach
		</div>
		<h1 class="text-4xl font-extralight tracking-tight" style:color="var(--color-ink)">
			Recent visits
		</h1>
		<p class="text-sm mt-3 max-w-[60ch]" style:color="var(--color-ink-muted)">
			The last {visits.length === 100 ? '100' : visits.length} site visits this browser session
			— blocked ones marked, one click to whitelist. Kept in memory only; closing the browser
			clears it.
		</p>
	</header>

	{#if mode === 'picker' && !fileConnected && visits.length > 0}
		<button
			type="button"
			class="mb-6 inline-flex items-center gap-2 text-sm rounded-lg px-3 py-2"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
			onclick={connect}
		>
			<FolderOpen size={14} />
			Connect the whitelist file to add sites from here
		</button>
	{/if}

	{#if visits.length === 0}
		<p class="text-sm" style:color="var(--color-ink-subtle)">
			Nothing yet — browse a little and come back.
		</p>
	{:else}
		<ul class="divide-y" style:border-color="var(--color-line)">
			{#each visits as visit}
				<li class="flex items-center gap-3 py-2.5" style:border-color="var(--color-line)">
					<span class="text-xs tabular-nums shrink-0" style:color="var(--color-ink-subtle)">
						{timeOf(visit.at)}
					</span>
					<span class="text-[15px] truncate" style:color="var(--color-ink)">
						{visit.host}
					</span>
					{#if visit.blocked}
						<span
							class="text-[10px] font-medium uppercase tracking-wider rounded px-1.5 py-0.5 shrink-0"
							style:color="var(--color-bad)"
							style:background-color="var(--color-surface-2)"
						>
							blocked
						</span>
					{/if}
					<span class="flex-1"></span>
					{#if isWhitelisted(visit.host)}
						<span
							class="inline-flex items-center gap-1 text-xs shrink-0"
							style:color="var(--color-good)"
						>
							<Check size={12} /> listed
						</span>
					{:else if canOfferAdd}
						<button
							type="button"
							class="inline-flex items-center gap-1 text-xs rounded-md px-2 py-1 shrink-0 transition-colors"
							style:background-color="var(--color-surface-2)"
							style:color={addedHost === visit.host
								? 'var(--color-good)'
								: 'var(--color-ink-muted)'}
							onclick={() => add(visit.host)}
							title="Add to the whitelist file"
						>
							{#if addedHost === visit.host}
								<Check size={12} /> Added
							{:else}
								<Plus size={12} /> Whitelist
							{/if}
						</button>
					{:else}
						<button
							type="button"
							class="inline-flex items-center gap-1 text-xs rounded-md px-2 py-1 shrink-0 transition-colors"
							style:background-color="var(--color-surface-2)"
							style:color={copiedHost === visit.host
								? 'var(--color-good)'
								: 'var(--color-ink-muted)'}
							onclick={() => copyHost(visit.host)}
							title="No file access here — copies the hostname for the whitelist file (run npm run install:host to enable one-click adds)"
						>
							{#if copiedHost === visit.host}
								<Check size={12} /> Copied
							{:else}
								<Copy size={12} /> Copy
							{/if}
						</button>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</main>
