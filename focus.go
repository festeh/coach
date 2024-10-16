package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"
)

func focusHandler(w http.ResponseWriter, r *http.Request) {
  log.Println("Got a request to /focus")
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if r.Method == http.MethodGet {
		w.WriteHeader(http.StatusOK)
		if state.IsFocusing() {
			w.Write([]byte("Focusing"))
		} else {
			w.Write([]byte("Not focusing"))
		}
		return
	}

	// POST method
	err := r.ParseForm()
	if err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	isFocusing := r.FormValue("focus") == "true"
  log.Println("isFocusing: ", isFocusing)
	err = state.SetFocusing(isFocusing)
	if err != nil {
		http.Error(w, "Failed to set focus state", http.StatusInternalServerError)
		return
	}

	duration := r.FormValue("duration")
	if duration == "" {
		duration = "30"
	}
  log.Println("duration: ", duration)
	durationInt, err := strconv.Atoi(duration)
	if err != nil {
		http.Error(w, "Failed to parse duration", http.StatusBadRequest)
		return
	}
	err = state.SetDuration(durationInt)
	if err != nil {
		http.Error(w, "Failed to set duration", http.StatusInternalServerError)
		return
	}

	// Broadcast the new focus state to all connected clients
	go broadcastFocusState()

	// If focusing is true, start a goroutine to set focus to false after the specified duration
	if isFocusing {
		go func() {
			time.Sleep(time.Duration(durationInt) * time.Second)
			err := state.SetFocusing(false)
			if err != nil {
				log.Printf("Error setting focus to false after duration: %v", err)
			}
			log.Println("Setting focus to false after " + duration + " seconds")
			go broadcastFocusState()
		}()
	}

	w.WriteHeader(http.StatusOK)
	if isFocusing {
		w.Write([]byte("Now focusing"))
	} else {
		w.Write([]byte("No longer focusing"))
	}
}

func broadcastFocusState() {
	message := struct {
		Event string `json:"event"`
		Focus bool   `json:"focus"`
	}{
		Event: "focus",
		Focus: state.IsFocusing(),
	}

	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}

	state.BroadcastToClients(jsonMessage)
}
