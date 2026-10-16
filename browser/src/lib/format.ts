export function formatTime(seconds: number): string {
	const hours = Math.floor(seconds / 3600);
	const minutes = Math.floor((seconds % 3600) / 60);
	const secs = seconds % 60;

	if (hours > 0) return `${hours}h ${minutes}m`;
	if (minutes > 0) return `${minutes}m`;
	return `${secs}s`;
}

// A local calendar day, comparable across dates.
export function dayKey(date: Date): string {
	const month = String(date.getMonth() + 1).padStart(2, '0');
	const day = String(date.getDate()).padStart(2, '0');
	return `${date.getFullYear()}-${month}-${day}`;
}

// "Today", "Yesterday", or a short date like "Mon, 12 Oct".
export function dayLabel(date: Date, now: Date): string {
	const key = dayKey(date);
	if (key === dayKey(now)) return 'Today';
	if (key === dayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1))) return 'Yesterday';
	return date.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
}

export function clockTime(at: number): string {
	return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
