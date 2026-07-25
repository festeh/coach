package coach

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"coach/internal/stats"

	"github.com/charmbracelet/log"
	"github.com/gorilla/websocket"
)

// writeJSON marshals data to JSON and writes it to the response with proper headers
func writeJSON(w http.ResponseWriter, data any) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		log.Error("Error marshaling JSON", "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return err
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(jsonData)
	return nil
}

// @Summary Health check endpoint
// @Description Returns the health status of the API
// @Tags health
// @Produce plain
// @Success 200 {string} string "Healthy"
// @Router /health [get]
func (s *Server) HealthHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.DBManager == nil {
		http.Error(w, "Database unavailable", http.StatusServiceUnavailable)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.DBManager.Ping(ctx); err != nil {
		log.Error("Health check failed", "err", err)
		http.Error(w, "Database unavailable", http.StatusServiceUnavailable)
		return
	}
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
		writeJSON(w, s.State.GetCurrentFocusInfo())
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
	writeJSON(w, s.State.GetCurrentFocusInfo())
}

// @Summary Get or release/engage the agent lock
// @Description GET returns current agent-lock state. POST /agent-lock/release with form
// @Description duration=N (seconds) releases the lock for N seconds (extends if longer
// @Description than current release). POST /agent-lock/engage cancels any active release.
// @Tags agent-lock
// @Produce json
// @Success 200 {object} AgentLockInfo
// @Failure 400 {string} string "Bad request"
// @Failure 405 {string} string "Method not allowed"
// @Router /agent-lock [get]
// @Router /agent-lock/release [post]
// @Router /agent-lock/engage [post]
func (s *Server) AgentLockHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /agent-lock", "method", r.Method, "path", r.URL.Path)

	switch r.URL.Path {
	case "/agent-lock":
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		writeJSON(w, s.State.GetAgentLockInfo())

	case "/agent-lock/release":
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "Failed to parse form", http.StatusBadRequest)
			return
		}
		duration, err := strconv.Atoi(r.FormValue("duration"))
		if err != nil || duration <= 0 {
			http.Error(w, "duration must be a positive integer (seconds)", http.StatusBadRequest)
			return
		}
		s.State.ReleaseAgentLock(time.Duration(duration) * time.Second)

		// Journal the decision. The override flag lives only on the wire; the
		// stored kind carries it.
		kind := "grant"
		if r.FormValue("is_override") == "true" {
			kind = "override"
			// Counts toward the day's escalation like a WebSocket override would.
			// This road does not enforce the cooldown: it belongs to the agent,
			// whose authority over the lock is not the user's budget.
			s.State.RecordOverride(time.Duration(duration) * time.Second)
		}
		s.logLockDecision(kind, r.FormValue("user_message"), r.FormValue("agent_message"), duration)

		writeJSON(w, s.State.GetAgentLockInfo())

	case "/agent-lock/engage":
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.State.EngageAgentLock()
		writeJSON(w, s.State.GetAgentLockInfo())

	case "/agent-lock/state":
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.writeLockState(w)

	default:
		http.NotFound(w, r)
	}
}

// logLockDecision writes a decision row, best-effort and asynchronous. A failure
// (or a missing DB in tests) loses the journal row, never the lock action.
func (s *Server) logLockDecision(kind, userMessage, agentMessage string, durationSeconds int) {
	if s.DBManager == nil {
		return
	}
	go func() {
		if err := s.DBManager.InsertLockDecision(kind, "agent", userMessage, agentMessage, durationSeconds); err != nil {
			log.Error("Failed to journal lock decision", "kind", kind, "error", err)
		}
	}()
}

// refuseUnblock tells one client its unblock was declined and what it is waiting
// for. The lock state has not changed, so there is nothing to broadcast.
func (s *Server) refuseUnblock(conn *websocket.Conn, request string, cooldown OverrideCooldown) {
	log.Info("Unblock refused",
		"request", request,
		"cooldown_remaining", cooldown.RemainingSeconds,
		"short_unblocks_left", cooldown.ShortUnblocksLeft)

	refusal := struct {
		Type              string `json:"type"`
		Request           string `json:"request"`
		OverridesToday    int    `json:"overrides_today"`
		CooldownSeconds   int    `json:"override_cooldown_seconds"`
		CooldownRemaining int    `json:"override_cooldown_remaining"`
		ShortUnblocksLeft int    `json:"short_unblocks_left"`
	}{
		Type:              "unblock_refused",
		Request:           request,
		OverridesToday:    cooldown.OverridesToday,
		CooldownSeconds:   cooldown.CooldownSeconds,
		CooldownRemaining: cooldown.RemainingSeconds,
		ShortUnblocksLeft: cooldown.ShortUnblocksLeft,
	}
	if err := s.State.NotifySingleClient(conn, refusal); err != nil {
		log.Error("Failed to send unblock refusal", "err", err)
	}
}

// logTemptation records one blocked attempt, best-effort and asynchronous. A
// failure (or a missing DB in tests) loses the row, never anything else.
func (s *Server) logTemptation(source, target string) {
	if s.DBManager == nil {
		return
	}
	go func() {
		if err := s.DBManager.InsertTemptation(source, target); err != nil {
			log.Error("Failed to record temptation", "source", source, "error", err)
		}
	}()
}

// writeLockState answers GET /agent-lock/state from today's journal: total
// released seconds, override count, and the most recent decisions.
func (s *Server) writeLockState(w http.ResponseWriter) {
	type recentEntry struct {
		At              string `json:"at"`
		Kind            string `json:"kind"`
		UserMessage     string `json:"user_message"`
		AgentMessage    string `json:"agent_message"`
		DurationSeconds int    `json:"duration_seconds"`
	}
	out := struct {
		ReleasedSecondsToday int              `json:"released_seconds_today"`
		OverrideCountToday   int              `json:"override_count_today"`
		TemptationCountToday int              `json:"temptation_count_today"`
		Cooldown             OverrideCooldown `json:"cooldown"`
		Recent               []recentEntry    `json:"recent"`
	}{Cooldown: s.State.OverrideCooldown(), Recent: []recentEntry{}}

	if s.DBManager == nil {
		writeJSON(w, out)
		return
	}

	// The temptation tally is supporting context; a failure here logs and
	// falls back to 0 rather than sinking the whole lock-state read.
	if count, err := s.DBManager.CountTodayTemptations(); err != nil {
		log.Error("Failed to count temptations", "err", err)
	} else {
		out.TemptationCountToday = count
	}

	decisions, err := s.DBManager.GetTodayLockDecisions()
	if err != nil {
		log.Error("Failed to read lock decisions", "err", err)
		http.Error(w, "Failed to read lock decisions", http.StatusInternalServerError)
		return
	}

	for _, d := range decisions {
		if d.Kind == "grant" || d.Kind == "override" {
			out.ReleasedSecondsToday += d.DurationSeconds
		}
		if d.Kind == "override" {
			out.OverrideCountToday++
		}
	}

	// Last 5, newest first (decisions arrive oldest-first).
	start := len(decisions) - 5
	if start < 0 {
		start = 0
	}
	for i := len(decisions) - 1; i >= start; i-- {
		d := decisions[i]
		out.Recent = append(out.Recent, recentEntry{
			At:              d.Created,
			Kind:            d.Kind,
			UserMessage:     d.UserMessage,
			AgentMessage:    d.AgentMessage,
			DurationSeconds: d.DurationSeconds,
		})
	}

	writeJSON(w, out)
}

// @Summary Record a lock denial
// @Description The coach agent posts here when it refuses a release request.
// @Description Grants, overrides, and engages go through the lock endpoints
// @Description that actually change state; this endpoint is denials only.
// @Tags agent-lock
// @Accept json
// @Produce json
// @Success 200 {object} map[string]bool
// @Failure 400 {string} string "Bad request"
// @Failure 405 {string} string "Method not allowed"
// @Router /lock-decisions [post]
func (s *Server) LockDecisionsHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /lock-decisions", "method", r.Method)

	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var body struct {
		Kind         string `json:"kind"`
		UserMessage  string `json:"user_message"`
		AgentMessage string `json:"agent_message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "Invalid JSON body", http.StatusBadRequest)
		return
	}
	// Kind is implied: this endpoint only records denials. A caller setting
	// kind is confused about what this endpoint does.
	if body.Kind != "" {
		http.Error(w, "kind is not accepted here; this endpoint records denials only", http.StatusBadRequest)
		return
	}

	s.logLockDecision("denial", body.UserMessage, body.AgentMessage, 0)
	writeJSON(w, map[string]bool{"ok": true})
}

// @Summary Get unlock stats
// @Description Returns unlocks and unlocked seconds per day for the last N days
// @Tags agent-lock
// @Produce json
// @Param days query int false "Days to report, oldest first (default 7)"
// @Success 200 {array} db.UnlockDay
// @Router /lock-decisions/stats [get]
func (s *Server) LockDecisionStatsHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /lock-decisions/stats", "method", r.Method)

	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	days := 7
	if daysStr := r.URL.Query().Get("days"); daysStr != "" {
		parsed, err := strconv.Atoi(daysStr)
		if err != nil || parsed < 1 {
			parsed = 7
		}
		// A phone header reads a week; a month is plenty of rope for anything else.
		if parsed > 31 {
			parsed = 31
		}
		days = parsed
	}

	if s.DBManager == nil {
		http.Error(w, "Journal unavailable", http.StatusServiceUnavailable)
		return
	}

	stats, err := s.DBManager.GetUnlockStats(days)
	if err != nil {
		log.Error("Failed to get unlock stats", "err", err)
		http.Error(w, "Failed to get unlock stats", http.StatusInternalServerError)
		return
	}

	writeJSON(w, stats)
}

// @Summary Get focus history
// @Description Returns focus records for the last N days
// @Tags focus
// @Produce json
// @Param days query int false "Number of days to look back (default 7)"
// @Success 200 {array} db.FocusRecord "Array of focus records"
// @Failure 500 {string} string "Internal server error"
// @Router /history [get]
func (s *Server) HistoryHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /history", "method", r.Method)

	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Get days parameter with default of 7
	daysStr := r.URL.Query().Get("days")
	days := 7
	if daysStr != "" {
		var err error
		days, err = strconv.Atoi(daysStr)
		if err != nil || days < 1 {
			days = 7
		}
	}

	records, err := s.DBManager.GetFocusHistory(days)
	if err != nil {
		log.Error("Failed to get focus history", "err", err)
		http.Error(w, "Failed to get focus history", http.StatusInternalServerError)
		return
	}

	writeJSON(w, records)
}

// @Summary Get attention intervals
// @Description Returns attention intervals overlapping the [from, to) window.
// @Description Defaults to the last 24 hours.
// @Tags attention
// @Produce json
// @Param from query string false "RFC3339 start of window (default: 24h ago)"
// @Param to query string false "RFC3339 end of window (default: now)"
// @Success 200 {array} db.AttentionInterval "Array of attention intervals"
// @Failure 400 {string} string "Bad request"
// @Failure 500 {string} string "Internal server error"
// @Router /attention [get]
func (s *Server) AttentionHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /attention", "method", r.Method)

	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	now := time.Now()
	from, to := now.Add(-24*time.Hour), now
	if v := r.URL.Query().Get("from"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			http.Error(w, "from must be RFC3339", http.StatusBadRequest)
			return
		}
		from = t
	}
	if v := r.URL.Query().Get("to"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			http.Error(w, "to must be RFC3339", http.StatusBadRequest)
			return
		}
		to = t
	}

	intervals, err := s.DBManager.GetAttentionIntervals(from, to)
	if err != nil {
		log.Error("Failed to get attention intervals", "err", err)
		http.Error(w, "Failed to get attention intervals", http.StatusInternalServerError)
		return
	}

	writeJSON(w, intervals)
}

// @Summary Today's attention summary
// @Description What has the user's attention right now, and where today's site time went
// @Tags attention
// @Produce json
// @Success 200 {object} stats.AttentionSummary
// @Failure 500 {string} string "Internal server error"
// @Router /attention/summary [get]
func (s *Server) AttentionSummaryHandler(w http.ResponseWriter, r *http.Request) {
	log.Info("Called /attention/summary", "method", r.Method)

	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	now := time.Now()
	dayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())

	if s.DBManager == nil {
		writeJSON(w, stats.SummarizeAttention(nil, dayStart, now))
		return
	}

	intervals, err := s.DBManager.GetAttentionIntervals(dayStart, now)
	if err != nil {
		log.Error("Failed to get attention intervals", "err", err)
		http.Error(w, "Failed to get attention intervals", http.StatusInternalServerError)
		return
	}

	writeJSON(w, stats.SummarizeAttention(intervals, dayStart, now))
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
			// Clients come and go: extensions close normally, browsers send
			// going-away on shutdown, phones drop without a close frame.
			if websocket.IsCloseError(err,
				websocket.CloseNormalClosure,
				websocket.CloseGoingAway,
				websocket.CloseNoStatusReceived,
				websocket.CloseAbnormalClosure) {
				log.Info("Client disconnected", "reason", err)
			} else {
				log.Error("Error reading message", "err", err)
			}
			return
		}

		var message struct {
			Type     string `json:"type"`
			Duration int    `json:"duration,omitempty"`
			State    string `json:"state,omitempty"`
			Site     string `json:"site,omitempty"`
			Source   string `json:"source,omitempty"`
			Target   string `json:"target,omitempty"`
			Message  string `json:"message,omitempty"`
		}

		if err := json.Unmarshal(buf, &message); err != nil {
			continue
		}
		log.Debug("Received message", "type", message.Type)

		switch message.Type {
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
		case "attention":
			switch message.State {
			case "site", "idle", "away":
				s.AttentionTracker.Handle(message.State, message.Site)
			default:
				log.Warn("Invalid attention state", "state", message.State)
			}
		case "temptation":
			// source is an open label set by the client (chromium, firefox,
			// android, …); a new client must not need a server change.
			if message.Source == "" || message.Target == "" {
				log.Warn("Invalid temptation", "source", message.Source, "target", message.Target)
			} else {
				s.logTemptation(message.Source, message.Target)
			}
		case "override":
			// A fixed 15 minutes, priced at a written reason and, after the first
			// of the day, at a wait that grows by 30s each time. The release
			// broadcast tells every client; the journal tells the judge.
			if message.Message == "" {
				log.Warn("Override without a reason ignored")
			} else if cooldown, taken := s.State.TakeOverride(); !taken {
				s.refuseUnblock(conn, "override", cooldown)
			} else {
				s.logLockDecision("override", message.Message, "", OverrideSeconds)
			}

		case "short_unblock":
			// The way out of a cooldown: 30 seconds, no reason asked, capped per
			// window. Free by design, so it is not journalled as an override and
			// does not lengthen the cooldown it interrupts.
			if cooldown, taken := s.State.TakeShortUnblock(); !taken {
				s.refuseUnblock(conn, "short_unblock", cooldown)
			} else {
				s.logLockDecision("short_unblock", "", "", ShortUnblockSeconds)
			}
		case "ping":
			// "type" is what current clients match on; "response" is kept for
			// backwards compatibility with older clients.
			response := struct {
				Type     string `json:"type"`
				Response string `json:"response"`
			}{
				Type:     "pong",
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

func (s *Server) AdminHandler() http.Handler {
	// Serve embedded SPA files from admin/dist, stripping the /admin/ prefix
	fs := http.FileServerFS(s.AdminFS)
	return http.StripPrefix("/admin/", fs)
}
