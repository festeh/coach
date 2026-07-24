package db

import "time"

// LockDecision is one persisted agent-lock decision.
type LockDecision struct {
	Kind            string `json:"kind"`
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

// InsertLockDecision records a grant, override, or denial.
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
		SELECT kind, user_message, agent_message, duration_seconds, created_at
		FROM lock_decisions
		WHERE created_at >= $1 AND created_at < $2
		ORDER BY created_at
		LIMIT 500
	`, start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	decisions := make([]LockDecision, 0)
	for rows.Next() {
		var decision LockDecision
		var createdAt time.Time
		if err := rows.Scan(
			&decision.Kind,
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
// first, quiet days included as zeroes. Grants and overrides both count: either
// way the lock was off, which is what the phone's header reports.
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
		AND kind IN ('grant', 'override')
		ORDER BY created_at
	`, start, end)
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
