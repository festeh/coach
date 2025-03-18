package coach

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/charmbracelet/log"
)

// @Summary Health check endpoint
// @Description Returns the health status of the API
// @Tags health
// @Produce plain
// @Success 200 {string} string "Healthy"
// @Router /health [get]
func (s *Server) HealthHandler(w http.ResponseWriter, r *http.Request) {
  w.WriteHeader(http.StatusOK)
  w.Write([]byte("Healthy"))
}

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
func (s *Server) FocusHandler(w http.ResponseWriter, r *http.Request) {
  log.Info("Focusing", "method", r.Method)
  if r.Method != http.MethodGet && r.Method != http.MethodPost {
    http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
    return
  }

  if r.Method == http.MethodGet {
    w.WriteHeader(http.StatusOK)
    message := GetFocusInfo(&s.State.internal)
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
    s.State.SetFocusing(focusDuration)
  } else {
    s.State.SetUnfocusing()
  }

  // Broadcast the new focus state to all connected clients
  go s.BroadcastFocusState()

  // If focusing is true, start a goroutine to set focus to false after the specified duration
  if focusing {
    go func() {
      time.Sleep(time.Duration(durationInt) * time.Second)
      s.State.SetUnfocusing()
      log.Info("Resetting focus after [duration] seconds", "duration", duration)
      go s.BroadcastFocusState()
    }()
  }

  w.WriteHeader(http.StatusOK)
  message := GetFocusInfo(&s.State.internal)
  jsonMessage, err := json.Marshal(message)
  if err != nil {
    log.Printf("Error marshaling focus state: %v", err)
    return
  }
  w.Write(jsonMessage)
}

// @Summary WebSocket connection endpoint
// @Description Establishes a WebSocket connection for real-time updates
// @Tags websocket
// @Accept json
// @Produce json
// @Success 101 {string} string "Switching Protocols to WebSocket"
// @Failure 400 {string} string "Bad Request"
// @Router /connect [get]
func (s *Server) WebsocketHandler(w http.ResponseWriter, r *http.Request) {
  log.Info("Client connected")
  conn, err := s.upgrader.Upgrade(w, r, nil)
  if err != nil {
    log.Error(err)
    return
  }

  s.State.AddClient(conn)

  defer func() {
    conn.Close()
    s.State.RemoveClient(conn)
  }()

  for {
    messageType, p, err := conn.ReadMessage()
    fmt.Println(messageType, string(p), err)
    if err != nil {
      log.Error(err)
      return
    }
    if string(p) == "get_quote" {
      s.BroadcastQuote()
    }
    if string(p) == "get_focusing" {
      s.BroadcastFocusState()
    }
  }
}
