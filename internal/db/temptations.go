package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// temptationsCollection records each block the user hit while locked: a
// non-whitelisted site in a browser, a watched app on the phone. One row per
// block.
//
//	source — which client reported it (e.g. "chromium", "firefox", "android")
//	target — the site hostname or app package the user reached for
var temptationsCollection = Collection{
	Name: "temptations",
	Type: "base",
	Fields: append([]Field{
		{Name: "source", Type: "text", Required: true},
		{Name: "target", Type: "text", Required: false},
	}, TimestampFields()...),
}

// EnsureTemptationsCollection creates the temptations collection if it doesn't
// exist. Idempotent.
func (m *Manager) EnsureTemptationsCollection() (created bool, err error) {
	return m.EnsureCollection(temptationsCollection)
}

// InsertTemptation writes one temptation row.
func (m *Manager) InsertTemptation(source, target string) error {
	_, err := m.createRecord("temptations", map[string]any{
		"source": source,
		"target": target,
	})
	return err
}

// CountTodayTemptations returns how many temptations were recorded today.
// "Today" is the server's local date, matching GetTodayFocusCount.
func (m *Manager) CountTodayTemptations() (int, error) {
	today := time.Now().Format("2006-01-02")

	u, err := url.Parse(fmt.Sprintf("%s/api/collections/temptations/records", m.BaseURL))
	if err != nil {
		return 0, fmt.Errorf("failed to parse URL: %w", err)
	}
	q := u.Query()
	q.Set("filter", fmt.Sprintf("created >= '%s 00:00:00'", today))
	q.Set("perPage", "1") // we only need totalItems, not the rows
	u.RawQuery = q.Encode()

	req, err := http.NewRequest("GET", u.String(), nil)
	if err != nil {
		return 0, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := m.DoRequest(req)
	if err != nil {
		return 0, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("temptations count failed with status %d: %s", resp.StatusCode, string(body))
	}

	var result struct {
		TotalItems int `json:"totalItems"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return 0, fmt.Errorf("failed to parse response: %w", err)
	}
	return result.TotalItems, nil
}
