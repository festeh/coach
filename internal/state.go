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
	LastChange time.Time

	clients       map[*websocket.Conn]bool
	focusRequests []FocusRequest
	hooks         []Hook
	mu            sync.Mutex
	stats         *stats.Stats
	expiryTimer   *time.Timer
}

// NewState creates a properly initialized State instance
func NewState(s *stats.Stats) *State {
	return &State{
		clients: make(map[*websocket.Conn]bool),
		stats:   s,
	}
}

// IsFocusing returns true if there is remaining focus time (thread-safe)
func (s *State) IsFocusing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.getTimeLeftLocked() > 0
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
	focusTimeLeft := s.getTimeLeftLocked()
	sinceLastChange := time.Since(s.LastChange)
	numFocuses := 0
	if s.stats != nil {
		numFocuses = s.stats.GetTodayFocusCount()
	}
	return FocusInfo{
		Type:            "focusing",
		Focusing:        s.getTimeLeftLocked() > 0,
		SinceLastChange: sinceLastChange / time.Second,
		FocusTimeLeft:   focusTimeLeft / time.Second,
		NumFocuses:      numFocuses,
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

	// Only update LastChange if we're starting a new focus session
	// (not already focusing)
	if s.getTimeLeftLocked() <= 0 {
		s.LastChange = time.Now()
	}

	// Find the latest EndTime from existing focus requests
	now := time.Now()
	latestEndTime := now
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}

	// Add new focus period starting from the latest end time
	s.focusRequests = append(s.focusRequests, FocusRequest{
		StartTime: latestEndTime,
		EndTime:   latestEndTime.Add(duration),
	})

	if s.stats != nil {
		s.stats.BumpTodaysFocusCount()
	}

	// Schedule expiry timer while still holding the lock
	s.scheduleExpiryTimer()

	// Get a copy of hooks to execute outside the lock
	hooks := make([]Hook, len(s.hooks))
	copy(hooks, s.hooks)
	s.mu.Unlock()

	// Execute hooks outside the lock to prevent deadlocks
	for _, hook := range hooks {
		hook(s)
	}
}

// scheduleExpiryTimer schedules a single timer for when focus ends. Must be called with mutex held.
func (s *State) scheduleExpiryTimer() {
	// Cancel existing timer if any
	if s.expiryTimer != nil {
		s.expiryTimer.Stop()
		s.expiryTimer = nil
	}

	// Find the latest end time
	timeLeft := s.getTimeLeftLocked()
	if timeLeft <= 0 {
		return
	}

	s.expiryTimer = time.AfterFunc(timeLeft, func() {
		s.mu.Lock()
		// Remove all expired focus requests
		now := time.Now()
		for i := 0; i < len(s.focusRequests); i++ {
			if s.focusRequests[i].EndTime.Before(now) {
				s.focusRequests = slices.Delete(s.focusRequests, i, i+1)
				i--
			}
		}

		// Check if all focus periods have ended
		if len(s.focusRequests) == 0 {
			s.LastChange = now
			s.expiryTimer = nil
			s.mu.Unlock()

			log.Info("All focus periods expired")
			message := s.GetCurrentFocusInfo()
			go s.NotifyAllClients(message)
		} else {
			// Reschedule for remaining focus periods
			s.scheduleExpiryTimer()
			s.mu.Unlock()
		}
	})
}

// clearFocus cancels all focus requests and the expiry timer
func (s *State) clearFocus() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.expiryTimer != nil {
		s.expiryTimer.Stop()
		s.expiryTimer = nil
	}
	s.focusRequests = nil
	s.LastChange = time.Now()
}

func (s *State) HandleFocusChange(focusing bool, durationSeconds int) {
	if focusing {
		s.SetFocusing(time.Duration(durationSeconds) * time.Second)
	} else {
		s.clearFocus()
	}

	message := s.GetCurrentFocusInfo()
	go s.NotifyAllClients(message)
}

// getTimeLeftLocked calculates remaining focus time. Must be called with mutex held.
func (s *State) getTimeLeftLocked() time.Duration {
	now := time.Now()
	latestEndTime := now
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}
	return latestEndTime.Sub(now)
}

// GetTimeLeft returns the remaining focus time (thread-safe)
func (s *State) GetTimeLeft() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.getTimeLeftLocked()
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
	// Copy clients to avoid holding lock during I/O
	clients := make([]*websocket.Conn, 0, len(s.clients))
	for client := range s.clients {
		clients = append(clients, client)
	}
	s.mu.Unlock()

	// Notify clients without holding lock
	var failedClients []*websocket.Conn
	for _, client := range clients {
		if err := s.NotifySingleClient(client, message); err != nil {
			failedClients = append(failedClients, client)
		}
	}

	// Remove failed clients
	if len(failedClients) > 0 {
		s.mu.Lock()
		for _, client := range failedClients {
			delete(s.clients, client)
		}
		s.mu.Unlock()
	}
}
