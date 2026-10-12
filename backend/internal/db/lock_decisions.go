package db

import (
	"slices"
	"time"

	"github.com/jackc/pgx/v5"
)

// DecisionGrant is the one kind written now: a release the user took with OK
// after giving a reason. Older rows also hold 'override', 'short_unblock' and
// 'denial' from the days of the judge and its escape hatches.
const DecisionGrant = "grant"

// releaseKinds are the kinds that took the lock off. Overrides and peeks are
// gone, but their rows still count toward the days they happened on.
var releaseKinds = []string{DecisionGrant, "override", "short_unblock"}

// IsRelease reports whether a decision of this kind took the lock off.
func IsRelease(kind string) bool {
	return slices.Contains(releaseKinds, kind)
}

// LockDecision is one persisted agent-lock decision.
type LockDecision struct {
	ID              int64  `json:"id"`
	Kind            string `json:"kind"`
	Source          string `json:"source"`
	UserMessage     string `json:"user_message"`
	AgentMessage    string `json:"agent_message"`
	DurationSeconds int    `json:"duration_seconds"`
	Created         string `json:"created"`
}

// UnlockDay is one local day of the unlock ledger: how often the lock came off
// and for how long in total.
type UnlockDay struct {
	Day             string `json:"day"`
	Unlocks         int    `json:"unlocks"`
	UnlockedSeconds int    `json:"unlocked_seconds"`
}

// unlockRow is one decision reduced to what the daily buckets need.
type unlockRow struct {
	createdAt       time.Time
	durationSeconds int
}

// InsertLockDecision records one decision. Source says where it came from.
func (m *Manager) InsertLockDecision(kind, source, userMessage, agentMessage string, durationSeconds int) error {
	ctx, cancel := operationContext()
	defer cancel()

	_, err := m.pool.Exec(ctx, `
		INSERT INTO lock_decisions
			(kind, source, user_message, agent_message, duration_seconds)
		VALUES ($1, $2, $3, $4, $5)
	`, kind, source, userMessage, agentMessage, durationSeconds)
	return err
}

// GetTodayLockDecisions returns today's decisions oldest first, using the
// server's configured local timezone.
func (m *Manager) GetTodayLockDecisions() ([]LockDecision, error) {
	start, end := localDayBounds(time.Now())
	ctx, cancel := operationContext()
	defer cancel()

	rows, err := m.pool.Query(ctx, `
		SELECT `+lockDecisionColumns+`
		FROM lock_decisions
		WHERE created_at >= $1 AND created_at < $2
		ORDER BY created_at
		LIMIT 500
	`, start, end)
	if err != nil {
		return nil, err
	}
	return scanLockDecisions(rows)
}

// ListLockDecisions returns up to limit decisions older than beforeID, newest
// first; a beforeID of 0 starts from the newest. Paging by id rather than by
// time keeps a page boundary from splitting rows that share a timestamp.
func (m *Manager) ListLockDecisions(beforeID int64, limit int) ([]LockDecision, error) {
	ctx, cancel := operationContext()
	defer cancel()

	rows, err := m.pool.Query(ctx, `
		SELECT `+lockDecisionColumns+`
		FROM lock_decisions
		WHERE $1 = 0 OR id < $1
		ORDER BY id DESC
		LIMIT $2
	`, beforeID, limit)
	if err != nil {
		return nil, err
	}
	return scanLockDecisions(rows)
}

const lockDecisionColumns = "id, kind, source, user_message, agent_message, duration_seconds, created_at"

func scanLockDecisions(rows pgx.Rows) ([]LockDecision, error) {
	defer rows.Close()
	decisions := make([]LockDecision, 0)
	for rows.Next() {
		var decision LockDecision
		var createdAt time.Time
		if err := rows.Scan(
			&decision.ID,
			&decision.Kind,
			&decision.Source,
			&decision.UserMessage,
			&decision.AgentMessage,
			&decision.DurationSeconds,
			&createdAt,
		); err != nil {
			return nil, err
		}
		decision.Created = createdAt.UTC().Format(time.RFC3339)
		decisions = append(decisions, decision)
	}
	return decisions, rows.Err()
}

// GetUnlockStats returns one row per local day for the last `days` days, oldest
// first, quiet days included as zeroes. Every release kind counts: either way
// the lock was off, which is what the phone's header reports.
func (m *Manager) GetUnlockStats(days int) ([]UnlockDay, error) {
	if days < 1 {
		days = 1
	}
	todayStart, end := localDayBounds(time.Now())
	start := todayStart.AddDate(0, 0, -(days - 1))

	ctx, cancel := operationContext()
	defer cancel()

	// Bucketing happens in Go rather than SQL so the day boundaries are the same
	// ones localDayBounds draws — one definition of "a day", not two.
	result, err := m.pool.Query(ctx, `
		SELECT created_at, duration_seconds
		FROM lock_decisions
		WHERE created_at >= $1 AND created_at < $2
		AND kind = ANY($3)
		ORDER BY created_at
	`, start, end, releaseKinds)
	if err != nil {
		return nil, err
	}
	defer result.Close()

	rows := make([]unlockRow, 0)
	for result.Next() {
		var row unlockRow
		if err := result.Scan(&row.createdAt, &row.durationSeconds); err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
	if err := result.Err(); err != nil {
		return nil, err
	}

	return bucketUnlockDays(rows, start, days), nil
}

// bucketUnlockDays folds decisions into one entry per day, oldest first.
func bucketUnlockDays(rows []unlockRow, start time.Time, days int) []UnlockDay {
	const dayFormat = "2006-01-02"

	byDay := make(map[string]*UnlockDay, days)
	stats := make([]UnlockDay, 0, days)
	for offset := 0; offset < days; offset++ {
		day := start.AddDate(0, 0, offset).Format(dayFormat)
		stats = append(stats, UnlockDay{Day: day})
	}
	for index := range stats {
		byDay[stats[index].Day] = &stats[index]
	}

	for _, row := range rows {
		// Local time, so a decision lands on the day the user lived it.
		day := byDay[row.createdAt.In(start.Location()).Format(dayFormat)]
		if day == nil {
			continue
		}
		day.Unlocks++
		day.UnlockedSeconds += row.durationSeconds
	}
	return stats
}
