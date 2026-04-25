package coach

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAgentLockDefaultEngaged(t *testing.T) {
	state := &State{}

	if !state.IsAgentLocked() {
		t.Error("Fresh state should be agent-locked by default")
	}

	info := state.GetAgentLockInfo()
	if info.TimeLeftSeconds != nil {
		t.Errorf("Locked state should have nil time_left_seconds, got %v", *info.TimeLeftSeconds)
	}
}

func TestReleaseAgentLockUnlocksAndAutoLocks(t *testing.T) {
	state := &State{}

	state.ReleaseAgentLock(100 * time.Millisecond)
	if state.IsAgentLocked() {
		t.Error("Expected agent lock to be released")
	}
	if info := state.GetAgentLockInfo(); info.TimeLeftSeconds == nil {
		t.Error("Released state should expose a time_left_seconds value")
	}

	time.Sleep(200 * time.Millisecond)
	if !state.IsAgentLocked() {
		t.Error("Expected agent lock to engage again after release expired")
	}
}

func TestReleaseAgentLockExtendsButNeverShortens(t *testing.T) {
	state := &State{}

	state.ReleaseAgentLock(500 * time.Millisecond)
	state.mu.Lock()
	first := *state.agentReleaseUntil
	state.mu.Unlock()

	// Shorter follow-up: release_until must NOT move backwards.
	state.ReleaseAgentLock(50 * time.Millisecond)
	state.mu.Lock()
	afterShorter := *state.agentReleaseUntil
	state.mu.Unlock()
	if !afterShorter.Equal(first) {
		t.Errorf("Shorter release should not change release_until: was %v, now %v", first, afterShorter)
	}

	// Longer follow-up: release_until extends.
	state.ReleaseAgentLock(2 * time.Second)
	state.mu.Lock()
	afterLonger := *state.agentReleaseUntil
	state.mu.Unlock()
	if !afterLonger.After(first) {
		t.Errorf("Longer release should extend release_until: was %v, now %v", first, afterLonger)
	}
}

func TestEngageAgentLockCancelsRelease(t *testing.T) {
	state := &State{}

	state.ReleaseAgentLock(5 * time.Second)
	if state.IsAgentLocked() {
		t.Fatal("Sanity check: should be released here")
	}

	state.EngageAgentLock()
	if !state.IsAgentLocked() {
		t.Error("EngageAgentLock should re-engage the lock immediately")
	}

	state.mu.Lock()
	timer := state.agentLockTimer
	state.mu.Unlock()
	if timer != nil {
		t.Error("Engage should clear the snap-back timer")
	}
}

func TestIsBlockedCombinesBothLocks(t *testing.T) {
	state := &State{}

	if !state.IsBlocked() {
		t.Error("Fresh state should be blocked (default-engaged agent lock)")
	}

	state.ReleaseAgentLock(5 * time.Second)
	if state.IsBlocked() {
		t.Error("With agent lock released and no manual focus, should not be blocked")
	}

	state.SetFocusing(5 * time.Second)
	if !state.IsBlocked() {
		t.Error("Manual focus alone should block, regardless of agent lock state")
	}

	state.EngageAgentLock()
	if !state.IsBlocked() {
		t.Error("Both locks engaged should still be blocked")
	}
}

func TestRestoreAgentLockFromFutureTime(t *testing.T) {
	state := &State{}

	until := time.Now().Add(2 * time.Second)
	state.RestoreAgentLock(&until)

	if state.IsAgentLocked() {
		t.Error("Restored future release should leave the lock unlocked")
	}

	state.mu.Lock()
	hasTimer := state.agentLockTimer != nil
	state.mu.Unlock()
	if !hasTimer {
		t.Error("Restoration should schedule the snap-back timer")
	}
}

func TestRestoreAgentLockFromPastTimeIsNoOp(t *testing.T) {
	state := &State{}

	past := time.Now().Add(-1 * time.Second)
	state.RestoreAgentLock(&past)

	if !state.IsAgentLocked() {
		t.Error("Restoring a past release should leave the lock engaged")
	}
}

func TestAgentLockReleaseEndpoint(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodPost, "/agent-lock/release", strings.NewReader("duration=300"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr := httptest.NewRecorder()
	server.AgentLockHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
	if server.State.IsAgentLocked() {
		t.Error("State should be released after /release")
	}
}

func TestAgentLockReleaseRejectsBadDuration(t *testing.T) {
	server := &Server{State: &State{}}

	cases := []string{"", "0", "-5", "abc"}
	for _, body := range cases {
		req := httptest.NewRequest(http.MethodPost, "/agent-lock/release", strings.NewReader("duration="+body))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		rr := httptest.NewRecorder()
		server.AgentLockHandler(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Errorf("duration=%q should be 400, got %d", body, rr.Code)
		}
	}
}

func TestAgentLockEngageEndpoint(t *testing.T) {
	server := &Server{State: &State{}}
	server.State.ReleaseAgentLock(5 * time.Second)

	req := httptest.NewRequest(http.MethodPost, "/agent-lock/engage", nil)
	rr := httptest.NewRecorder()
	server.AgentLockHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
	if !server.State.IsAgentLocked() {
		t.Error("State should be locked after /engage")
	}
}

func TestAgentLockGetEndpoint(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodGet, "/agent-lock", nil)
	rr := httptest.NewRecorder()
	server.AgentLockHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), `"time_left_seconds":null`) {
		t.Errorf("Locked GET should expose null time_left_seconds, got %s", rr.Body.String())
	}
}
