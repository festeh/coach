package coach

import (
	"net/http"
	"time"

	"github.com/gorilla/websocket"

	"coach/internal/db"
	"coach/internal/stats"
)

// Server encapsulates all the state and handlers for the coach application
type Server struct {
	State      *State
	QuoteStore *QuoteStore
	DBManager  *db.Manager
	upgrader   websocket.Upgrader
}

// NewServer creates and initializes a new server instance
func NewServer() (*Server, error) {
	server := &Server{
		State: &State{
			LastChange: time.Now(),
		},
		QuoteStore: &QuoteStore{},
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}

	err := server.QuoteStore.Load()
	if err != nil {
		return nil, err
	}

	// Initialize database manager
	dbManager, err := db.InitManager()
	if err != nil {
		return nil, err
	}

	stats, err := stats.New(dbManager)
	if err != nil {
		return nil, err
	}

	server.State.stats = stats
	server.State.AddHook(DatabaseHook(dbManager))
	server.DBManager = dbManager

	return server, nil
}

// corsMiddleware adds CORS headers to all responses
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}

// SetupRoutes configures all HTTP routes for the server
func (s *Server) SetupRoutes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.HealthHandler)
	mux.HandleFunc("/focusing", s.FocusHandler)
	mux.HandleFunc("/history", s.HistoryHandler)
	mux.HandleFunc("/connect", s.WebsocketHandler)

	return corsMiddleware(mux)
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
