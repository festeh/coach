package coach

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"coach/internal/db"

	"github.com/gorilla/websocket"
)

// The release path and /agent-lock/state journal asynchronously through
// DBManager, which is nil here. These tests check the synchronous contract:
// validation, the lock itself, and that a nil DB never panics the handler.

func TestReleaseOpensTheLock(t *testing.T) {
	for _, source := range []string{releaseSourceChat, releaseSourceFallback} {
		server := &Server{State: &State{}}

		if err := server.release(600, "one video, then back to the PR", "Enjoy it.", source); err != nil {
			t.Fatalf("source %q: unexpected error: %v", source, err)
		}
		if agentLocked(server.State) {
			t.Errorf("source %q: state should be released after an OK", source)
		}
	}
}

func TestReleaseRejectsMalformedRequests(t *testing.T) {
	cases := []struct {
		name     string
		duration int
		reason   string
		source   string
	}{
		{"zero duration", 0, "bored", releaseSourceChat},
		{"negative duration", -5, "bored", releaseSourceChat},
		{"longer than the cap", MaxReleaseSeconds + 1, "bored", releaseSourceChat},
		{"no reason", 600, "", releaseSourceChat},
		{"blank reason", 600, "   ", releaseSourceFallback},
		{"no source", 600, "bored", ""},
		{"unknown source", 600, "bored", "agent"},
	}
	for _, c := range cases {
		server := &Server{State: &State{}}
		if err := server.release(c.duration, c.reason, "", c.source); err == nil {
			t.Errorf("%s: expected an error", c.name)
		}
		if !agentLocked(server.State) {
			t.Errorf("%s: a refused release must leave the lock engaged", c.name)
		}
	}
}

func TestReleaseFrameOpensTheLockAndBroadcasts(t *testing.T) {
	server := &Server{State: &State{}}
	ts := httptest.NewServer(http.HandlerFunc(server.WebsocketHandler))
	defer ts.Close()

	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http"), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))

	var initial FocusInfo
	if err := conn.ReadJSON(&initial); err != nil {
		t.Fatalf("initial frame: %v", err)
	}
	if initial.AgentReleaseTimeLeft != nil {
		t.Fatal("Sanity check: the lock should start engaged")
	}

	frame := map[string]any{
		"type":          "release",
		"duration":      600,
		"message":       "one video",
		"agent_message": "Enjoy it.",
		"source":        "chat",
	}
	if err := conn.WriteJSON(frame); err != nil {
		t.Fatalf("write: %v", err)
	}

	var update FocusInfo
	if err := conn.ReadJSON(&update); err != nil {
		t.Fatalf("broadcast frame: %v", err)
	}
	if update.AgentReleaseTimeLeft == nil || *update.AgentReleaseTimeLeft <= 0 {
		t.Errorf("Broadcast should carry the open release, got %+v", update)
	}
}

func TestFocusFrameCarriesNoOverrideFields(t *testing.T) {
	server := &Server{State: &State{}}
	body, err := json.Marshal(server.State.GetCurrentFocusInfo())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), "override") {
		t.Errorf("focus frame should not mention overrides: %s", body)
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
	for _, want := range []string{
		`"released_seconds_today":0`,
		`"temptation_count_today":0`,
		`"recent":[]`,
	} {
		if !strings.Contains(rr.Body.String(), want) {
			t.Errorf("expected %s in %s", want, rr.Body.String())
		}
	}
	if strings.Contains(rr.Body.String(), "override") {
		t.Errorf("lock state should not mention overrides: %s", rr.Body.String())
	}
}

func TestLogTemptationNoPanicWithoutDB(t *testing.T) {
	server := &Server{State: &State{}}
	// nil DBManager must be a no-op, not a panic.
	server.logTemptation("chromium", "reddit.com")
}

func TestUnlockStatsRejectsNonGet(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodPost, "/lock-decisions/stats", nil)
	rr := httptest.NewRecorder()
	server.LockDecisionStatsHandler(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("Expected 405, got %d: %s", rr.Code, rr.Body.String())
	}
}

func TestUnlockStatsWithoutJournalDoesNotPanic(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodGet, "/lock-decisions/stats?days=notanumber", nil)
	rr := httptest.NewRecorder()
	server.LockDecisionStatsHandler(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("Expected 503 without a journal, got %d: %s", rr.Code, rr.Body.String())
	}
}

func decisionsWithIDs(ids ...int64) []db.LockDecision {
	rows := make([]db.LockDecision, 0, len(ids))
	for _, id := range ids {
		rows = append(rows, db.LockDecision{ID: id, Kind: db.DecisionGrant})
	}
	return rows
}

func TestPageLockDecisionsPointsAtTheOlderPage(t *testing.T) {
	// limit+1 rows read: the extra one proves an older page exists.
	page := pageLockDecisions(decisionsWithIDs(9, 8, 7), 2)

	if len(page.Decisions) != 2 || page.Decisions[1].ID != 8 {
		t.Fatalf("expected ids 9, 8 on the page, got %+v", page.Decisions)
	}
	if page.NextBefore == nil || *page.NextBefore != 8 {
		t.Errorf("next page should start before id 8, got %v", page.NextBefore)
	}
}

func TestPageLockDecisionsEndsOnTheLastPage(t *testing.T) {
	page := pageLockDecisions(decisionsWithIDs(2, 1), 2)

	if len(page.Decisions) != 2 {
		t.Fatalf("expected both rows, got %+v", page.Decisions)
	}
	if page.NextBefore != nil {
		t.Errorf("the last page has no cursor, got %d", *page.NextBefore)
	}
}

func TestEmptyLockDecisionPageEncodesAnEmptyList(t *testing.T) {
	body, err := json.Marshal(pageLockDecisions(decisionsWithIDs(), 50))
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"decisions":[],"next_before":null}` {
		t.Errorf("unexpected encoding: %s", body)
	}
}

func TestLockDecisionsRejectsABrokenCursor(t *testing.T) {
	server := &Server{State: &State{}}

	for _, before := range []string{"abc", "0", "-3"} {
		req := httptest.NewRequest(http.MethodGet, "/lock-decisions?before="+before, nil)
		rr := httptest.NewRecorder()
		server.LockDecisionsHandler(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Errorf("before=%q should be 400, got %d", before, rr.Code)
		}
	}
}

func TestLockDecisionsRejectsNonGet(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodPost, "/lock-decisions", nil)
	rr := httptest.NewRecorder()
	server.LockDecisionsHandler(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Errorf("Expected 405, got %d", rr.Code)
	}
}

func TestLockDecisionsWithoutJournalDoesNotPanic(t *testing.T) {
	server := &Server{State: &State{}}

	req := httptest.NewRequest(http.MethodGet, "/lock-decisions?limit=999", nil)
	rr := httptest.NewRecorder()
	server.LockDecisionsHandler(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Errorf("Expected 503 without a journal, got %d", rr.Code)
	}
}
