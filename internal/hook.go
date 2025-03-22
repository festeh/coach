package coach

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/charmbracelet/log"

	"coach/internal/db"
)

// Hook is a function that is called when focus state changes
type Hook func(*State)

func DatabaseHook(manager *db.Manager) Hook {
	return func(s *State) {

		s.mu.Lock()
		duration := s.internal.Duration
		timestamp := s.internal.LastChange
		s.mu.Unlock()

		// Create record data
		record := map[string]interface{}{
			"timestamp": timestamp.Format(time.RFC3339),
			"duration":  int(duration.Seconds()),
		}

		jsonData, err := json.Marshal(record)
		if err != nil {
			log.Error("Failed to marshal focus record", "error", err)
			return
		}

		go func() {
			endpoint := fmt.Sprintf("%s/api/collections/coach/records", manager.BaseURL)
			req, err := http.NewRequest("POST", endpoint, bytes.NewBuffer(jsonData))
			if err != nil {
				log.Error("Failed to create request", "error", err)
				return
			}

			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", manager.AuthToken)

			resp, err := manager.Client.Do(req)
			if err != nil {
				log.Error("Failed to send focus record to database", "error", err)
				return
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				log.Info("Focus record saved to database",
					"timestamp", timestamp.Format(time.RFC3339),
					"duration", duration.String())
			} else {
				log.Error("Failed to save focus record", "status", resp.StatusCode)
			}
		}()
	}
}
