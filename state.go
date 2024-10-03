package main

import (
	"encoding/json"
	"os"
	"sync"
)

type State struct {
	isFocusing bool `json:"is_focusing"`
	mu         sync.Mutex
}

const stateFile = "state.json"

var state = &State{}

func (s *State) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, err := os.Stat(stateFile); os.IsNotExist(err) {
		s.isFocusing = false
		return s.save()
	}

	data, err := os.ReadFile(stateFile)
	if err != nil {
		return err
	}

	return json.Unmarshal(data, s)
}

// save is an internal method that saves the state without acquiring the mutex
func (s *State) save() error {
	data, err := json.Marshal(s)
	if err != nil {
		return err
	}

	return os.WriteFile(stateFile, data, 0644)
}

func (s *State) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.save()
}

func (s *State) SetFocusing(focusing bool) error {
	s.mu.Lock()
	s.isFocusing = focusing
	s.mu.Unlock()
	return s.Save()
}

func (s *State) IsFocusing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.isFocusing
}
