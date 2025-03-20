package coach

import (
	"net/http"
	"github.com/gorilla/websocket"
)

// Server encapsulates all the state and handlers for the coach application
type Server struct {
	State      *State
	QuoteStore *QuoteStore
	upgrader   websocket.Upgrader
}

// NewServer creates and initializes a new server instance
func NewServer() (*Server, error) {
	server := &Server{
		State:      &State{},
		QuoteStore: &QuoteStore{},
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}

	err := server.State.Load()
	if err != nil {
		return nil, err
	}

	err = server.QuoteStore.Load()
	if err != nil {
		return nil, err
	}

	return server, nil
}

// SetupRoutes configures all HTTP routes for the server
func (s *Server) SetupRoutes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.HealthHandler)
	mux.HandleFunc("/focusing", s.FocusHandler)
	mux.HandleFunc("/connect", s.WebsocketHandler)
	
	return mux
}

// BroadcastFocusState sends the current focus state to all connected clients
func (s *Server) BroadcastFocusState() {
	message := s.State.GetCurrentFocusInfo()
	s.State.NotifyAllClients(message)
}

// BroadcastQuote sends a random quote to all connected clients
func (s *Server) BroadcastQuote() {
	message := struct {
		Event string `json:"event"`
		Quote string `json:"quote"`
	}{
		Event: "quote",
		Quote: s.QuoteStore.GetQuote().Text,
	}
	
	s.State.NotifyAllClients(message)
}
