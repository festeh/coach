package coach

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"github.com/charmbracelet/log"

	"coach/internal/db"
)

// HooksHandler handles GET /api/hooks — list all hooks with config and param definitions
func (s *Server) HooksHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	type hookResponse struct {
		ID          string            `json:"id"`
		Name        string            `json:"name"`
		Description string            `json:"description"`
		Params      []ParamDef        `json:"params"`
		Config      *db.HookConfigRecord `json:"config"`
	}

	defs := s.HookRunner.GetDefs()
	hooks := make([]hookResponse, 0, len(defs))

	for _, def := range defs {
		cfg := s.HookRunner.GetConfig(def.ID)
		hooks = append(hooks, hookResponse{
			ID:          def.ID,
			Name:        def.Name,
			Description: def.Description,
			Params:      def.Params,
			Config:      cfg,
		})
	}

	writeJSON(w, hooks)
}

// HookByIDHandler handles PUT /api/hooks/{id} and POST /api/hooks/{id}/trigger
func (s *Server) HookByIDHandler(w http.ResponseWriter, r *http.Request) {
	// Parse path: /api/hooks/{id} or /api/hooks/{id}/trigger
	path := strings.TrimPrefix(r.URL.Path, "/api/hooks/")
	parts := strings.SplitN(path, "/", 2)

	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "Hook ID required", http.StatusBadRequest)
		return
	}

	hookID := parts[0]
	action := ""
	if len(parts) == 2 {
		action = parts[1]
	}

	switch {
	case action == "trigger" && r.Method == http.MethodPost:
		s.triggerHook(w, hookID)
	case action == "context" && r.Method == http.MethodGet:
		s.hookContext(w, hookID)
	case action == "" && r.Method == http.MethodPut:
		s.updateHookConfig(w, r, hookID)
	default:
		http.Error(w, "Not found", http.StatusNotFound)
	}
}

func (s *Server) triggerHook(w http.ResponseWriter, hookID string) {
	log.Info("Manually triggering hook", "hook_id", hookID)

	if err := s.HookRunner.RunHook(hookID); err != nil {
		log.Error("Hook trigger failed", "hook_id", hookID, "error", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, map[string]string{"status": "ok"})
}

func (s *Server) updateHookConfig(w http.ResponseWriter, r *http.Request, hookID string) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read body", http.StatusBadRequest)
		return
	}

	var cfg db.HookConfigRecord
	if err := json.Unmarshal(body, &cfg); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}
	cfg.HookID = hookID

	if err := s.HookRunner.UpdateConfig(cfg); err != nil {
		log.Error("Failed to update hook config", "hook_id", hookID, "error", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, map[string]string{"status": "ok"})
}

func (s *Server) hookContext(w http.ResponseWriter, hookID string) {
	if hookID != "ai_request" {
		http.Error(w, "Context not available for this hook", http.StatusNotFound)
		return
	}

	ctx := HookContext{
		Trigger: "preview",
		State:   s.State,
		Server:  s,
	}

	context := GatherContext(ctx, s.DimaistClient)
	writeJSON(w, map[string]string{"context": context})
}

// HookResultsHandler handles GET /api/hook-results
func (s *Server) HookResultsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	results, err := s.DBManager.GetHookResults(20)
	if err != nil {
		log.Error("Failed to get hook results", "error", err)
		http.Error(w, "Failed to get results", http.StatusInternalServerError)
		return
	}

	if results == nil {
		results = []db.HookResultRecord{}
	}

	writeJSON(w, results)
}

// HookResultByIDHandler handles POST /api/hook-results/{id}/read
func (s *Server) HookResultByIDHandler(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/hook-results/")
	parts := strings.SplitN(path, "/", 2)

	if len(parts) < 2 || parts[1] != "read" || r.Method != http.MethodPost {
		http.Error(w, "Not found", http.StatusNotFound)
		return
	}

	recordID := parts[0]
	if err := s.DBManager.MarkHookResultRead(recordID); err != nil {
		log.Error("Failed to mark result as read", "id", recordID, "error", err)
		http.Error(w, "Failed to mark as read", http.StatusInternalServerError)
		return
	}

	writeJSON(w, map[string]string{"status": "ok"})
}
