package stats

import (
	"coach/internal/db"
	"time"
)

type Stats struct {
	focusingByDay map[string]int
}

// getToday returns today's date in YYYY-MM-DD format
func (s *Stats) getToday() string {
	return time.Now().Format("2006-01-02")
}

// GetTodayFocusCount returns the number of focus entries for today
func (s *Stats) GetTodayFocusCount() int {
	return s.focusingByDay[s.getToday()]
}

// BumpTodaysFocusCount increments the count of focus entries for today
func (s *Stats) BumpTodaysFocusCount() {
	s.focusingByDay[s.getToday()]++
}

func NewStats(manager *db.Manager) (*Stats, error) {
	stats := &Stats{
		focusingByDay: make(map[string]int),
	}
	
	// Get count of today's focus entries
	count, err := manager.GetTodayFocusCount()
	if err != nil {
		return nil, err
	}
	
	// Store the count for today
	stats.focusingByDay[stats.getToday()] = count
	
	return stats, nil
}
