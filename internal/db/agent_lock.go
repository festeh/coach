package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// AgentLockRecord is the singleton record in the agent_lock collection.
type AgentLockRecord struct {
	RecordID     string `json:"id"`
	ReleaseUntil string `json:"release_until"`
}

// agentLockCollection is the schema for the agent_lock collection.
//
//	release_until (text) — RFC3339 timestamp, or empty when the lock is engaged
var agentLockCollection = Collection{
	Name: "agent_lock",
	Type: "base",
	Fields: []Field{
		{Name: "release_until", Type: "text", Required: false},
	},
}

// EnsureAgentLockCollection creates the agent_lock collection if it doesn't exist.
// Idempotent.
func (m *Manager) EnsureAgentLockCollection() (created bool, err error) {
	return m.EnsureCollection(agentLockCollection)
}

// GetAgentReleaseUntil reads the singleton agent_lock record. Returns nil if no record
// exists, the field is empty, or the parsed time is in the past.
func (m *Manager) GetAgentReleaseUntil() (*time.Time, error) {
	rec, err := m.fetchAgentLockRecord()
	if err != nil {
		return nil, err
	}
	if rec == nil || rec.ReleaseUntil == "" {
		return nil, nil
	}
	t, err := time.Parse(time.RFC3339, rec.ReleaseUntil)
	if err != nil {
		return nil, fmt.Errorf("failed to parse release_until %q: %w", rec.ReleaseUntil, err)
	}
	if !time.Now().Before(t) {
		return nil, nil
	}
	return &t, nil
}

// SetAgentReleaseUntil upserts the singleton record. Pass nil to engage the lock.
func (m *Manager) SetAgentReleaseUntil(t *time.Time) error {
	var s string
	if t != nil {
		s = t.UTC().Format(time.RFC3339)
	}
	payload := map[string]any{"release_until": s}

	rec, err := m.fetchAgentLockRecord()
	if err != nil {
		return err
	}
	if rec == nil {
		_, err := m.createRecord("agent_lock", payload)
		return err
	}
	return m.updateRecord("agent_lock", rec.RecordID, payload)
}

// fetchAgentLockRecord returns the most-recent record, or nil if none exist.
func (m *Manager) fetchAgentLockRecord() (*AgentLockRecord, error) {
	endpoint := fmt.Sprintf("%s/api/collections/agent_lock/records?sort=-created&perPage=1", m.BaseURL)
	req, err := http.NewRequest("GET", endpoint, nil)
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
		return nil, fmt.Errorf("agent_lock fetch failed with status %d: %s", resp.StatusCode, string(body))
	}

	var result struct {
		Items []AgentLockRecord `json:"items"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	if len(result.Items) == 0 {
		return nil, nil
	}
	return &result.Items[0], nil
}
