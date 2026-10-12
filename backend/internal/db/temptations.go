package db

import "time"

// InsertTemptation records one blocked attempt.
func (m *Manager) InsertTemptation(source, target string) error {
	ctx, cancel := operationContext()
	defer cancel()

	_, err := m.pool.Exec(ctx, `
		INSERT INTO temptations (source, target)
		VALUES ($1, $2)
	`, source, target)
	return err
}

// CountTodayTemptations returns today's blocked-attempt count in the server's
// configured local timezone.
func (m *Manager) CountTodayTemptations() (int, error) {
	start, end := localDayBounds(time.Now())
	ctx, cancel := operationContext()
	defer cancel()

	var count int
	err := m.pool.QueryRow(ctx, `
		SELECT count(*) FROM temptations
		WHERE created_at >= $1 AND created_at < $2
	`, start, end).Scan(&count)
	return count, err
}
