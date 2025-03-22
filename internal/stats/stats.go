package stats

import (
	"coach/internal/db"
	"time"
)

type Stats struct {
	focusingByDay map[string]int
}

func NewStats(manager *db.Manager) (*Stats, error) {
	stats := &Stats{
		focusingByDay: make(map[string]int),
	}
	
	// Get today's date in YYYY-MM-DD format
	today := time.Now().Format("2006-01-02")
	
	// Get count of today's focus entries
	count, err := manager.GetTodayFocusCount()
	if err != nil {
		return nil, err
	}
	
	// Store the count for today
	stats.focusingByDay[today] = count
	
	return stats, nil
}
