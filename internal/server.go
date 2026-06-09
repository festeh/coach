package coach

import (
	"io/fs"
	"net/http"
	"time"

	"github.com/gorilla/websocket"

	"github.com/charmbracelet/log"

	"coach/internal/db"
	"coach/internal/stats"
)

// Server encapsulates all the state and handlers for the coach application
type Server struct {
	State            *State
	DBManager        *db.Manager
	AttentionTracker *AttentionTracker
	AdminFS          fs.FS
	upgrader         websocket.Upgrader
}

// NewServer creates and initializes a new server instance
func NewServer(adminFS fs.FS) (*Server, error) {
	server := &Server{
		State: &State{
			LastChange: time.Now(),
		},
		AdminFS: adminFS,
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}

	// Initialize database manager
	dbManager, err := db.InitManager()
	if err != nil {
		return nil, err
	}

	// Auto-migrate collections owned by coach itself (not by the coach_db CLI).
	if created, err := dbManager.EnsureAgentLockCollection(); err != nil {
		log.Warn("Failed to ensure agent_lock collection — agent lock state won't persist", "error", err)
	} else if created {
		log.Info("Created agent_lock collection")
	}
	if created, err := dbManager.EnsureAttentionCollection(); err != nil {
		log.Warn("Failed to ensure attention collection — attention beacons won't persist", "error", err)
	} else if created {
		log.Info("Created attention collection")
	}
	if created, err := dbManager.EnsureLockDecisionsCollection(); err != nil {
		log.Warn("Failed to ensure lock_decisions collection — lock decisions won't be journaled", "error", err)
	} else if created {
		log.Info("Created lock_decisions collection")
	}
	if created, err := dbManager.EnsureTemptationsCollection(); err != nil {
		log.Warn("Failed to ensure temptations collection — temptations won't be recorded", "error", err)
	} else if created {
		log.Info("Created temptations collection")
	}
	server.AttentionTracker = NewAttentionTracker(dbManager)

	stats, err := stats.New(dbManager)
	if err != nil {
		return nil, err
	}

	server.State.stats = stats
	server.State.dbManager = dbManager
	server.State.AddHook(DatabaseHook(dbManager))

	// Restore active focus session from DB (if any)
	if remaining, err := dbManager.GetActiveFocus(); err != nil {
		log.Warn("Failed to check for active focus session", "error", err)
	} else if remaining > 0 {
		server.State.RestoreFocus(remaining)
	}

	// Restore active agent-lock release window from DB (if any)
	if releaseUntil, err := dbManager.GetAgentReleaseUntil(); err != nil {
		log.Warn("Failed to load agent lock state", "error", err)
	} else {
		server.State.RestoreAgentLock(releaseUntil)
	}
	server.DBManager = dbManager

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
	mux.HandleFunc("/attention", s.AttentionHandler)
	mux.HandleFunc("/connect", s.WebsocketHandler)
	mux.HandleFunc("/agent-lock", s.AgentLockHandler)
	mux.HandleFunc("/agent-lock/release", s.AgentLockHandler)
	mux.HandleFunc("/agent-lock/engage", s.AgentLockHandler)
	mux.HandleFunc("/agent-lock/state", s.AgentLockHandler)
	mux.HandleFunc("/lock-decisions", s.LockDecisionsHandler)
	mux.Handle("/admin/", s.AdminHandler())

	return corsMiddleware(mux)
}
