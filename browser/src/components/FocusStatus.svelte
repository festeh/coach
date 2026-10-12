<script lang="ts">
	import { Lock } from 'lucide-svelte';
	import { formatTime } from '../lib/format';

	let {
		focus,
		agentReleaseTimeLeft = null,
		compact = false
	}: { focus: boolean; agentReleaseTimeLeft?: number | null; compact?: boolean } = $props();

	const locked = $derived(agentReleaseTimeLeft === null);
	const focusing = $derived(locked || focus);
	const releaseLeft = $derived(!locked && agentReleaseTimeLeft !== null ? agentReleaseTimeLeft : 0);
</script>

{#if compact}
	<div class="inline-flex items-baseline gap-2 text-sm">
		{#if locked}
			<Lock size={11} class="self-center" color="var(--color-accent)" />
		{:else}
			<span
				class="inline-block w-1.5 h-1.5 rounded-full self-center"
				style:background-color={focusing ? 'var(--color-accent)' : 'var(--color-ink-subtle)'}
			></span>
		{/if}
		<span class="text-ink font-medium">{focusing ? 'Focusing' : 'Not focusing'}</span>
		{#if !locked && focus && releaseLeft > 0}
			<span class="text-ink-subtle tabular-nums">
				· {formatTime(releaseLeft)} left
			</span>
		{/if}
	</div>
{:else if locked}
	<div class="flex flex-col items-center text-center select-none gap-3">
		<Lock size={28} color="var(--color-accent)" />
		<div class="text-3xl font-light tracking-tight" style:color="var(--color-ink)">
			Focusing
		</div>
	</div>
{:else if focus}
	<div class="flex flex-col items-center text-center select-none">
		<div
			class="text-[11px] font-medium uppercase tracking-[0.2em]"
			style:color="var(--color-accent)"
		>
			Focusing
		</div>
		{#if releaseLeft > 0}
			<div
				class="mt-2 text-6xl font-light tabular-nums leading-none"
				style:color="var(--color-ink)"
			>
				{formatTime(releaseLeft)}
			</div>
			<div class="mt-3 text-xs tabular-nums" style:color="var(--color-ink-subtle)">
				until lock returns
			</div>
		{/if}
	</div>
{:else}
	<div class="flex flex-col items-center text-center select-none">
		<div
			class="text-[11px] font-medium uppercase tracking-[0.2em]"
			style:color="var(--color-ink-muted)"
		>
			Not focusing
		</div>
	</div>
{/if}
