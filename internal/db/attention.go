package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// attentionCollection is the schema for the attention collection. One record per
// contiguous span of attention on the same (state, site).
//
//	state      (text) — "site", "idle" or "away"
//	site       (text) — hostname, set only when state is "site"
//	started_at (text) — RFC3339 timestamp of the first beacon of the span
//	last_seen  (text) — RFC3339 timestamp of the latest beacon, bumped by heartbeats
var attentionCollection = Collection{
	Name: "attention",
	Type: "base",
	Fields: []Field{
		{Name: "state", Type: "text", Required: true},
		{Name: "site", Type: "text", Required: false},
		{Name: "started_at", Type: "text", Required: true},
		{Name: "last_seen", Type: "text", Required: true},
	},
}

// EnsureAttentionCollection creates the attention collection if it doesn't exist.
// Idempotent.
func (m *Manager) EnsureAttentionCollection() (created bool, err error) {
	return m.EnsureCollection(attentionCollection)
}

// CreateAttentionInterval opens a new attention interval and returns its record ID.
func (m *Manager) CreateAttentionInterval(state, site string, at time.Time) (string, error) {
	ts := at.UTC().Format(time.RFC3339)
	return m.createRecord("attention", map[string]any{
		"state":      state,
		"site":       site,
		"started_at": ts,
		"last_seen":  ts,
	})
}

// TouchAttentionInterval bumps last_seen on an open interval.
func (m *Manager) TouchAttentionInterval(recordID string, at time.Time) error {
	return m.updateRecord("attention", recordID, map[string]any{
		"last_seen": at.UTC().Format(time.RFC3339),
	})
}

// AttentionInterval is one contiguous span of attention as stored in PB.
type AttentionInterval struct {
	State     string `json:"state"`
	Site      string `json:"site"`
	StartedAt string `json:"started_at"`
	LastSeen  string `json:"last_seen"`
}

// GetAttentionIntervals returns intervals overlapping [from, to), oldest first.
// Pages through PB since a day of browsing can exceed one page of records.
func (m *Manager) GetAttentionIntervals(from, to time.Time) ([]AttentionInterval, error) {
	// Timestamps are stored as RFC3339 UTC strings, which sort lexicographically,
	// so PB's plain string comparison is a correct time comparison.
	filter := fmt.Sprintf("last_seen >= '%s' && started_at < '%s'",
		from.UTC().Format(time.RFC3339), to.UTC().Format(time.RFC3339))

	intervals := []AttentionInterval{}
	for page := 1; ; page++ {
		u, err := url.Parse(fmt.Sprintf("%s/api/collections/attention/records", m.BaseURL))
		if err != nil {
			return nil, fmt.Errorf("failed to parse URL: %w", err)
		}
		q := u.Query()
		q.Set("filter", filter)
		q.Set("sort", "started_at")
		q.Set("perPage", "500")
		q.Set("page", strconv.Itoa(page))
		u.RawQuery = q.Encode()

		req, err := http.NewRequest("GET", u.String(), nil)
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		resp, err := m.DoRequest(req)
		if err != nil {
			return nil, fmt.Errorf("failed to send request: %w", err)
		}

		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			return nil, fmt.Errorf("failed to read response body: %w", err)
		}

		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("attention fetch failed with status %d: %s", resp.StatusCode, string(body))
		}

		var result struct {
			Items      []AttentionInterval `json:"items"`
			TotalPages int                 `json:"totalPages"`
		}
		if err := json.Unmarshal(body, &result); err != nil {
			return nil, fmt.Errorf("failed to parse response: %w", err)
		}

		intervals = append(intervals, result.Items...)
		if page >= result.TotalPages {
			return intervals, nil
		}
	}
}
