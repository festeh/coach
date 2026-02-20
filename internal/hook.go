package coach

import (
	"fmt"
	"sync"
	"time"

	"github.com/charmbracelet/log"

	"coach/internal/db"
)

// Hook is a function that is called when focus state changes (legacy, used by DatabaseHook)
type Hook func(*State)

// DatabaseHook creates a hook that records focus state changes to the database
func DatabaseHook(manager *db.Manager) Hook {
	return func(s *State) {
		if len(s.focusRequests) == 0 {
			log.Error("No focus requests found")
			return
		}

		s.mu.Lock()
		// get latest focus request
		request := s.focusRequests[len(s.focusRequests)-1]
		s.mu.Unlock()

		duration := request.EndTime.Sub(request.StartTime)

		record := map[string]any{
			"timestamp": request.StartTime.Format(time.RFC3339),
			"duration":  int(duration.Seconds()),
		}

		go func() {
			if err := manager.AddRecord(record); err != nil {
				log.Error("Failed to add focus record to database", "error", err)
				return
			}

			log.Info("Focus record saved to database",
				"timestamp", record["timestamp"],
				"duration", duration.String())
		}()
	}
}

// ParamDef describes a configurable parameter the admin UI renders
type ParamDef struct {
	Key     string   `json:"key"`
	Name    string   `json:"name"`
	Type    string   `json:"type"` // "text", "textarea", "select"
	Default string   `json:"default"`
	Options []string `json:"options,omitempty"` // only for type "select"
}

// HookDef is a registered hook implementation
type HookDef struct {
	ID          string     `json:"id"`
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Params      []ParamDef `json:"params"`
	Run         func(ctx HookContext) error
}

// HookContext is passed to a hook's Run function
type HookContext struct {
	Trigger string
	State   *State
	Server  *Server
	Params  map[string]string
}

// HookResult is a result produced by a hook run
type HookResult struct {
	ID      string `json:"id"`
	HookID  string `json:"hook_id"`
	Content string `json:"content"`
	Read    bool   `json:"read"`
	Created string `json:"created"`
}

// HookRunner manages hook registration, configuration, and scheduling
type HookRunner struct {
	defs   map[string]*HookDef
	timers map[string]*time.Timer
	state  *State
	server *Server
	db     *db.Manager
	mu     sync.Mutex
}

func NewHookRunner(state *State, dbManager *db.Manager) *HookRunner {
	return &HookRunner{
		defs:   make(map[string]*HookDef),
		timers: make(map[string]*time.Timer),
		state:  state,
		db:     dbManager,
	}
}

// SetServer sets the server reference (called after server is created, to break circular dep)
func (r *HookRunner) SetServer(server *Server) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.server = server
}

// Register adds a hook definition to the registry
func (r *HookRunner) Register(def *HookDef) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.defs[def.ID] = def
}

// StartSchedulers loads configs from PocketBase and starts timers for all enabled scheduled hooks
func (r *HookRunner) StartSchedulers() {
	records, err := r.db.GetHookConfigs()
	if err != nil {
		log.Warn("Failed to load hook configs for scheduling", "error", err)
		return
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	for _, rec := range records {
		if rec.Enabled && rec.Trigger == "scheduled" {
			r.scheduleNextLocked(rec.HookID, &rec)
		}
	}
}

// GetDefs returns all registered hook definitions
func (r *HookRunner) GetDefs() []*HookDef {
	r.mu.Lock()
	defer r.mu.Unlock()

	defs := make([]*HookDef, 0, len(r.defs))
	for _, def := range r.defs {
		defs = append(defs, def)
	}
	return defs
}

// GetConfig returns the config for a hook from PocketBase, or nil if not configured
func (r *HookRunner) GetConfig(hookID string) *db.HookConfigRecord {
	rec, err := r.db.GetHookConfig(hookID)
	if err != nil {
		log.Error("Failed to get hook config", "hook_id", hookID, "error", err)
		return nil
	}
	return rec
}

// UpdateConfig saves a hook config to PocketBase and reschedules if needed
func (r *HookRunner) UpdateConfig(cfg db.HookConfigRecord) error {
	// Stop existing timer
	r.mu.Lock()
	if timer, ok := r.timers[cfg.HookID]; ok {
		timer.Stop()
		delete(r.timers, cfg.HookID)
	}
	r.mu.Unlock()

	// Check if record already exists in PocketBase
	existing, err := r.db.GetHookConfig(cfg.HookID)
	if err != nil {
		return fmt.Errorf("failed to check existing hook config: %w", err)
	}

	if existing != nil {
		cfg.RecordID = existing.RecordID
		if err := r.db.UpdateHookConfig(cfg.RecordID, hookConfigToMap(cfg)); err != nil {
			return fmt.Errorf("failed to update hook config: %w", err)
		}
	} else {
		recordID, err := r.db.CreateHookConfig(hookConfigToMap(cfg))
		if err != nil {
			return fmt.Errorf("failed to create hook config: %w", err)
		}
		cfg.RecordID = recordID
	}

	// Reschedule if enabled
	if cfg.Enabled && cfg.Trigger == "scheduled" {
		r.mu.Lock()
		r.scheduleNextLocked(cfg.HookID, &cfg)
		r.mu.Unlock()
	}

	return nil
}

// RunHook runs a hook immediately, skipping heuristics. Used by the trigger endpoint.
func (r *HookRunner) RunHook(hookID string) error {
	r.mu.Lock()
	def, ok := r.defs[hookID]
	server := r.server
	state := r.state
	r.mu.Unlock()

	if !ok {
		return fmt.Errorf("hook not found: %s", hookID)
	}

	params := r.resolveParams(hookID)

	ctx := HookContext{
		Trigger: "manual",
		State:   state,
		Server:  server,
		Params:  params,
	}

	return def.Run(ctx)
}

// resolveParams merges defaults with user overrides from PocketBase
func (r *HookRunner) resolveParams(hookID string) map[string]string {
	params := make(map[string]string)

	// Fill defaults
	r.mu.Lock()
	if def, ok := r.defs[hookID]; ok {
		for _, p := range def.Params {
			params[p.Key] = p.Default
		}
	}
	r.mu.Unlock()

	// Override with user config from DB
	cfg := r.GetConfig(hookID)
	if cfg != nil && cfg.Params != nil {
		for k, v := range cfg.Params {
			if v != "" {
				params[k] = v
			}
		}
	}

	return params
}

// scheduleNextLocked calculates and schedules the next fire time. Must hold mu.
func (r *HookRunner) scheduleNextLocked(hookID string, cfg *db.HookConfigRecord) {
	dur := r.timeUntilNextFire(cfg)
	if dur < 0 {
		log.Warn("No upcoming fire time for hook", "hook_id", hookID)
		return
	}

	log.Info("Scheduling hook", "hook_id", hookID, "next_fire_in", dur)

	r.timers[hookID] = time.AfterFunc(dur, func() {
		r.onTimerFire(hookID)
	})
}

// onTimerFire is called when a scheduled timer fires
func (r *HookRunner) onTimerFire(hookID string) {
	// Check heuristics
	if !r.state.HasClients() {
		log.Info("Skipping hook: no clients connected", "hook_id", hookID)
		r.reschedule(hookID)
		return
	}

	if r.state.IsFocusing() {
		log.Info("Skipping hook: currently focusing", "hook_id", hookID)
		r.reschedule(hookID)
		return
	}

	r.mu.Lock()
	def, ok := r.defs[hookID]
	server := r.server
	state := r.state
	r.mu.Unlock()

	if !ok {
		r.reschedule(hookID)
		return
	}

	params := r.resolveParams(hookID)

	ctx := HookContext{
		Trigger: "scheduled",
		State:   state,
		Server:  server,
		Params:  params,
	}

	log.Info("Running scheduled hook", "hook_id", hookID)
	if err := def.Run(ctx); err != nil {
		log.Error("Hook execution failed", "hook_id", hookID, "error", err)
	}

	r.reschedule(hookID)
}

func (r *HookRunner) reschedule(hookID string) {
	cfg := r.GetConfig(hookID)
	if cfg == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.scheduleNextLocked(hookID, cfg)
}

// timeUntilNextFire calculates the duration until the next fire time
func (r *HookRunner) timeUntilNextFire(cfg *db.HookConfigRecord) time.Duration {
	now := time.Now()

	firstRun, err := parseTimeOfDay(cfg.FirstRun)
	if err != nil {
		log.Error("Failed to parse first_run", "value", cfg.FirstRun, "error", err)
		return -1
	}

	lastRun, err := parseTimeOfDay(cfg.LastRun)
	if err != nil {
		log.Error("Failed to parse last_run", "value", cfg.LastRun, "error", err)
		return -1
	}

	freq, err := time.ParseDuration(cfg.Frequency)
	if err != nil {
		log.Error("Failed to parse frequency", "value", cfg.Frequency, "error", err)
		return -1
	}

	if freq <= 0 {
		log.Error("Frequency must be positive", "value", cfg.Frequency)
		return -1
	}

	// Build today's first and last run times
	todayFirst := time.Date(now.Year(), now.Month(), now.Day(), firstRun.hour, firstRun.minute, 0, 0, now.Location())
	todayLast := time.Date(now.Year(), now.Month(), now.Day(), lastRun.hour, lastRun.minute, 0, 0, now.Location())

	// Step through today's fire times to find the next one after now
	t := todayFirst
	for !t.After(todayLast) {
		if t.After(now) {
			return t.Sub(now)
		}
		t = t.Add(freq)
	}

	// All today's times have passed — schedule for tomorrow's first run
	tomorrowFirst := todayFirst.Add(24 * time.Hour)
	return tomorrowFirst.Sub(now)
}

type timeOfDay struct {
	hour   int
	minute int
}

func parseTimeOfDay(s string) (timeOfDay, error) {
	var h, m int
	_, err := fmt.Sscanf(s, "%d:%d", &h, &m)
	if err != nil {
		return timeOfDay{}, fmt.Errorf("invalid time format %q: %w", s, err)
	}
	if h < 0 || h > 23 || m < 0 || m > 59 {
		return timeOfDay{}, fmt.Errorf("invalid time %q", s)
	}
	return timeOfDay{hour: h, minute: m}, nil
}

func hookConfigToMap(cfg db.HookConfigRecord) map[string]any {
	return map[string]any{
		"hook_id":   cfg.HookID,
		"enabled":   cfg.Enabled,
		"trigger":   cfg.Trigger,
		"first_run": cfg.FirstRun,
		"last_run":  cfg.LastRun,
		"frequency": cfg.Frequency,
		"params":    cfg.Params,
	}
}
