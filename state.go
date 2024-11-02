package main

import (
	"encoding/json"
	"log"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type InternalState struct {
	IsFocusing bool `json:"is_focusing"`
	// last time the focus has occured
	FocusedAt time.Time `json:"focused_at"`
	// duration in seconds
	Duration int `json:"duration"`
}

type State struct {
	internal InternalState
	clients  map[*websocket.Conn]bool
	mu       sync.Mutex
}

func (s *State) SetFocusing(focusing bool) error {
	s.mu.Lock()
	s.internal.IsFocusing = focusing
	s.mu.Unlock()
	return s.Save()
}

func (s *State) Focusing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.internal.IsFocusing
}

func (s *State) SetDuration(duration int) error {
	s.mu.Lock()
	s.internal.Duration = duration
	s.mu.Unlock()
	return s.Save()
}

func (s *State) Duration() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.internal.Duration
}

func (s *State) SetFocusedAt(focusedAt time.Time) error {
	s.mu.Lock()
	s.internal.FocusedAt = focusedAt
	s.mu.Unlock()
	return s.Save()
}

func (s *State) FocusedAt() time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.internal.FocusedAt
}

func (s *State) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.save()
}

var state = &State{}

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

func (s *State) BroadcastToClients(message []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for client := range s.clients {
		err := client.WriteMessage(websocket.TextMessage, message)
		if err != nil {
			log.Printf("Error sending message to client: %v", err)
			client.Close()
			delete(s.clients, client)
		}
	}
}
