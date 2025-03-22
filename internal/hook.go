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
		if len(s.focusRequests) == 0 {
			log.Error("No focus requests found")
			return
		}

		s.mu.Lock()
		// get latest focus request
		request := s.focusRequests[len(s.focusRequests)-1]
		s.mu.Unlock()

		duration := request.EndTime.Sub(request.StartTime)

		record := map[string]any{
			"timestamp": time.Now().Format(time.RFC3339),
			"duration":  int(duration.Seconds()),
		}

		go func() {
			if err := manager.AddRecord(record); err != nil {
				log.Error("Failed to add focus record to database", "error", err)
				return
			}

			log.Info("Focus record saved to database",
				"timestamp", record["timestamp"],
				"duration", duration.String())
		}()
	}
}
