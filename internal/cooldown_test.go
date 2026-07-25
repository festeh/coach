package coach

import (
	"testing"
	"time"

	"coach/internal/db"
)

// ledgerAt builds a ledger as it would stand on now's day.
func ledgerAt(now time.Time, overrides int, lastEnd time.Time, hatches int) overrideLedger {
	return overrideLedger{
		day:               db.LocalDayStart(now),
		overrides:         overrides,
		lastOverrideEnd:   lastEnd,
		shortUnblocksUsed: hatches,
	}
}

func TestFirstOverrideOfTheDayIsFree(t *testing.T) {
	now := time.Now()

	cooldown := computeCooldown(now, overrideLedger{}.onDay(now))

	if cooldown.Active() {
		t.Errorf("Fresh day should carry no cooldown, got %ds remaining", cooldown.RemainingSeconds)
	}
	if cooldown.CooldownSeconds != 0 {
		t.Errorf("Fresh day should price the next override at 0s, got %ds", cooldown.CooldownSeconds)
	}
}

func TestCooldownGrowsByThirtySecondsPerOverride(t *testing.T) {
	now := time.Now()

	for overrides, want := range map[int]int{1: 30, 2: 60, 3: 90, 10: 300} {
		// Anchored in the future so the whole cooldown is still ahead.
		ledger := ledgerAt(now, overrides, now, 0)
		if got := computeCooldown(now, ledger).CooldownSeconds; got != want {
			t.Errorf("After %d overrides, cooldown should be %ds, got %ds", overrides, want, got)
		}
	}
}

// The anchor is the whole point: measured from the tap, a 30s cooldown would
// expire 870 seconds before the release it followed and would never refuse
// anything.
// The confirm dialog quotes this, and it is sent rather than derived so the phone
// does not need its own copy of the step.
func TestNextCooldownPricesOneMoreOverride(t *testing.T) {
	now := time.Now()

	if got := computeCooldown(now, overrideLedger{}.onDay(now)).NextCooldownSeconds; got != CooldownStepSeconds {
		t.Errorf("With a free override available, the one after it should cost %ds, got %ds",
			CooldownStepSeconds, got)
	}
	if got := computeCooldown(now, ledgerAt(now, 3, now, 0)).NextCooldownSeconds; got != 4*CooldownStepSeconds {
		t.Errorf("After 3 overrides the next should cost %ds, got %ds", 4*CooldownStepSeconds, got)
	}
}

func TestCooldownRunsFromLockReEngageNotFromTheTap(t *testing.T) {
	now := time.Now()
	takenAt := now.Add(-OverrideSeconds * time.Second / 2) // mid-release
	releaseEnds := takenAt.Add(OverrideSeconds * time.Second)

	cooldown := computeCooldown(now, ledgerAt(now, 1, releaseEnds, 0))

	if !cooldown.Active() {
		t.Fatal("Cooldown should still be pending while the release is running")
	}
	// Half the release remains, plus the full 30s cooldown after it.
	wantAtLeast := OverrideSeconds/2 + CooldownStepSeconds - 1
	if cooldown.RemainingSeconds < wantAtLeast {
		t.Errorf("Remaining should span the rest of the release plus the cooldown, want >= %ds, got %ds",
			wantAtLeast, cooldown.RemainingSeconds)
	}
}

func TestCooldownClearsAfterItElapses(t *testing.T) {
	now := time.Now()
	// One override, its release lapsed a minute ago: 30s of cooldown has passed.
	ledger := ledgerAt(now, 1, now.Add(-time.Minute), 0)

	if cooldown := computeCooldown(now, ledger); cooldown.Active() {
		t.Errorf("Cooldown should have elapsed, got %ds remaining", cooldown.RemainingSeconds)
	}
}

func TestRemainingRoundsUpSoAnActiveCooldownNeverReadsZero(t *testing.T) {
	now := time.Now()
	// 500ms left: truncation would report 0 while an override is still refused.
	ledger := ledgerAt(now, 1, now.Add(-CooldownStepSeconds*time.Second).Add(500*time.Millisecond), 0)

	cooldown := computeCooldown(now, ledger)

	if cooldown.RemainingSeconds != 1 {
		t.Errorf("A cooldown with 500ms to run should read 1s, got %ds", cooldown.RemainingSeconds)
	}
	if !cooldown.Active() {
		t.Error("Active() must agree with a non-zero remaining")
	}
}

func TestEscalationResetsOnANewDay(t *testing.T) {
	now := time.Now()
	yesterday := now.AddDate(0, 0, -1)
	stale := overrideLedger{
		day:             db.LocalDayStart(yesterday),
		overrides:       5,
		lastOverrideEnd: yesterday,
	}

	cooldown := computeCooldown(now, stale.onDay(now))

	if cooldown.OverridesToday != 0 {
		t.Errorf("Yesterday's overrides should not count today, got %d", cooldown.OverridesToday)
	}
	if cooldown.Active() {
		t.Error("A new day's first override should be free")
	}
}

func TestShortUnblocksOnlyOfferedDuringACooldown(t *testing.T) {
	now := time.Now()

	clear := computeCooldown(now, overrideLedger{}.onDay(now))
	if clear.ShortUnblocksLeft != 0 {
		t.Errorf("No cooldown means no need for the hatch, got %d left", clear.ShortUnblocksLeft)
	}

	cooling := computeCooldown(now, ledgerAt(now, 1, now, 0))
	if cooling.ShortUnblocksLeft != ShortUnblockLimit {
		t.Errorf("A fresh cooldown window should offer %d hatches, got %d",
			ShortUnblockLimit, cooling.ShortUnblocksLeft)
	}
}

func TestShortUnblocksRunOutAtTheLimit(t *testing.T) {
	now := time.Now()

	spent := computeCooldown(now, ledgerAt(now, 1, now, ShortUnblockLimit))

	if spent.ShortUnblocksLeft != 0 {
		t.Errorf("Hatch should be exhausted at the limit, got %d left", spent.ShortUnblocksLeft)
	}
	if !spent.Active() {
		t.Error("Exhausting the hatch must not end the cooldown")
	}
}

func TestTakeOverrideRefusesWhileCoolingDown(t *testing.T) {
	state := &State{}

	if _, taken := state.TakeOverride(); !taken {
		t.Fatal("First override of the day should be free")
	}
	if agentLocked(state) {
		t.Error("A taken override should release the lock")
	}

	// The second is refused: the first one's release has not even lapsed yet.
	cooldown, taken := state.TakeOverride()
	if taken {
		t.Error("Second override should be refused while the cooldown runs")
	}
	if !cooldown.Active() {
		t.Error("A refusal should report what it is waiting for")
	}
	if cooldown.OverridesToday != 1 {
		t.Errorf("Refused attempts must not count, got %d overrides today", cooldown.OverridesToday)
	}
}

func TestTakeShortUnblockOnlyWorksDuringACooldownAndIsCapped(t *testing.T) {
	state := &State{}

	// No cooldown yet, so the hatch is shut.
	if _, taken := state.TakeShortUnblock(); taken {
		t.Error("Hatch should be shut while no cooldown is running")
	}

	if _, taken := state.TakeOverride(); !taken {
		t.Fatal("First override should be free")
	}

	for i := 1; i <= ShortUnblockLimit; i++ {
		if _, taken := state.TakeShortUnblock(); !taken {
			t.Errorf("Hatch use %d of %d should be allowed", i, ShortUnblockLimit)
		}
	}
	if cooldown, taken := state.TakeShortUnblock(); taken {
		t.Errorf("Hatch should be exhausted after %d uses, %d reported left",
			ShortUnblockLimit, cooldown.ShortUnblocksLeft)
	}

	// Free by design: the hatch must not have lengthened the cooldown.
	if got := state.OverrideCooldown().CooldownSeconds; got != CooldownStepSeconds {
		t.Errorf("Hatch uses must not escalate the cooldown, want %ds got %ds",
			CooldownStepSeconds, got)
	}
}

func TestTakeOverrideAllowedOnceTheCooldownElapses(t *testing.T) {
	state := &State{}

	// One override already taken, its release and cooldown both long past.
	past := time.Now().Add(-time.Hour)
	state.RestoreOverrideLedger(1, &past, 0)

	if cooldown := state.OverrideCooldown(); cooldown.Active() {
		t.Fatalf("An hour-old cooldown should be clear, got %ds", cooldown.RemainingSeconds)
	}
	cooldown, taken := state.TakeOverride()
	if !taken {
		t.Error("Override should be allowed once the cooldown has elapsed")
	}
	if cooldown.OverridesToday != 2 {
		t.Errorf("Should now be 2 overrides today, got %d", cooldown.OverridesToday)
	}
	if cooldown.CooldownSeconds != 2*CooldownStepSeconds {
		t.Errorf("Next cooldown should be %ds, got %ds", 2*CooldownStepSeconds, cooldown.CooldownSeconds)
	}
}

func TestRestoreOverrideLedgerKeepsHatchesSpent(t *testing.T) {
	state := &State{}
	end := time.Now().Add(time.Minute) // release still running, so cooldown pending

	state.RestoreOverrideLedger(2, &end, 1)

	cooldown := state.OverrideCooldown()
	if cooldown.OverridesToday != 2 {
		t.Errorf("Restored override count should be 2, got %d", cooldown.OverridesToday)
	}
	if !cooldown.Active() {
		t.Error("Restored ledger should still be cooling down")
	}
	if want := ShortUnblockLimit - 1; cooldown.ShortUnblocksLeft != want {
		t.Errorf("A restart must not hand back spent hatches, want %d left got %d",
			want, cooldown.ShortUnblocksLeft)
	}
}

func TestRecordOverrideBillsTheHTTPRoad(t *testing.T) {
	state := &State{}

	state.RecordOverride(OverrideSeconds * time.Second)

	cooldown := state.OverrideCooldown()
	if cooldown.OverridesToday != 1 {
		t.Errorf("HTTP override should count toward the day, got %d", cooldown.OverridesToday)
	}
	// And it now prices the next WebSocket override.
	if _, taken := state.TakeOverride(); taken {
		t.Error("An HTTP override should put the WebSocket road on cooldown too")
	}
}
