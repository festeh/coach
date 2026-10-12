import { onMount } from 'svelte';
import { getStorage, onStorageChanged, type StorageChanges } from './storage';

export class CoachState {
	focus = $state(false);
	connected = $state(false);
	reconnectAt = $state(0);
	agentReleaseTimeLeft = $state<number | null>(null);

	constructor() {
		onMount(() => {
			let active = true;

			const readAll = async () => {
				const res = await getStorage('focusing', 'connected', 'reconnect_at', 'agent_release_time_left');
				if (!active) return;
				this.focus = res.focusing;
				this.connected = res.connected;
				this.reconnectAt = res.reconnect_at;
				this.agentReleaseTimeLeft = res.agent_release_time_left;
			};

			void readAll();

			// Storage can go stale on a long-lived page: the background may have
			// been asleep when it should have written, or a change event got lost
			// across a worker restart. Anchor on the background's live socket
			// state at mount, then re-read periodically to converge no matter
			// what was missed.
			browser.runtime
				.sendMessage({ type: 'get_connection' })
				.then((res: { connected?: boolean } | undefined) => {
					if (active && typeof res?.connected === 'boolean') this.connected = res.connected;
				})
				.catch(() => {});
			const poll = setInterval(readAll, 10_000);

			const unsubscribe = onStorageChanged(async (changes: StorageChanges) => {
				if (changes.focusing) this.focus = changes.focusing.newValue;
				if (changes.connected) this.connected = changes.connected.newValue;
				if (changes.reconnect_at) this.reconnectAt = changes.reconnect_at.newValue;
				if (changes.agent_release_time_left) this.agentReleaseTimeLeft = changes.agent_release_time_left.newValue;
			});

			return () => {
				active = false;
				clearInterval(poll);
				unsubscribe();
			};
		});
	}

	async refresh(): Promise<void> {
		try {
			await browser.runtime.sendMessage({ type: 'get_focusing' });
		} catch (error) {
			console.error('Error sending get_focusing request:', error);
		}
	}
}
