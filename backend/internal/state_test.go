package coach

import (
	"testing"
	"time"
)

// Test-only probes into State. Production code reads state through
// GetCurrentFocusInfo / GetAgentLockInfo; these wrap the same values so the
// behavioral tests below can assert without their own public accessors.

func remaining(s *State) time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.getTimeLeftLocked()
}

func isFocusing(s *State) bool { return s.GetCurrentFocusInfo().Focusing }

func agentLocked(s *State) bool { return s.GetAgentLockInfo().TimeLeftSeconds == nil }

func isBlocked(s *State) bool {
	fi := s.GetCurrentFocusInfo()
	return fi.Focusing || fi.AgentReleaseTimeLeft == nil
}

// TestTimeAccumulationImmediate tests that repeated immediate invocations add time
func TestTimeAccumulationImmediate(t *testing.T) {
	state := &State{}

	// First invocation: 30 seconds
	state.SetFocusing(30 * time.Second)
	timeLeft1 := remaining(state)

	// Should have approximately 30 seconds remaining
	if timeLeft1 < 29*time.Second || timeLeft1 > 31*time.Second {
		t.Errorf("After first invocation, expected ~30s, got %v", timeLeft1)
	}

	// Second invocation: 30 seconds (immediate)
	state.SetFocusing(30 * time.Second)
	timeLeft2 := remaining(state)

	// Should have approximately 60 seconds remaining (30 + 30)
	if timeLeft2 < 59*time.Second || timeLeft2 > 61*time.Second {
		t.Errorf("After second invocation, expected ~60s, got %v", timeLeft2)
	}

	// Verify we have 2 focus requests
	state.mu.Lock()
	numRequests := len(state.focusRequests)
	state.mu.Unlock()

	if numRequests != 2 {
		t.Errorf("Expected 2 focus requests, got %d", numRequests)
	}
}

// TestTimeAccumulationWithDelay tests that invocations after delay add remaining time
func TestTimeAccumulationWithDelay(t *testing.T) {
	state := &State{}

	// First invocation: 30 seconds
	state.SetFocusing(30 * time.Second)

	// Wait 1 second
	time.Sleep(1 * time.Second)

	// Should have approximately 29 seconds remaining
	timeLeft1 := remaining(state)
	if timeLeft1 < 28*time.Second || timeLeft1 > 30*time.Second {
		t.Errorf("After 1 second delay, expected ~29s, got %v", timeLeft1)
	}

	// Second invocation: 30 seconds
	state.SetFocusing(30 * time.Second)
	timeLeft2 := remaining(state)

	// Should have approximately 59 seconds remaining (29 + 30)
	if timeLeft2 < 58*time.Second || timeLeft2 > 60*time.Second {
		t.Errorf("After second invocation with delay, expected ~59s, got %v", timeLeft2)
	}
}

// TestMultipleInvocations tests three sequential invocations
func TestMultipleInvocations(t *testing.T) {
	// Create state without stats for this test (stats testing is separate)
	state := &State{}

	// Three invocations: 10s, 20s, 30s
	state.SetFocusing(10 * time.Second)
	state.SetFocusing(20 * time.Second)
	state.SetFocusing(30 * time.Second)

	timeLeft := remaining(state)

	// Should have approximately 60 seconds remaining (10 + 20 + 30)
	if timeLeft < 59*time.Second || timeLeft > 61*time.Second {
		t.Errorf("After three invocations, expected ~60s, got %v", timeLeft)
	}

	// Verify we have 3 focus requests
	state.mu.Lock()
	numRequests := len(state.focusRequests)
	state.mu.Unlock()

	if numRequests != 3 {
		t.Errorf("Expected 3 focus requests, got %d", numRequests)
	}
}

// TestFirstInvocationUsesCurrentTime tests that first invocation starts from now
func TestFirstInvocationUsesCurrentTime(t *testing.T) {
	state := &State{}

	now := time.Now()
	state.SetFocusing(30 * time.Second)

	state.mu.Lock()
	firstRequest := state.focusRequests[0]
	state.mu.Unlock()

	// First request should start near current time
	if firstRequest.StartTime.Before(now.Add(-1*time.Second)) ||
	   firstRequest.StartTime.After(now.Add(1*time.Second)) {
		t.Errorf("First request StartTime should be near current time, got %v", firstRequest.StartTime)
	}

	// EndTime should be StartTime + duration
	expectedEndTime := firstRequest.StartTime.Add(30 * time.Second)
	if !firstRequest.EndTime.Equal(expectedEndTime) {
		t.Errorf("First request EndTime incorrect. Expected %v, got %v", expectedEndTime, firstRequest.EndTime)
	}
}

// TestTimeLeftWithNoFocusRequests tests GetTimeLeft with no active requests
func TestTimeLeftWithNoFocusRequests(t *testing.T) {
	state := &State{}

	timeLeft := remaining(state)

	// Should return 0 when no active focus requests
	if timeLeft != 0 {
		t.Errorf("Expected 0 time left with no requests, got %v", timeLeft)
	}
}

// TestIsFocusingDerived tests that IsFocusing is correctly derived from focus requests
func TestIsFocusingDerived(t *testing.T) {
	state := &State{}

	// Initially not focusing (no requests)
	if isFocusing(state) {
		t.Error("Expected IsFocusing() to be false with no requests")
	}

	// Add a short focus request
	state.SetFocusing(1 * time.Second)

	// Should now be focusing
	if !isFocusing(state) {
		t.Error("Expected IsFocusing() to be true with active request")
	}

	// Wait for request to expire
	time.Sleep(1200 * time.Millisecond)

	// Should automatically not be focusing (request expired)
	if isFocusing(state) {
		t.Error("Expected IsFocusing() to be false after request expired")
	}

	// Verify time left is also 0
	if remaining(state) != 0 {
		t.Errorf("Expected 0 time left after expiration, got %v", remaining(state))
	}
}
