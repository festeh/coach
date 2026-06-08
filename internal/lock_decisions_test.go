package coach

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The /release and /lock-decisions handlers journal asynchronously through
// DBManager, which is nil here. These tests check the synchronous contract:
// status codes, validation, and that a nil DB never panics the handler.

func TestReleaseStillWorksWithoutJournalFields(t *testing.T) {
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

func TestReleaseAcceptsJournalFields(t *testing.T) {
	server := &Server{State: &State{}}

	body := "duration=600&is_override=true&user_message=just+5+min&agent_message=ok+go"
	req := httptest.NewRequest(http.MethodPost, "/agent-lock/release", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rr := httptest.NewRecorder()
	server.AgentLockHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
}

func TestLockStateEmptyWithoutDB(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodGet, "/agent-lock/state", nil)
	rr := httptest.NewRecorder()
	server.AgentLockHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
	for _, want := range []string{`"released_seconds_today":0`, `"override_count_today":0`, `"recent":[]`} {
		if !strings.Contains(rr.Body.String(), want) {
			t.Errorf("expected %s in %s", want, rr.Body.String())
		}
	}
}

func TestLockDecisionsRecordsDenial(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodPost, "/lock-decisions",
		strings.NewReader(`{"user_message":"want reddit","agent_message":"no, finish the PR"}`))
	rr := httptest.NewRecorder()
	server.LockDecisionsHandler(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("Expected 200, got %d: %s", rr.Code, rr.Body.String())
	}
}

func TestLockDecisionsRejectsExplicitKind(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodPost, "/lock-decisions",
		strings.NewReader(`{"kind":"grant","user_message":"x","agent_message":"y"}`))
	rr := httptest.NewRecorder()
	server.LockDecisionsHandler(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("Expected 400 when kind is set, got %d", rr.Code)
	}
}

func TestLockDecisionsRejectsGet(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodGet, "/lock-decisions", nil)
	rr := httptest.NewRecorder()
	server.LockDecisionsHandler(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Errorf("Expected 405 for GET, got %d", rr.Code)
	}
}
