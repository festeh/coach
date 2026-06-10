package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// lockDecisionsCollection records every agent-lock decision: a plea and the
// coach's answer. One row per decision.
//
//	kind             — "grant", "override", or "denial"
//	source           — who reported it (currently always "agent")
//	user_message     — what the user said, verbatim
//	agent_message    — what the coach replied
//	duration_seconds — release length for grant/override; 0 for denial
var lockDecisionsCollection = Collection{
	Name: "lock_decisions",
	Type: "base",
	Fields: append([]Field{
		{Name: "kind", Type: "text", Required: true},
		{Name: "source", Type: "text", Required: false},
		{Name: "user_message", Type: "text", Required: false},
		{Name: "agent_message", Type: "text", Required: false},
		{Name: "duration_seconds", Type: "number", Required: false},
	}, TimestampFields()...),
}

// EnsureLockDecisionsCollection creates the lock_decisions collection if it
// doesn't exist. Idempotent.
func (m *Manager) EnsureLockDecisionsCollection() (created bool, err error) {
	return m.EnsureCollection(lockDecisionsCollection)
}

// InsertLockDecision writes one decision row.
func (m *Manager) InsertLockDecision(kind, source, userMessage, agentMessage string, durationSeconds int) error {
	_, err := m.createRecord("lock_decisions", map[string]any{
		"kind":             kind,
		"source":           source,
		"user_message":     userMessage,
		"agent_message":    agentMessage,
		"duration_seconds": durationSeconds,
	})
	return err
}

// LockDecision is one decision row as stored in PB.
type LockDecision struct {
	Kind            string `json:"kind"`
	UserMessage     string `json:"user_message"`
	AgentMessage    string `json:"agent_message"`
	DurationSeconds int    `json:"duration_seconds"`
	Created         string `json:"created"`
}

// GetTodayLockDecisions returns today's decisions, oldest first. "Today" is the
// server's local date, matching GetTodayFocusCount.
func (m *Manager) GetTodayLockDecisions() ([]LockDecision, error) {
	today := time.Now().Format("2006-01-02")

	u, err := url.Parse(fmt.Sprintf("%s/api/collections/lock_decisions/records", m.BaseURL))
	if err != nil {
		return nil, fmt.Errorf("failed to parse URL: %w", err)
	}
	q := u.Query()
	q.Set("filter", fmt.Sprintf("created >= '%s 00:00:00'", today))
	q.Set("sort", "created")
	q.Set("perPage", "500")
	u.RawQuery = q.Encode()

	req, err := http.NewRequest("GET", u.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := m.DoRequest(req)
	if err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("lock_decisions fetch failed with status %d: %s", resp.StatusCode, string(body))
	}

	var result struct {
		Items []LockDecision `json:"items"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}
	return result.Items, nil
}
