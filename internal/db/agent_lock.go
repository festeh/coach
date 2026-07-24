package db

import (
	"time"

	"github.com/jackc/pgx/v5/pgtype"
)

// GetAgentReleaseUntil reads the singleton release window. Expired windows are
// treated as locked.
func (m *Manager) GetAgentReleaseUntil() (*time.Time, error) {
	ctx, cancel := operationContext()
	defer cancel()

	var releaseUntil pgtype.Timestamptz
	err := m.pool.QueryRow(ctx, `
		SELECT release_until FROM agent_lock WHERE singleton = TRUE
	`).Scan(&releaseUntil)
	if err != nil {
		return nil, err
	}
	if !releaseUntil.Valid || !time.Now().Before(releaseUntil.Time) {
		return nil, nil
	}
	value := releaseUntil.Time
	return &value, nil
}

// SetAgentReleaseUntil updates the singleton release window. A nil value
// immediately re-engages the lock.
func (m *Manager) SetAgentReleaseUntil(value *time.Time) error {
	ctx, cancel := operationContext()
	defer cancel()

	_, err := m.pool.Exec(ctx, `
		UPDATE agent_lock
		SET release_until = $1, updated_at = now()
		WHERE singleton = TRUE
	`, value)
	return err
}
