package db

import (
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
