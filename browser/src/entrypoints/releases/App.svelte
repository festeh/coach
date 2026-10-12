<script lang="ts">
	import { onMount } from 'svelte';
	import { formatTime } from '../../lib/format';
	import { fetchReleases, groupByDay, releaseLabel, type Release } from '../../lib/releases';

	const serverUrl = import.meta.env.VITE_SERVER as string;
	const coachToken = (import.meta.env.VITE_COACH_TOKEN as string | undefined) ?? '';

	// Runes mode on purpose — see options/App.svelte for the cautionary tale.
	let releases = $state<Release[]>([]);
	// The cursor to the next older page; null once the journal's start is shown.
	let nextBefore = $state<number | null>(null);
	let loading = $state(false);
	let loadedOnce = $state(false);
	let error = $state('');

	const days = $derived(groupByDay(releases, new Date()));

	async function loadPage(before: number | null) {
		loading = true;
		error = '';
		try {
			const page = await fetchReleases(serverUrl, coachToken, before);
			releases = before === null ? page.decisions : [...releases, ...page.decisions];
			nextBefore = page.next_before;
			loadedOnce = true;
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	onMount(() => loadPage(null));

	function timeOf(release: Release): string {
		return new Date(release.created).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	}

	function badgeColor(label: string): string {
		switch (label) {
			case 'OK':
			case 'granted':
				return 'var(--color-good)';
			case 'fallback':
				return 'var(--color-accent)';
			case 'denied':
				return 'var(--color-bad)';
			default:
				return 'var(--color-ink-muted)';
		}
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
			Releases
		</h1>
		<p class="text-sm mt-3 max-w-[60ch]" style:color="var(--color-ink-muted)">
			Every time the lock came off: your reason, the coach's reply, and for how long. Newest
			first, from the phone and every browser.
		</p>
	</header>

	{#if loadedOnce && releases.length === 0}
		<p class="text-sm" style:color="var(--color-ink-subtle)">No releases yet.</p>
	{/if}

	{#each days as day (day.key)}
		<section class="mb-8">
			<h2
				class="text-[11px] font-medium uppercase tracking-[0.15em] pb-2 border-b"
				style:color="var(--color-ink-subtle)"
				style:border-color="var(--color-line)"
			>
				{day.label}
			</h2>
			<ul class="divide-y" style:border-color="var(--color-line)">
				{#each day.releases as release (release.id)}
					{@const label = releaseLabel(release)}
					<li class="py-3" style:border-color="var(--color-line)">
						<div class="flex items-center gap-3">
							<span class="text-xs tabular-nums shrink-0" style:color="var(--color-ink-subtle)">
								{timeOf(release)}
							</span>
							<span
								class="text-[10px] font-medium uppercase tracking-wider rounded px-1.5 py-0.5 shrink-0"
								style:color={badgeColor(label)}
								style:background-color="var(--color-surface-2)"
							>
								{label}
							</span>
							{#if release.duration_seconds > 0}
								<span class="text-xs tabular-nums shrink-0" style:color="var(--color-ink-muted)">
									{formatTime(release.duration_seconds)}
								</span>
							{/if}
						</div>
						{#if release.user_message}
							<p class="text-[15px] mt-1.5" style:color="var(--color-ink)">
								{release.user_message}
							</p>
						{/if}
						{#if release.agent_message}
							<p class="text-sm mt-1" style:color="var(--color-ink-muted)">
								{release.agent_message}
							</p>
						{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/each}

	{#if error}
		<p class="text-sm mb-4" style:color="var(--color-bad)">Couldn't load releases: {error}</p>
	{/if}

	{#if !loadedOnce || nextBefore !== null || error}
		<button
			type="button"
			class="text-sm rounded-lg px-3 py-2 transition-colors disabled:opacity-40"
			style:background-color="var(--color-surface-2)"
			style:color="var(--color-ink-muted)"
			disabled={loading}
			onclick={() => loadPage(loadedOnce ? nextBefore : null)}
		>
			{#if loading}
				Loading…
			{:else if !loadedOnce || error}
				Try again
			{:else}
				Load older
			{/if}
		</button>
	{:else if releases.length > 0}
		<p class="text-xs" style:color="var(--color-ink-subtle)">That's everything.</p>
	{/if}
</main>
