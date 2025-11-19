package coach

import (
	"testing"
	"time"
)

// TestTimeAccumulationImmediate tests that repeated immediate invocations add time
func TestTimeAccumulationImmediate(t *testing.T) {
	state := &State{}

	// First invocation: 30 seconds
	state.SetFocusing(30 * time.Second)
	timeLeft1 := state.GetTimeLeft()

	// Should have approximately 30 seconds remaining
	if timeLeft1 < 29*time.Second || timeLeft1 > 31*time.Second {
		t.Errorf("After first invocation, expected ~30s, got %v", timeLeft1)
	}

	// Second invocation: 30 seconds (immediate)
	state.SetFocusing(30 * time.Second)
	timeLeft2 := state.GetTimeLeft()

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

	// Wait 5 seconds
	time.Sleep(5 * time.Second)

	// Should have approximately 25 seconds remaining
	timeLeft1 := state.GetTimeLeft()
	if timeLeft1 < 24*time.Second || timeLeft1 > 26*time.Second {
		t.Errorf("After 5 second delay, expected ~25s, got %v", timeLeft1)
	}

	// Second invocation: 30 seconds
	state.SetFocusing(30 * time.Second)
	timeLeft2 := state.GetTimeLeft()

	// Should have approximately 55 seconds remaining (25 + 30)
	if timeLeft2 < 54*time.Second || timeLeft2 > 56*time.Second {
		t.Errorf("After second invocation with delay, expected ~55s, got %v", timeLeft2)
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

	timeLeft := state.GetTimeLeft()

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

	timeLeft := state.GetTimeLeft()

	// Should return 0 when no active focus requests
	if timeLeft != 0 {
		t.Errorf("Expected 0 time left with no requests, got %v", timeLeft)
	}
}
