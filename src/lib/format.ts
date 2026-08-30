// M:SS for ticking countdowns, where formatTime's "1m" would sit frozen.
export function formatCountdown(seconds: number): string {
	const minutes = Math.floor(seconds / 60);
	const secs = seconds % 60;
	return `${minutes}:${String(secs).padStart(2, '0')}`;
}

export function formatTime(seconds: number): string {
	const hours = Math.floor(seconds / 3600);
	const minutes = Math.floor((seconds % 3600) / 60);
	const secs = seconds % 60;

	if (hours > 0) return `${hours}h ${minutes}m`;
	if (minutes > 0) return `${minutes}m`;
	return `${secs}s`;
}
