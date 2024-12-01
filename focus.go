package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"
)

// @Summary Get or set focus state
// @Description Get the current focus state or set a new focus state with duration
// @Tags focus
// @Accept x-www-form-urlencoded
// @Produce json
// @Param focusing formData bool false "Set focus state to true/false"
// @Param duration formData int false "Duration in seconds for focus period (default 30)"
// @Success 200 {object} map[string]interface{} "Returns focus state"
// @Failure 400 {string} string "Bad request"
// @Failure 405 {string} string "Method not allowed"
// @Failure 500 {string} string "Internal server error"
// @Router /focusing [get]
// @Router /focusing [post]
func focusHandler(w http.ResponseWriter, r *http.Request) {
	log.Println("Got a request to /focusing")
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if r.Method == http.MethodGet {
		w.WriteHeader(http.StatusOK)
		message := struct {
			Focusing bool `json:"focusing"`
		}{
			Focusing: state.Focusing(),
		}
		jsonMessage, err := json.Marshal(message)
		if err != nil {
			log.Printf("Error marshaling focus state: %v", err)
			return
		}
		w.Write(jsonMessage)
		return
	}

	// POST method
	err := r.ParseForm()
	if err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	focusing := r.FormValue("focusing") == "true"
	log.Println("focusing: ", focusing)
	err = state.SetFocusing(focusing)
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
	if focusing {
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
	if focusing {
		w.Write([]byte("Now focusing"))
	} else {
		w.Write([]byte("No longer focusing"))
	}
}

func broadcastFocusState() {
	message := struct {
		Event    string `json:"event"`
		Focusing bool   `json:"focusing"`
	}{
		Event:    "focusing",
		Focusing: state.Focusing(),
	}

	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}

	state.BroadcastToClients(jsonMessage)
}
