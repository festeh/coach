package coach

import (
	"encoding/json"
	"github.com/charmbracelet/log"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type InternalState struct {
	IsFocusing bool `json:"is_focusing"`
	// last time the focusing was changed
	LastChange time.Time `json:"changed_at"`
	// duration of the focus time left
	Duration time.Duration `json:"duration"`
}

type State struct {
	internal InternalState
	clients  map[*websocket.Conn]bool
	mu       sync.Mutex
}

type FocusInfo struct {
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
		Focusing:        s.IsFocusing,
		SinceLastChange: sinceLastChange / time.Second,
		FocusTimeLeft:   focusTimeLeft / time.Second,
	}
}


func (s *State) SetFocusing(duration time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.internal.IsFocusing = true
	s.internal.Duration = duration
	s.internal.LastChange = time.Now()
}

func (s *State) SetUnfocusing() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.internal.IsFocusing = false
	s.internal.LastChange = time.Now()
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
			s.SetUnfocusing()
			log.Info("Resetting focus after duration", "duration", durationSeconds)
			// Broadcast the state change after reset
			go server.BroadcastFocusState()
		}()
	} else {
		s.SetUnfocusing()
	}
	
	// Broadcast the new focus state to all connected clients
	go server.BroadcastFocusState()
	
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

func (s *State) BroadcastToClients(message interface{}) {
	log.Info("Start broadcast")
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Error("Error marshaling message", "err", err)
		return
	}
	
	s.mu.Lock()
	defer s.mu.Unlock()
	for client := range s.clients {
		err := client.WriteMessage(websocket.TextMessage, jsonMessage)
		log.Info("Send message", "msg", string(jsonMessage), "to", client.RemoteAddr())
		if err != nil {
			log.Error("Error sending message to client", "err", err)
			client.Close()
			delete(s.clients, client)
		}
	}
}
