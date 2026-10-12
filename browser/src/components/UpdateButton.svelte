<script lang="ts">
	import { RotateCw } from 'lucide-svelte';

	let { updateFocus, iconOnly = false }: { updateFocus: () => void; iconOnly?: boolean } =
		$props();
</script>

{#if iconOnly}
	<button
		type="button"
		class="p-2 rounded-lg transition-colors"
		style:color="var(--color-ink-muted)"
		onclick={updateFocus}
		onmouseenter={(e) => ((e.currentTarget as HTMLElement).style.color = 'var(--color-ink)')}
		onmouseleave={(e) => ((e.currentTarget as HTMLElement).style.color = 'var(--color-ink-muted)')}
		title="Refresh"
		aria-label="Refresh"
	>
		<RotateCw size={16} />
	</button>
{:else}
	<button
		type="button"
		class="inline-flex items-center gap-2 text-sm font-medium transition-colors"
		style:color="var(--color-ink-muted)"
		onclick={updateFocus}
		onmouseenter={(e) => ((e.currentTarget as HTMLElement).style.color = 'var(--color-accent)')}
		onmouseleave={(e) => ((e.currentTarget as HTMLElement).style.color = 'var(--color-ink-muted)')}
	>
		<RotateCw size={14} />
		<span>Refresh</span>
	</button>
{/if}
