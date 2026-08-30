import { onMount } from 'svelte';
import {
	DEFAULT_OVERRIDE_STATE,
	getStorage,
	onStorageChanged,
	type OverrideSnapshot,
	type StorageChanges
} from './storage';
import { OverridePhase } from './background/types';

export class CoachState {
	focus = $state(false);
	connected = $state(false);
	reconnectAt = $state(0);
	agentReleaseTimeLeft = $state<number | null>(null);
	override = $state<OverrideSnapshot>(DEFAULT_OVERRIDE_STATE);

	// Ticked every second so the countdowns decay between server frames.
	private now = $state(Date.now());
	// One server refresh per frame: re-armed whenever a fresh frame lands.
	private refreshAsked = false;

	// Seconds since the override snapshot was written by a server frame.
	private get drift(): number {
		if (this.override.synced_at === 0) return 0;
		return Math.max(0, Math.floor((this.now - this.override.synced_at) / 1000));
	}

	// The countdown as it stands right now, not as it stood at the last frame.
	get liveCooldownRemaining(): number {
		if (this.override.override_phase !== OverridePhase.Cooling) return 0;
		return Math.max(0, this.override.override_cooldown_remaining - this.drift);
	}

	get liveRedeemRemaining(): number {
		if (this.override.override_phase !== OverridePhase.Ready) return 0;
		return Math.max(0, this.override.override_redeem_remaining - this.drift);
	}

	// The phase with local time applied. When a countdown or a redeem window
	// runs out between frames this flips ahead of the server; the tick loop
	// asks the server for a fresh frame at that moment, and the frame that
	// answers restores authority (and brings redeem_remaining with it).
	get livePhase(): OverridePhase {
		const phase = this.override.override_phase;
		if (phase === OverridePhase.Cooling && this.liveCooldownRemaining === 0) {
			return OverridePhase.Ready;
		}
		if (phase === OverridePhase.Ready && this.liveRedeemRemaining === 0) {
			return OverridePhase.Idle;
		}
		return phase;
	}

	private takeSnapshot(snapshot: OverrideSnapshot): void {
		this.override = snapshot;
		this.refreshAsked = false;
		this.now = Date.now();
	}

	constructor() {
		onMount(() => {
			let active = true;

			const readAll = async () => {
				const res = await getStorage(
					'focusing',
					'connected',
					'reconnect_at',
					'agent_release_time_left',
					'override_state'
				);
				if (!active) return;
				this.focus = res.focusing;
				this.connected = res.connected;
				this.reconnectAt = res.reconnect_at;
				this.agentReleaseTimeLeft = res.agent_release_time_left;
				if (res.override_state.synced_at !== this.override.synced_at) {
					this.takeSnapshot(res.override_state);
				}
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

			const tick = setInterval(() => {
				this.now = Date.now();
				// A phase flipped locally: the server knows more (the redeem window
				// length, whether the request survived) — ask it to say so.
				if (this.livePhase !== this.override.override_phase && !this.refreshAsked) {
					this.refreshAsked = true;
					void this.refresh();
				}
			}, 1000);

			const unsubscribe = onStorageChanged(async (changes: StorageChanges) => {
				if (changes.focusing) this.focus = changes.focusing.newValue;
				if (changes.connected) this.connected = changes.connected.newValue;
				if (changes.reconnect_at) this.reconnectAt = changes.reconnect_at.newValue;
				if (changes.agent_release_time_left) this.agentReleaseTimeLeft = changes.agent_release_time_left.newValue;
				if (changes.override_state) this.takeSnapshot(changes.override_state.newValue);
			});

			return () => {
				active = false;
				clearInterval(poll);
				clearInterval(tick);
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
