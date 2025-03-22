package coach

import (
	"time"

	"github.com/charmbracelet/log"

	"coach/internal/db"
)

// Hook is a function that is called when focus state changes
type Hook func(*State)

// DatabaseHook creates a hook that records focus state changes to the database
func DatabaseHook(manager *db.Manager) Hook {
	return func(s *State) {
		// Skip if not focusing (only record when focus starts)
		if !s.Focusing() {
			return
		}

		// Get current state information
		s.mu.Lock()
		duration := s.internal.Duration
		s.mu.Unlock()

		// Create record data with current timestamp
		record := map[string]interface{}{
			"timestamp": time.Now().Format(time.RFC3339),
			"duration":  int(duration.Seconds()),
		}

		// Send to database in a goroutine to avoid blocking
		go func() {
			if err := manager.AddRecord("coach", record); err != nil {
				log.Error("Failed to add focus record to database", "error", err)
				return
			}
			
			log.Info("Focus record saved to database",
				"timestamp", record["timestamp"],
				"duration", duration.String())
		}()
	}
}
