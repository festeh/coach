package coach

import (
	"time"

	"coach/internal/db"
)

const (
	// OverrideSeconds is what a granted override buys: the lock off for 15 minutes.
	OverrideSeconds = 15 * 60

	// CooldownStepSeconds is the price of habit. The first override of each local
	// day is free; every redeemed one makes the next wait 30 seconds longer.
	CooldownStepSeconds = 30

	// RedeemWindowSeconds is how long a ripened request stays claimable. Short on
	// purpose: long enough to notice the wait has ended, too short to arm a
	// request over breakfast and cash it in when the craving hits.
	RedeemWindowSeconds = 2 * 60

	// ShortUnblockSeconds is the peek: enough to read one thing, not enough to
	// settle in. Uncapped by design — having to come back and click again every
	// 30 seconds is the deterrent, and the journal keeps every use visible.
	ShortUnblockSeconds = 30
)

// OverridePhase is where the day's override request stands.
type OverridePhase string

const (
	// OverridePhaseIdle: no live request. A click grants (first of the day) or arms.
	OverridePhaseIdle OverridePhase = "idle"
	// OverridePhaseCooling: a request is armed and its countdown is running.
	OverridePhaseCooling OverridePhase = "cooling"
	// OverridePhaseReady: the countdown lapsed; a click redeems until the window closes.
	OverridePhaseReady OverridePhase = "ready"
)

// OverrideOutcome is what a click of the override button did.
type OverrideOutcome int

const (
	// OverrideGranted: the lock came off for OverrideSeconds.
	OverrideGranted OverrideOutcome = iota
	// OverrideArmed: the countdown started; come back when it lapses.
	OverrideArmed
	// OverrideCooling: the countdown is still running; nothing changed.
	OverrideCooling
	// OverrideNeedsReason: granting or arming is priced at a written reason.
	OverrideNeedsReason
)

// OverrideStatus is the override state as the clients see it, flattened onto
// the focus frame.
type OverrideStatus struct {
	OverridesToday int           `json:"overrides_today"`
	Phase          OverridePhase `json:"override_phase"`
	// CooldownSeconds is the full length of the running countdown while cooling,
	// or of the one the next click would start while idle; 0 means the next
	// click grants outright.
	CooldownSeconds int `json:"override_cooldown_seconds"`
	// CooldownRemaining is how much of the countdown is left, 0 outside cooling.
	CooldownRemaining int `json:"override_cooldown_remaining"`
	// RedeemRemaining is how long the ripe request stays claimable, 0 outside ready.
	RedeemRemaining int `json:"override_redeem_remaining"`
	// NextCooldownSeconds is what the wait becomes after one more redemption.
	// Sent rather than derived so the escalation rule lives in one place: a
	// client computing it would need its own copy of the step, free to drift.
	NextCooldownSeconds int `json:"override_next_cooldown_seconds"`
}

// overrideLedger is the mutable half: what the day has spent so far.
type overrideLedger struct {
	// day is the local midnight these counters belong to. A later day zeroes them.
	day time.Time
	// redeemed counts overrides actually taken. Armed-and-abandoned requests are
	// deliberately absent: escalation prices screen time taken, not cravings felt.
	redeemed int
	// armedAt is when the live request's countdown started; zero means none.
	// The countdown runs from the click, never in the background — a wait nobody
	// is sitting through stops nothing.
	armedAt time.Time
	// armedReason is what was written at arming; the redemption journals it.
	armedReason string
}

// cooldownSeconds is the wait the day's tally currently prices.
func (l overrideLedger) cooldownSeconds() int { return l.redeemed * CooldownStepSeconds }

// at returns the ledger as it stands at now: zeroed if the counters belong to
// an earlier day (which is what makes the first override of each day free),
// and with the live request dropped once its redeem window has closed —
// evaporating unclaimed costs nothing, because walking away is the win.
func (l overrideLedger) at(now time.Time) overrideLedger {
	start := db.LocalDayStart(now)
	if !l.day.Equal(start) {
		return overrideLedger{day: start}
	}
	if !l.armedAt.IsZero() {
		expiry := l.armedAt.Add(time.Duration(l.cooldownSeconds()+RedeemWindowSeconds) * time.Second)
		if !now.Before(expiry) {
			l.armedAt = time.Time{}
			l.armedReason = ""
		}
	}
	return l
}

// overrideStatus reads the phase off a ledger already normalised to now.
func overrideStatus(now time.Time, ledger overrideLedger) OverrideStatus {
	status := OverrideStatus{
		OverridesToday:      ledger.redeemed,
		Phase:               OverridePhaseIdle,
		CooldownSeconds:     ledger.cooldownSeconds(),
		NextCooldownSeconds: (ledger.redeemed + 1) * CooldownStepSeconds,
	}
	if ledger.armedAt.IsZero() {
		return status
	}
	ripeAt := ledger.armedAt.Add(time.Duration(ledger.cooldownSeconds()) * time.Second)
	if left := ripeAt.Sub(now); left > 0 {
		status.Phase = OverridePhaseCooling
		status.CooldownRemaining = ceilSeconds(left)
		return status
	}
	status.Phase = OverridePhaseReady
	status.RedeemRemaining = ceilSeconds(ripeAt.Add(RedeemWindowSeconds * time.Second).Sub(now))
	return status
}

// ceilSeconds rounds up, so a countdown with half a second to run still reads
// as 1 rather than as over. Clients gate on these values, and they must not
// say 0 while the server would still say cooling.
func ceilSeconds(d time.Duration) int {
	return int((d + time.Second - 1) / time.Second)
}
