package coach

import (
	"time"

	"coach/internal/db"
)

const (
	// OverrideSeconds is what an override buys: the lock off for 15 minutes.
	OverrideSeconds = 15 * 60

	// CooldownStepSeconds is the price of habit. The first override of each local
	// day is free; every one after it makes the next wait 30 seconds longer.
	CooldownStepSeconds = 30

	// ShortUnblockSeconds is the escape hatch during a cooldown — enough to read
	// one thing, not enough to settle in.
	ShortUnblockSeconds = 30

	// ShortUnblockLimit caps the hatch per cooldown window. It costs nothing, so
	// without a cap it would be the cooldown's way out: unlimited access in
	// 30-second slices.
	ShortUnblockLimit = 2
)

// OverrideCooldown is the price of the next override as the clients see it.
type OverrideCooldown struct {
	OverridesToday int `json:"overrides_today"`
	// CooldownSeconds is the current cooldown's full length.
	CooldownSeconds int `json:"override_cooldown_seconds"`
	// RemainingSeconds is how much of it is left, 0 when clear.
	RemainingSeconds int `json:"override_cooldown_remaining"`
	// NextCooldownSeconds is what taking one more override would cost afterwards.
	// Sent rather than derived so the escalation rule lives in one place: a client
	// computing it would need its own copy of the step, free to drift from this one.
	NextCooldownSeconds int `json:"override_next_cooldown_seconds"`
	ShortUnblocksLeft   int `json:"short_unblocks_left"`
}

// Active reports whether an override would be refused right now.
func (c OverrideCooldown) Active() bool { return c.RemainingSeconds > 0 }

// overrideLedger is the mutable half: what the day has spent so far.
type overrideLedger struct {
	// day is the local midnight these counters belong to. A later day zeroes them.
	day       time.Time
	overrides int
	// lastOverrideEnd is when the last override's release lapses. The cooldown
	// runs from there rather than from the tap, because 30 seconds measured from
	// the tap would expire 870 seconds before the release it followed and would
	// never stop anything. A coach grant that extends the release past this point
	// lets the cooldown tick while the lock is still off, which costs nothing:
	// nobody needs an override during a release.
	lastOverrideEnd time.Time
	// shortUnblocksUsed counts hatch uses in the current cooldown window only.
	shortUnblocksUsed int
}

// onDay returns the ledger as it stands on now's local day, zeroed if the
// counters belong to an earlier one. This is what makes the first override of
// each day free.
func (l overrideLedger) onDay(now time.Time) overrideLedger {
	start := db.LocalDayStart(now)
	if l.day.Equal(start) {
		return l
	}
	return overrideLedger{day: start}
}

// computeCooldown prices the next override against a ledger already normalised
// to now's day.
func computeCooldown(now time.Time, ledger overrideLedger) OverrideCooldown {
	cooldown := OverrideCooldown{
		OverridesToday:      ledger.overrides,
		CooldownSeconds:     ledger.overrides * CooldownStepSeconds,
		NextCooldownSeconds: (ledger.overrides + 1) * CooldownStepSeconds,
	}
	if cooldown.CooldownSeconds > 0 {
		endsAt := ledger.lastOverrideEnd.Add(time.Duration(cooldown.CooldownSeconds) * time.Second)
		if left := endsAt.Sub(now); left > 0 {
			// Rounded up, so a cooldown with half a second to run still reads as
			// 1 rather than as clear. Callers gate on RemainingSeconds, and it
			// must not say 0 while an override would still be refused.
			cooldown.RemainingSeconds = int((left + time.Second - 1) / time.Second)
		}
	}
	// The hatch exists to soften a cooldown, so it only opens while one runs.
	if cooldown.Active() {
		if left := ShortUnblockLimit - ledger.shortUnblocksUsed; left > 0 {
			cooldown.ShortUnblocksLeft = left
		}
	}
	return cooldown
}
