package coach

import (
	"io/fs"
	"net/http"
	"time"

	"github.com/gorilla/websocket"

	"github.com/charmbracelet/log"

	"coach/internal/ai"
	"coach/internal/db"
	"coach/internal/dimaist"
	"coach/internal/stats"
)

// Server encapsulates all the state and handlers for the coach application
type Server struct {
	State         *State
	QuoteStore    *QuoteStore
	DBManager     *db.Manager
	HookRunner    *HookRunner
	DimaistClient *dimaist.Client
	AdminFS       fs.FS
	upgrader      websocket.Upgrader
}

// NewServer creates and initializes a new server instance
func NewServer(adminFS fs.FS) (*Server, error) {
	server := &Server{
		State: &State{
			LastChange: time.Now(),
		},
		QuoteStore: &QuoteStore{},
		AdminFS:    adminFS,
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

	// Initialize hook runner
	hookRunner := NewHookRunner(server.State, dbManager)
	hookRunner.SetServer(server)

	// Initialize dimaist client (optional)
	var dimaistClient *dimaist.Client
	dimaistClient, err = dimaist.NewClient()
	if err != nil {
		log.Warn("Dimaist client not available, tasks won't be included in AI context", "error", err)
	}
	server.DimaistClient = dimaistClient

	// Register AI hook (only if AI env vars are set)
	aiClient, err := ai.NewClient()
	if err != nil {
		log.Warn("AI client not available, AI hook disabled", "error", err)
	} else {
		hookRunner.Register(NewAIHookDef(aiClient, dimaistClient))
	}

	hookRunner.StartSchedulers()
	server.HookRunner = hookRunner

	return server, nil
}

// corsMiddleware adds CORS headers to all responses
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, OPTIONS")
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
	mux.HandleFunc("/api/hooks", s.HooksHandler)
	mux.HandleFunc("/api/hooks/", s.HookByIDHandler)
	mux.HandleFunc("/api/hook-results", s.HookResultsHandler)
	mux.HandleFunc("/api/hook-results/", s.HookResultByIDHandler)
	mux.Handle("/admin/", s.AdminHandler())

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
