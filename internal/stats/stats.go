package stats

import "coach/internal/db"

type Stats struct {
	focusingByDay map[string]int
}

func NewStats(db *db.Manager) (*Stats, error) {
	// TODO: impl
	return &Stats{
		focusingByDay: make(map[string]int),
	}, nil
}
