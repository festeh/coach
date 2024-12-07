package main

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
	// If IsFocusing=true shows remaining time
	FocusTimeLeft time.Duration `json:"left"`
}

type State struct {
	internal InternalState
	clients  map[*websocket.Conn]bool
	mu       sync.Mutex
}

func (s *State) BroadcastFocusStateEveryMinute() {
	ticker := time.NewTicker(1 * time.Minute)
	go func() {
		for range ticker.C {
			broadcastFocusState()
		}
	}()
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
	log.Info("Start broadcast")
	s.mu.Lock()
	defer s.mu.Unlock()
	for client := range s.clients {
		err := client.WriteMessage(websocket.TextMessage, message)
		log.Info("Send message", "msg", string(message), "to", client.RemoteAddr())
		if err != nil {
			log.Error("Error sending message to client", "err", err)
			client.Close()
			delete(s.clients, client)
		}
	}
}
