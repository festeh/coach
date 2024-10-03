package main

import (
	"encoding/json"
	"io/ioutil"
	"os"
	"sync"
)

type State struct {
	IsFocusing bool `json:"is_focusing"`
	mu         sync.Mutex
}

const stateFile = "state.json"

var state = &State{}

func (s *State) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, err := os.Stat(stateFile); os.IsNotExist(err) {
		s.IsFocusing = false
		return s.Save()
	}

	data, err := ioutil.ReadFile(stateFile)
	if err != nil {
		return err
	}

	return json.Unmarshal(data, s)
}

func (s *State) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := json.Marshal(s)
	if err != nil {
		return err
	}

	return ioutil.WriteFile(stateFile, data, 0644)
}

func (s *State) SetFocusing(focusing bool) error {
	s.mu.Lock()
	s.IsFocusing = focusing
	s.mu.Unlock()
	return s.Save()
}

func (s *State) IsFocusing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.IsFocusing
}
