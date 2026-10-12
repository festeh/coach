<script lang="ts">
	let { connected = false, reconnectAt = 0 }: { connected?: boolean; reconnectAt?: number } =
		$props();

	let countdown = $state(0);

	function updateCountdown() {
		if (connected || !reconnectAt) {
			countdown = 0;
			return;
		}
		countdown = Math.max(0, Math.ceil((reconnectAt - Date.now()) / 1000));
	}

	$effect(() => {
		if (!connected && reconnectAt) {
			updateCountdown();
			const interval = setInterval(updateCountdown, 1000);
			return () => clearInterval(interval);
		}
		countdown = 0;
	});

	async function handleReconnect() {
		if (!connected) {
			try {
				await browser.runtime.sendMessage({ type: 'reconnect' });
			} catch (error) {
				console.error('Error sending reconnect message:', error);
			}
		}
	}

	function statusText(connected: boolean, countdown: number): string {
		if (connected) return 'Connected';
		if (countdown > 0) return `Reconnecting in ${countdown}s`;
		return 'Reconnecting';
	}
</script>

<button
	type="button"
	class="inline-flex items-center gap-2 text-sm transition-colors"
	class:cursor-default={connected}
	class:hover:text-ink={!connected}
	onclick={handleReconnect}
	disabled={connected}
	style:color={connected ? 'var(--color-ink-muted)' : 'var(--color-bad)'}
>
	<span
		class="inline-block w-1.5 h-1.5 rounded-full"
		class:animate-pulse={!connected}
		style:background-color={connected ? 'var(--color-good)' : 'var(--color-bad)'}
	></span>
	<span class="font-medium tabular-nums">{statusText(connected, countdown)}</span>
</button>
