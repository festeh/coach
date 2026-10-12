package stats

import (
	"coach/internal/db"
	"sync"
	"time"
)

type Stats struct {
	mu            sync.Mutex
	focusingByDay map[string]int
}

func (s *Stats) getToday() string {
	return time.Now().Format("2006-01-02")
}

func (s *Stats) GetTodayFocusCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.focusingByDay[s.getToday()]
}

func (s *Stats) BumpTodaysFocusCount() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.focusingByDay[s.getToday()]++
}

func New(manager *db.Manager) (*Stats, error) {
	stats := &Stats{
		focusingByDay: make(map[string]int),
	}
	
	count, err := manager.GetTodayFocusCount()
	if err != nil {
		return nil, err
	}
	
	stats.focusingByDay[stats.getToday()] = count
	return stats, nil
}
