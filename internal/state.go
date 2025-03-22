package coach

import (
	"encoding/json"
	"github.com/charmbracelet/log"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type FocusRequest struct {
	StartTime time.Time
	EndTime   time.Time
}

type InternalState struct {
	IsFocusing bool `json:"is_focusing"`
	// last time the focusing was changed
	LastChange time.Time `json:"changed_at"`
	// duration of the focus time left
	Duration time.Duration `json:"duration"`
}


type State struct {
	internal      InternalState
	clients       map[*websocket.Conn]bool
	focusRequests []FocusRequest
	hooks         []Hook
	mu            sync.Mutex
}

type FocusInfo struct {
	Type            string        `json:"type"`
	Focusing        bool          `json:"focusing"`
	SinceLastChange time.Duration `json:"since_last_change"`
	FocusTimeLeft   time.Duration `json:"focus_time_left"`
}

func GetFocusInfo(s *InternalState) FocusInfo {
	focusTimeLeft := time.Duration(0)
	sinceLastChange := time.Since(s.LastChange)
	if s.IsFocusing {
		focusTimeLeft = s.Duration - sinceLastChange
	}
	if focusTimeLeft < 0 {
		focusTimeLeft = time.Duration(0)
	}
	return FocusInfo{
		Type:            "focusing",
		Focusing:        s.IsFocusing,
		SinceLastChange: sinceLastChange / time.Second,
		FocusTimeLeft:   focusTimeLeft / time.Second,
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
	s.internal.IsFocusing = true
	s.internal.Duration = duration
	s.internal.LastChange = time.Now()
	s.focusRequests = append(s.focusRequests, FocusRequest{
		StartTime: s.internal.LastChange,
		EndTime:   s.internal.LastChange.Add(duration),
	})
	
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
	s.internal.IsFocusing = false
	s.internal.LastChange = time.Now()
	
	// Get a copy of hooks to execute outside the lock
	hooks := make([]Hook, len(s.hooks))
	copy(hooks, s.hooks)
	s.mu.Unlock()
	
	// Execute hooks outside the lock to prevent deadlocks
	for _, hook := range hooks {
		hook(s)
	}
}

// HandleFocusChange processes a focus state change request
// It returns the updated focus info, broadcasts the change, and schedules auto-reset if needed
func (s *State) HandleFocusChange(focusing bool, durationSeconds int, server *Server) FocusInfo {
	focusDuration := time.Duration(durationSeconds) * time.Second

	if focusing {
		s.SetFocusing(focusDuration)

		// Schedule auto-reset if focusing
		go func() {
			time.Sleep(focusDuration)

			s.mu.Lock()
			// Remove expired focus request
			for i := 0; i < len(s.focusRequests); i++ {
				if s.focusRequests[i].EndTime.Before(time.Now()) || s.focusRequests[i].EndTime.Equal(time.Now()) {
					s.focusRequests = append(s.focusRequests[:i], s.focusRequests[i+1:]...)
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
		// If explicitly unfocusing, clear all focus requests
		s.mu.Lock()
		s.focusRequests = nil
		s.mu.Unlock()

		s.SetUnfocusing()
	}

	// Broadcast the new focus state to all connected clients
	message := s.GetCurrentFocusInfo()
	go s.NotifyAllClients(message)

	// Get the updated focus info
	s.mu.Lock()
	info := GetFocusInfo(&s.internal)
	s.mu.Unlock()

	return info
}

// GetCurrentFocusInfo returns the current focus state information
func (s *State) GetCurrentFocusInfo() FocusInfo {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Clean up expired focus requests
	now := time.Now()
	activeRequests := []FocusRequest{}
	for _, req := range s.focusRequests {
		if req.EndTime.After(now) {
			activeRequests = append(activeRequests, req)
		}
	}
	s.focusRequests = activeRequests

	// Find the latest end time among active requests
	var latestEndTime time.Time
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}

	// Update duration if we have active requests
	if len(s.focusRequests) > 0 && s.internal.IsFocusing {
		s.internal.Duration = latestEndTime.Sub(s.internal.LastChange)
	}

	return GetFocusInfo(&s.internal)
}

func (s *State) Focusing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.internal.IsFocusing
}

func (s *State) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.save()
}

func (s *State) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, err := os.Stat(stateFile); os.IsNotExist(err) {
		s.internal.IsFocusing = false
		return s.save()
	}

	data, err := os.ReadFile(stateFile)
	if err != nil {
		return err
	}

	s.internal.LastChange = time.Now()
	return json.Unmarshal(data, &s.internal)
}

// save is an internal method that saves the state without acquiring the mutex
const stateFile = "state.json"

func (s *State) save() error {
	data, err := json.Marshal(&s.internal)
	if err != nil {
		return err
	}

	return os.WriteFile(stateFile, data, 0644)
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
