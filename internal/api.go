package coach

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/charmbracelet/log"
	"github.com/gorilla/websocket"
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
	log.Info("Called /focusing", "method", r.Method)
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// GET method - return current focus state
	if r.Method == http.MethodGet {
		message := s.State.GetCurrentFocusInfo()
		jsonMessage, err := json.Marshal(message)
		if err != nil {
			log.Error("Error marshaling focus state", "err", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Write(jsonMessage)
		return
	}

	// POST method - update focus state
	err := r.ParseForm()
	if err != nil {
		http.Error(w, "Failed to parse form", http.StatusBadRequest)
		return
	}

	focusing := r.FormValue("focusing") == "true"
	log.Info("Focus change requested", "focusing", focusing)

	// Get duration parameter with default of 30 seconds
	duration := r.FormValue("duration")
	if duration == "" {
		duration = "30"
	}

	durationInt, err := strconv.Atoi(duration)
	if err != nil {
		http.Error(w, "Failed to parse duration", http.StatusBadRequest)
		return
	}
	log.Info("Focus parameters", "duration", durationInt)

	s.State.HandleFocusChange(focusing, durationInt)
	message := s.State.GetCurrentFocusInfo()

	// Return the updated focus state
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		log.Error("Error marshaling focus state", "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
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

	message := s.State.GetCurrentFocusInfo()
	if err := s.State.NotifySingleClient(conn, message); err != nil {
		log.Error("Failed to send initial focus state to client", "err", err)
	}

	defer func() {
		conn.Close()
		s.State.RemoveClient(conn)
	}()

	for {
		_, buf, err := conn.ReadMessage()
		if err != nil {
			log.Error("Error reading message", "err", err)
			return
		}

		var message struct {
			Type     string `json:"type"`
			Duration int    `json:"duration,omitempty"`
		}

		if err := json.Unmarshal(buf, &message); err != nil {
			continue
		}
		log.Debug("Received message", "type", message.Type)

		switch message.Type {
		case "get_quote":
			s.BroadcastQuote()
		case "get_focusing":
			focusInfo := s.State.GetCurrentFocusInfo()
			if err := s.State.NotifySingleClient(conn, focusInfo); err != nil {
				log.Error("Failed to send focus state to client", "err", err)
			}
		case "focus":
			duration := 30 * 60 // default 30 minutes
			if message.Duration > 0 {
				duration = message.Duration
			}
			s.State.HandleFocusChange(true, duration)
		case "ping":
			response := struct {
				Response string `json:"response"`
			}{
				Response: "pong",
			}

			jsonResponse, err := json.Marshal(response)
			if err != nil {
				log.Error("Error marshaling response", "err", err)
				return
			}

			err = conn.WriteMessage(websocket.TextMessage, jsonResponse)
			if err != nil {
				log.Error("Error sending response", "err", err)
				return
			}
		default:
			log.Warn("Unknown message type", "type", message.Type)
		}
	}
}
