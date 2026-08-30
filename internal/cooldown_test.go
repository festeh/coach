package coach

import (
	"testing"
	"time"

	"coach/internal/db"
)

// ledgerAt builds a ledger as it would stand on now's day.
func ledgerAt(now time.Time, redeemed int, armedAt time.Time, reason string) overrideLedger {
	return overrideLedger{
		day:         db.LocalDayStart(now),
		redeemed:    redeemed,
		armedAt:     armedAt,
		armedReason: reason,
	}
}

func TestFirstOverrideOfTheDayIsFree(t *testing.T) {
	state := &State{}

	status, outcome, reason := state.TakeOverride("one thing to check")

	if outcome != OverrideGranted {
		t.Fatalf("First override of the day should be granted outright, got outcome %v", outcome)
	}
	if agentLocked(state) {
		t.Error("A granted override should release the lock")
	}
	if reason != "one thing to check" {
		t.Errorf("A free grant should journal the written reason, got %q", reason)
	}
	if status.OverridesToday != 1 {
		t.Errorf("The free grant should count as redeemed, got %d today", status.OverridesToday)
	}
}

func TestOverrideRequiresAReasonToGrantOrArm(t *testing.T) {
	state := &State{}

	if _, outcome, _ := state.TakeOverride(""); outcome != OverrideNeedsReason {
		t.Errorf("A bare click while idle should ask for a reason, got outcome %v", outcome)
	}
	if !agentLocked(state) {
		t.Error("A refused click must not release anything")
	}
	if status := state.OverrideStatus(); status.OverridesToday != 0 {
		t.Errorf("A refused click must not count, got %d today", status.OverridesToday)
	}
}

func TestSecondClickArmsACountdownInsteadOfGranting(t *testing.T) {
	state := &State{}

	if _, outcome, _ := state.TakeOverride("first"); outcome != OverrideGranted {
		t.Fatal("First override should be free")
	}

	status, outcome, _ := state.TakeOverride("second")

	if outcome != OverrideArmed {
		t.Fatalf("Second click of the day should arm, got outcome %v", outcome)
	}
	if status.Phase != OverridePhaseCooling {
		t.Errorf("An armed request should read as cooling, got %q", status.Phase)
	}
	if status.CooldownSeconds != CooldownStepSeconds {
		t.Errorf("The second override should cost %ds, got %ds", CooldownStepSeconds, status.CooldownSeconds)
	}
	if status.CooldownRemaining <= 0 || status.CooldownRemaining > CooldownStepSeconds {
		t.Errorf("A fresh countdown should have its whole length ahead, got %ds", status.CooldownRemaining)
	}
}

func TestClickDuringTheCountdownChangesNothing(t *testing.T) {
	state := &State{}
	state.TakeOverride("first")
	state.TakeOverride("second")

	status, outcome, _ := state.TakeOverride("impatient")

	if outcome != OverrideCooling {
		t.Fatalf("A click mid-countdown should be a no-op, got outcome %v", outcome)
	}
	if status.Phase != OverridePhaseCooling {
		t.Errorf("The countdown should still be running, got %q", status.Phase)
	}
	if status.OverridesToday != 1 {
		t.Errorf("An impatient click must not count, got %d today", status.OverridesToday)
	}
}

func TestCountdownGrowsByThirtySecondsPerRedemption(t *testing.T) {
	now := time.Now()

	for redeemed, want := range map[int]int{1: 30, 2: 60, 3: 90, 10: 300} {
		status := overrideStatus(now, ledgerAt(now, redeemed, time.Time{}, ""))
		if status.CooldownSeconds != want {
			t.Errorf("After %d redemptions, arming should cost %ds, got %ds", redeemed, want, status.CooldownSeconds)
		}
		if status.NextCooldownSeconds != want+CooldownStepSeconds {
			t.Errorf("After %d redemptions, one more should cost %ds, got %ds",
				redeemed, want+CooldownStepSeconds, status.NextCooldownSeconds)
		}
	}
}

func TestRipeRequestRedeemsWithoutRetypingTheReason(t *testing.T) {
	state := &State{}
	armedAt := time.Now().Add(-time.Duration(CooldownStepSeconds+1) * time.Second)
	state.RestoreOverrideLedger(1, &armedAt, "finish the thread")

	if status := state.OverrideStatus(); status.Phase != OverridePhaseReady {
		t.Fatalf("A lapsed countdown should read as ready, got %q", status.Phase)
	}

	status, outcome, reason := state.TakeOverride("")

	if outcome != OverrideGranted {
		t.Fatalf("A click on a ripe request should redeem, got outcome %v", outcome)
	}
	if agentLocked(state) {
		t.Error("A redemption should release the lock")
	}
	if reason != "finish the thread" {
		t.Errorf("The redemption should journal the reason written at arming, got %q", reason)
	}
	if status.OverridesToday != 2 {
		t.Errorf("The redemption should count, got %d today", status.OverridesToday)
	}
	if status.Phase != OverridePhaseIdle {
		t.Errorf("A redeemed request should leave no live request behind, got %q", status.Phase)
	}
}

func TestRipeWindowClosesAndTheRequestEvaporates(t *testing.T) {
	now := time.Now()
	expired := now.Add(-time.Duration(CooldownStepSeconds+RedeemWindowSeconds+1) * time.Second)

	ledger := ledgerAt(now, 1, expired, "stale").at(now)
	status := overrideStatus(now, ledger)

	if status.Phase != OverridePhaseIdle {
		t.Fatalf("An unclaimed request should evaporate, got %q", status.Phase)
	}
	// No penalty: the next click re-arms at the same price it was.
	if status.OverridesToday != 1 {
		t.Errorf("Abandoning a request must not count as a redemption, got %d", status.OverridesToday)
	}
	if status.CooldownSeconds != CooldownStepSeconds {
		t.Errorf("Abandoning a request must not change the price, want %ds got %ds",
			CooldownStepSeconds, status.CooldownSeconds)
	}
}

func TestRedeemWindowCountsDownFromWhereTheCooldownEnded(t *testing.T) {
	now := time.Now()
	// Armed 40s ago at a 30s price: ripe for 10s, so ~110s of the window left.
	armedAt := now.Add(-40 * time.Second)

	status := overrideStatus(now, ledgerAt(now, 1, armedAt, "r"))

	if status.Phase != OverridePhaseReady {
		t.Fatalf("Expected ready, got %q", status.Phase)
	}
	if status.RedeemRemaining < RedeemWindowSeconds-11 || status.RedeemRemaining > RedeemWindowSeconds-10 {
		t.Errorf("Redeem window should have ~%ds left, got %ds", RedeemWindowSeconds-10, status.RedeemRemaining)
	}
}

func TestCountdownRoundsUpSoCoolingNeverReadsZero(t *testing.T) {
	now := time.Now()
	// 500ms of countdown left: truncation would read 0 while a click still arms nothing.
	armedAt := now.Add(-time.Duration(CooldownStepSeconds)*time.Second + 500*time.Millisecond)

	status := overrideStatus(now, ledgerAt(now, 1, armedAt, "r"))

	if status.Phase != OverridePhaseCooling {
		t.Fatalf("Half a second to run should still be cooling, got %q", status.Phase)
	}
	if status.CooldownRemaining != 1 {
		t.Errorf("A countdown with 500ms to run should read 1s, got %ds", status.CooldownRemaining)
	}
}

func TestEscalationAndLiveRequestsResetOnANewDay(t *testing.T) {
	now := time.Now()
	yesterday := now.AddDate(0, 0, -1)
	stale := overrideLedger{
		day:         db.LocalDayStart(yesterday),
		redeemed:    5,
		armedAt:     yesterday,
		armedReason: "yesterday's craving",
	}

	status := overrideStatus(now, stale.at(now))

	if status.OverridesToday != 0 {
		t.Errorf("Yesterday's redemptions should not count today, got %d", status.OverridesToday)
	}
	if status.Phase != OverridePhaseIdle {
		t.Errorf("Yesterday's request should not survive midnight, got %q", status.Phase)
	}
	if status.CooldownSeconds != 0 {
		t.Errorf("A new day's first override should be free, got %ds", status.CooldownSeconds)
	}
}

func TestShortUnblockAlwaysGrantsAndNeverEscalates(t *testing.T) {
	state := &State{}

	for i := range 3 {
		state.TakeShortUnblock()
		if agentLocked(state) {
			t.Fatalf("Peek %d should have released the lock", i+1)
		}
	}

	status := state.OverrideStatus()
	if status.OverridesToday != 0 {
		t.Errorf("Peeks must not count as overrides, got %d today", status.OverridesToday)
	}
	if status.CooldownSeconds != 0 {
		t.Errorf("Peeks must not price the next override, got %ds", status.CooldownSeconds)
	}
}

func TestShortUnblockLeavesTheCountdownRunning(t *testing.T) {
	state := &State{}
	armedAt := time.Now().Add(-5 * time.Second)
	state.RestoreOverrideLedger(1, &armedAt, "waiting it out")

	status := state.TakeShortUnblock()

	if agentLocked(state) {
		t.Error("The peek should have released the lock")
	}
	if status.Phase != OverridePhaseCooling {
		t.Errorf("A peek must not touch the armed request, got %q", status.Phase)
	}
	if status.OverridesToday != 1 {
		t.Errorf("A peek must not count as a redemption, got %d", status.OverridesToday)
	}
}

func TestRecordOverrideBillsTheHTTPRoadAndClearsTheArm(t *testing.T) {
	state := &State{}
	armedAt := time.Now()
	state.RestoreOverrideLedger(1, &armedAt, "pending")

	state.RecordOverride()

	status := state.OverrideStatus()
	if status.OverridesToday != 2 {
		t.Errorf("HTTP override should count toward the day, got %d", status.OverridesToday)
	}
	if status.Phase != OverridePhaseIdle {
		t.Errorf("The agent's grant should retire the pending request, got %q", status.Phase)
	}
	// And it prices the next WebSocket click, which now arms rather than grants.
	if _, outcome, _ := state.TakeOverride("more"); outcome != OverrideArmed {
		t.Errorf("After an HTTP override the next click should arm, got outcome %v", outcome)
	}
}

func TestRestoreOverrideLedgerRevivesARunningCountdown(t *testing.T) {
	state := &State{}
	armedAt := time.Now().Add(-10 * time.Second)

	state.RestoreOverrideLedger(2, &armedAt, "survived the restart")

	status := state.OverrideStatus()
	if status.OverridesToday != 2 {
		t.Errorf("Restored redemption count should be 2, got %d", status.OverridesToday)
	}
	if status.Phase != OverridePhaseCooling {
		t.Fatalf("A restart must not skip the countdown, got %q", status.Phase)
	}
	if want := 2*CooldownStepSeconds - 10; status.CooldownRemaining < want-1 || status.CooldownRemaining > want {
		t.Errorf("Countdown should pick up where it was, want ~%ds got %ds", want, status.CooldownRemaining)
	}
}

func TestGrantedReleaseEndsARunningFocusSession(t *testing.T) {
	state := &State{}
	state.SetFocusing(5 * time.Minute)

	if _, outcome, _ := state.TakeOverride("released means released"); outcome != OverrideGranted {
		t.Fatal("First override of the day should be granted")
	}

	if isBlocked(state) {
		t.Error("A granted override should end the focus session and unblock")
	}
	if state.GetCurrentFocusInfo().Focusing {
		t.Error("The focus session should be over")
	}
}

func TestPeekEndsARunningFocusSessionToo(t *testing.T) {
	state := &State{}
	state.SetFocusing(5 * time.Minute)

	state.TakeShortUnblock()

	if isBlocked(state) {
		t.Error("A granted peek should end the focus session and unblock")
	}
}

func TestArmingDoesNotTouchAFocusSession(t *testing.T) {
	state := &State{}
	state.RestoreOverrideLedger(1, nil, "")
	state.SetFocusing(5 * time.Minute)

	if _, outcome, _ := state.TakeOverride("still cooling"); outcome != OverrideArmed {
		t.Fatal("Expected the click to arm")
	}

	if !state.GetCurrentFocusInfo().Focusing {
		t.Error("Arming grants nothing, so the focus session must survive it")
	}
}
