package main

import (
	"encoding/json"
	"github.com/charmbracelet/log"
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
	log.Info("Focusing", "method", r.Method)
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if r.Method == http.MethodGet {
		w.WriteHeader(http.StatusOK)
		message := GetFocusInfo(&state.internal)
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
	log.Info("", "focusing", focusing)

	duration := r.FormValue("duration")
	if duration == "" {
		duration = "30"
	}
	log.Info("", "duration", duration)
	durationInt, err := strconv.Atoi(duration)
	if err != nil {
		http.Error(w, "Failed to parse duration", http.StatusBadRequest)
		return
	}
	focusDuration := time.Duration(durationInt) * time.Second
	if focusing {
		state.SetFocusing(focusDuration)
	} else {
		state.SetUnfocusing()
	}

	// Broadcast the new focus state to all connected clients
	go broadcastFocusState()

	// If focusing is true, start a goroutine to set focus to false after the specified duration
	if focusing {
		go func() {
			time.Sleep(time.Duration(durationInt) * time.Second)
			state.SetUnfocusing()
			log.Info("Resetting focus after [duration] seconds", "duration", duration)
			go broadcastFocusState()
		}()
	}

	w.WriteHeader(http.StatusOK)
	message := GetFocusInfo(&state.internal)
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}
	w.Write(jsonMessage)
}

func broadcastFocusState() {
	message := GetFocusInfo(&state.internal)
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Printf("Error marshaling focus state: %v", err)
		return
	}

	state.BroadcastToClients(jsonMessage)
}
