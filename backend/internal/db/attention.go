package db

import (
	"errors"
	"fmt"
	"strconv"
	"time"
)

// AttentionInterval is one contiguous attention span.
type AttentionInterval struct {
	State     string `json:"state"`
	Site      string `json:"site"`
	StartedAt string `json:"started_at"`
	LastSeen  string `json:"last_seen"`
}

// CreateAttentionInterval opens a new attention interval and returns its ID.
func (m *Manager) CreateAttentionInterval(state, site string, at time.Time) (string, error) {
	switch state {
	case "site", "idle", "away":
	default:
		return "", fmt.Errorf("invalid attention state %q", state)
	}

	ctx, cancel := operationContext()
	defer cancel()

	var id int64
	err := m.pool.QueryRow(ctx, `
		INSERT INTO attention_intervals (state, site, started_at, last_seen)
		VALUES ($1, $2, $3, $3)
		RETURNING id
	`, state, site, at).Scan(&id)
	if err != nil {
		return "", err
	}
	return strconv.FormatInt(id, 10), nil
}

// TouchAttentionInterval advances last_seen on an open interval.
func (m *Manager) TouchAttentionInterval(recordID string, at time.Time) error {
	id, err := strconv.ParseInt(recordID, 10, 64)
	if err != nil || id <= 0 {
		return errors.New("invalid attention interval ID")
	}

	ctx, cancel := operationContext()
	defer cancel()

	result, err := m.pool.Exec(ctx, `
		UPDATE attention_intervals
		SET last_seen = $1
		WHERE id = $2 AND last_seen <= $1
	`, at, id)
	if err != nil {
		return err
	}
	if result.RowsAffected() != 1 {
		return errors.New("attention interval was not found or moved backwards")
	}
	return nil
}

// GetAttentionIntervals returns intervals overlapping [from, to), oldest first.
func (m *Manager) GetAttentionIntervals(from, to time.Time) ([]AttentionInterval, error) {
	if !from.Before(to) {
		return nil, errors.New("attention interval start must precede end")
	}

	ctx, cancel := operationContext()
	defer cancel()

	rows, err := m.pool.Query(ctx, `
		SELECT state, site, started_at, last_seen
		FROM attention_intervals
		WHERE started_at < $2 AND last_seen >= $1
		ORDER BY started_at
		LIMIT 5000
	`, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	intervals := make([]AttentionInterval, 0)
	for rows.Next() {
		var interval AttentionInterval
		var startedAt, lastSeen time.Time
		if err := rows.Scan(&interval.State, &interval.Site, &startedAt, &lastSeen); err != nil {
			return nil, err
		}
		interval.StartedAt = startedAt.UTC().Format(time.RFC3339)
		interval.LastSeen = lastSeen.UTC().Format(time.RFC3339)
		intervals = append(intervals, interval)
	}
	return intervals, rows.Err()
}
