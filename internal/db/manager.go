package db

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const queryTimeout = 10 * time.Second

// FocusRecord is one persisted focus session.
type FocusRecord struct {
	Timestamp time.Time `json:"timestamp"`
	Duration  int       `json:"duration"`
}

// Manager owns Coach's PostgreSQL connection pool and queries.
type Manager struct {
	pool *pgxpool.Pool
}

// InitManager connects to the dedicated Coach database and applies idempotent
// schema migrations. DATABASE_URL is supplied by the protected runtime file.
func InitManager() (*Manager, error) {
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		return nil, errors.New("DATABASE_URL is required")
	}

	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	config.MaxConns = 5
	config.MinConns = 1
	config.MaxConnIdleTime = 5 * time.Minute

	ctx, cancel := context.WithTimeout(context.Background(), queryTimeout)
	defer cancel()

	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("create PostgreSQL pool: %w", err)
	}

	manager := &Manager{pool: pool}
	if err := manager.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("connect to PostgreSQL: %w", err)
	}
	if err := manager.migrate(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("migrate PostgreSQL schema: %w", err)
	}
	return manager, nil
}

// Close releases every PostgreSQL connection owned by Coach.
func (m *Manager) Close() {
	if m != nil && m.pool != nil {
		m.pool.Close()
	}
}

// Ping verifies that the database is reachable.
func (m *Manager) Ping(ctx context.Context) error {
	return m.pool.Ping(ctx)
}

func (m *Manager) migrate(ctx context.Context) error {
	tx, err := m.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	statements := []string{
		`CREATE TABLE IF NOT EXISTS focus_sessions (
			id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
			started_at TIMESTAMPTZ NOT NULL UNIQUE,
			duration_seconds INTEGER NOT NULL CHECK (duration_seconds > 0),
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`,
		`CREATE INDEX IF NOT EXISTS focus_sessions_started_at_idx
			ON focus_sessions (started_at DESC)`,
		`CREATE TABLE IF NOT EXISTS agent_lock (
			singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
			release_until TIMESTAMPTZ,
			updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`,
		`INSERT INTO agent_lock (singleton) VALUES (TRUE)
			ON CONFLICT (singleton) DO NOTHING`,
		`CREATE TABLE IF NOT EXISTS attention_intervals (
			id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
			state TEXT NOT NULL CHECK (state IN ('site', 'idle', 'away')),
			site TEXT NOT NULL DEFAULT '',
			started_at TIMESTAMPTZ NOT NULL,
			last_seen TIMESTAMPTZ NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
			CHECK (last_seen >= started_at)
		)`,
		`CREATE INDEX IF NOT EXISTS attention_intervals_window_idx
			ON attention_intervals (last_seen DESC, started_at)`,
		`CREATE TABLE IF NOT EXISTS lock_decisions (
			id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
			kind TEXT NOT NULL CHECK (kind IN ('grant', 'override', 'denial')),
			source TEXT NOT NULL DEFAULT '',
			user_message TEXT NOT NULL DEFAULT '',
			agent_message TEXT NOT NULL DEFAULT '',
			duration_seconds INTEGER NOT NULL DEFAULT 0 CHECK (duration_seconds >= 0),
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`,
		`CREATE INDEX IF NOT EXISTS lock_decisions_created_at_idx
			ON lock_decisions (created_at DESC)`,
		`CREATE TABLE IF NOT EXISTS temptations (
			id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
			source TEXT NOT NULL,
			target TEXT NOT NULL DEFAULT '',
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`,
		`CREATE INDEX IF NOT EXISTS temptations_created_at_idx
			ON temptations (created_at DESC)`,
	}

	for _, statement := range statements {
		if _, err := tx.Exec(ctx, statement); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func operationContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), queryTimeout)
}

func localDayBounds(now time.Time) (time.Time, time.Time) {
	start := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	return start, start.AddDate(0, 0, 1)
}

// AddFocusRecord persists one focus session.
func (m *Manager) AddFocusRecord(startedAt time.Time, durationSeconds int) error {
	if durationSeconds <= 0 {
		return errors.New("focus duration must be positive")
	}
	ctx, cancel := operationContext()
	defer cancel()

	_, err := m.pool.Exec(ctx, `
		INSERT INTO focus_sessions (started_at, duration_seconds)
		VALUES ($1, $2)
		ON CONFLICT (started_at) DO UPDATE
		SET duration_seconds = EXCLUDED.duration_seconds
	`, startedAt, durationSeconds)
	return err
}

// GetTodayFocusCount returns the number of sessions started today in the
// server's configured local timezone.
func (m *Manager) GetTodayFocusCount() (int, error) {
	start, end := localDayBounds(time.Now())
	ctx, cancel := operationContext()
	defer cancel()

	var count int
	err := m.pool.QueryRow(ctx, `
		SELECT count(*) FROM focus_sessions
		WHERE started_at >= $1 AND started_at < $2
	`, start, end).Scan(&count)
	return count, err
}

// GetActiveFocus returns the remaining duration of the newest unexpired focus
// session, or zero when no focus is active.
func (m *Manager) GetActiveFocus() (time.Duration, error) {
	ctx, cancel := operationContext()
	defer cancel()

	var startedAt time.Time
	var durationSeconds int
	err := m.pool.QueryRow(ctx, `
		SELECT started_at, duration_seconds
		FROM focus_sessions
		ORDER BY started_at DESC
		LIMIT 1
	`).Scan(&startedAt, &durationSeconds)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}

	remaining := time.Until(startedAt.Add(time.Duration(durationSeconds) * time.Second))
	if remaining <= 0 {
		return 0, nil
	}
	return remaining, nil
}

// GetFocusHistory returns focus sessions since the local midnight N days ago,
// newest first.
func (m *Manager) GetFocusHistory(days int) ([]FocusRecord, error) {
	if days < 1 {
		days = 7
	}
	start, _ := localDayBounds(time.Now().AddDate(0, 0, -days))
	ctx, cancel := operationContext()
	defer cancel()

	rows, err := m.pool.Query(ctx, `
		SELECT started_at, duration_seconds
		FROM focus_sessions
		WHERE started_at >= $1
		ORDER BY started_at DESC
		LIMIT 500
	`, start)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	records := make([]FocusRecord, 0)
	for rows.Next() {
		var record FocusRecord
		if err := rows.Scan(&record.Timestamp, &record.Duration); err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, rows.Err()
}
