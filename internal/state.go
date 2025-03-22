package coach

import (
	"coach/internal/stats"
	"encoding/json"
	"sync"
	"time"

	"github.com/charmbracelet/log"

	"github.com/gorilla/websocket"
	"slices"
)

type FocusRequest struct {
	StartTime time.Time
	EndTime   time.Time
}

type State struct {
	IsFocusing bool
	LastChange time.Time

	clients       map[*websocket.Conn]bool
	focusRequests []FocusRequest
	hooks         []Hook
	mu            sync.Mutex
	stats         *stats.Stats
}

type FocusInfo struct {
	Type            string        `json:"type"`
	Focusing        bool          `json:"focusing"`
	SinceLastChange time.Duration `json:"since_last_change"`
	FocusTimeLeft   time.Duration `json:"focus_time_left"`
	NumFocuses      int           `json:"num_focuses"`
}

func (s *State) GetCurrentFocusInfo() FocusInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	focusTimeLeft := s.GetTimeLeft()
	sinceLastChange := time.Since(s.LastChange)
	return FocusInfo{
		Type:            "focusing",
		Focusing:        s.IsFocusing,
		SinceLastChange: sinceLastChange / time.Second,
		FocusTimeLeft:   focusTimeLeft / time.Second,
		NumFocuses:      s.stats.GetTodayFocusCount(),
	}
}

// AddHook registers a new hook function to be called when focus state changes
func (s *State) AddHook(hook Hook) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.hooks = append(s.hooks, hook)
}

func (s *State) SetFocusing(duration time.Duration) {
	s.mu.Lock()
	s.IsFocusing = true
	s.LastChange = time.Now()
	s.focusRequests = append(s.focusRequests, FocusRequest{
		StartTime: s.LastChange,
		EndTime:   s.LastChange.Add(duration),
	})

  s.stats.BumpTodaysFocusCount()

	// Get a copy of hooks to execute outside the lock
	hooks := make([]Hook, len(s.hooks))
	copy(hooks, s.hooks)
	s.mu.Unlock()

	// Execute hooks outside the lock to prevent deadlocks
	for _, hook := range hooks {
		hook(s)
	}


}

func (s *State) SetUnfocusing() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.IsFocusing = false
	s.LastChange = time.Now()
}

func (s *State) HandleFocusChange(focusing bool, durationSeconds int) {
	focusDuration := time.Duration(durationSeconds) * time.Second

	if focusing {
		s.SetFocusing(focusDuration)

		// Schedule auto-reset if focusing
		go func() {
			time.Sleep(focusDuration)

			s.mu.Lock()
			// Remove expired focus request
			for i := 0; i < len(s.focusRequests); i++ {
				if s.focusRequests[i].EndTime.Before(time.Now()) {
					s.focusRequests = slices.Delete(s.focusRequests, i, i+1)
					i-- // Adjust index after removal
				}
			}

			shouldUnfocus := len(s.focusRequests) == 0
			s.mu.Unlock()

			if shouldUnfocus {
				log.Info("All focus periods expired, unfocusing", "duration", durationSeconds)
				s.SetUnfocusing()
				message := s.GetCurrentFocusInfo()
				go s.NotifyAllClients(message)
			} else {
				log.Info("Focus period expired but other active focus periods remain")
			}
		}()
	} else {
		s.mu.Lock()
		s.focusRequests = nil
		s.mu.Unlock()
		s.SetUnfocusing()
	}

	message := s.GetCurrentFocusInfo()
	go s.NotifyAllClients(message)
}

func (s *State) GetTimeLeft() time.Duration {
	now := time.Now()
	latestEndTime := now
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}
	return latestEndTime.Sub(now)
}

func (s *State) AddClient(client *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.clients == nil {
		s.clients = make(map[*websocket.Conn]bool)
	}
	s.clients[client] = true
}

func (s *State) RemoveClient(client *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.clients, client)
}

func (s *State) NotifySingleClient(client *websocket.Conn, message any) error {
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		return err
	}

	err = client.WriteMessage(websocket.TextMessage, jsonMessage)
	log.Info("Notifying", "msg", string(jsonMessage), "to", client.RemoteAddr())
	if err != nil {
		log.Error("Error sending message to client", "err", err)
		client.Close()
		return err
	}
	return nil
}

func (s *State) NotifyAllClients(message any) {
	s.mu.Lock()
	log.Info("Notifying all clients", "count", len(s.clients))
	defer s.mu.Unlock()
	for client := range s.clients {
		if err := s.NotifySingleClient(client, message); err != nil {
			delete(s.clients, client)
		}
	}
}
