package coach

import (
	"coach/internal/db"
	"coach/internal/stats"
	"encoding/json"
	"sync"
	"time"

	"github.com/charmbracelet/log"

	"github.com/gorilla/websocket"
	"slices"
)

type FocusRequest struct {
	StartTime time.Time
	EndTime   time.Time
}

type State struct {
	LastChange time.Time

	clients           map[*websocket.Conn]bool
	focusRequests     []FocusRequest
	hooks             []Hook
	mu                sync.Mutex
	stats             *stats.Stats
	expiryTimer       *time.Timer
	dbManager         *db.Manager
	agentReleaseUntil *time.Time
	agentLockTimer    *time.Timer
	overrides         overrideLedger
}

type FocusInfo struct {
	Type                 string        `json:"type"`
	Focusing             bool          `json:"focusing"`
	SinceLastChange      time.Duration `json:"since_last_change"`
	FocusTimeLeft        time.Duration `json:"focus_time_left"`
	NumFocuses           int           `json:"num_focuses"`
	AgentReleaseTimeLeft *int64        `json:"agent_release_time_left"`

	// Cooldown state rides the focus frame rather than a second endpoint: the
	// phone already collects this frame, and the fields are flattened because the
	// client folds the frame into a flat map and would stringify a nested object.
	OverridesToday      int `json:"overrides_today"`
	CooldownSeconds     int `json:"override_cooldown_seconds"`
	CooldownRemaining   int `json:"override_cooldown_remaining"`
	NextCooldownSeconds int `json:"override_next_cooldown_seconds"`
	ShortUnblocksLeft   int `json:"short_unblocks_left"`
}

// AgentLockInfo is the public shape of agent-lock state. TimeLeftSeconds is nil when locked.
type AgentLockInfo struct {
	TimeLeftSeconds *int64 `json:"time_left_seconds"`
}

func (s *State) GetCurrentFocusInfo() FocusInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	focusTimeLeft := s.getTimeLeftLocked()
	sinceLastChange := time.Since(s.LastChange)
	numFocuses := 0
	if s.stats != nil {
		numFocuses = s.stats.GetTodayFocusCount()
	}
	cooldown := computeCooldown(time.Now(), s.overrides.onDay(time.Now()))
	return FocusInfo{
		Type:                 "focusing",
		Focusing:             s.getTimeLeftLocked() > 0,
		SinceLastChange:      sinceLastChange / time.Second,
		FocusTimeLeft:        focusTimeLeft / time.Second,
		NumFocuses:           numFocuses,
		AgentReleaseTimeLeft: s.agentReleaseTimeLeftLocked(),
		OverridesToday:       cooldown.OverridesToday,
		CooldownSeconds:      cooldown.CooldownSeconds,
		CooldownRemaining:    cooldown.RemainingSeconds,
		NextCooldownSeconds:  cooldown.NextCooldownSeconds,
		ShortUnblocksLeft:    cooldown.ShortUnblocksLeft,
	}
}

// GetAgentLockInfo returns the current agent-lock state.
func (s *State) GetAgentLockInfo() AgentLockInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	return AgentLockInfo{TimeLeftSeconds: s.agentReleaseTimeLeftLocked()}
}

// ReleaseAgentLock unlocks the agent lock for d. If a release is already active and ends
// after now+d, this is a no-op (we never shorten an existing release here).
func (s *State) ReleaseAgentLock(d time.Duration) {
	if d <= 0 {
		return
	}
	s.mu.Lock()
	candidate := time.Now().Add(d)
	changed := false
	if s.agentReleaseUntil == nil || s.agentReleaseUntil.Before(candidate) {
		until := candidate
		s.agentReleaseUntil = &until
		s.scheduleAgentLockTimerLocked()
		s.persistAgentReleaseUntilLocked()
		changed = true
	}
	s.mu.Unlock()

	if changed {
		log.Info("Agent lock released", "until", candidate)
		go s.NotifyAllClients(s.GetCurrentFocusInfo())
	}
}

// OverrideCooldown prices the next override without changing anything.
func (s *State) OverrideCooldown() OverrideCooldown {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	return computeCooldown(now, s.overrides.onDay(now))
}

// TakeOverride releases the lock for OverrideSeconds and bills the day's ledger.
// It refuses while a cooldown runs, returning the live cooldown either way so the
// caller can tell the client what it is waiting for. The bool reports whether the
// lock actually came off.
func (s *State) TakeOverride() (OverrideCooldown, bool) {
	now := time.Now()

	s.mu.Lock()
	ledger := s.overrides.onDay(now)
	if cooldown := computeCooldown(now, ledger); cooldown.Active() {
		s.overrides = ledger
		s.mu.Unlock()
		log.Info("Override refused, cooldown running", "remaining", cooldown.RemainingSeconds)
		return cooldown, false
	}

	ledger.overrides++
	ledger.lastOverrideEnd = now.Add(OverrideSeconds * time.Second)
	// A new override opens a new cooldown window, so the hatch allowance is fresh.
	ledger.shortUnblocksUsed = 0
	s.overrides = ledger
	s.mu.Unlock()

	// Called without the lock: ReleaseAgentLock takes it and broadcasts.
	s.ReleaseAgentLock(OverrideSeconds * time.Second)
	log.Info("Override taken", "today", ledger.overrides, "next_cooldown", ledger.overrides*CooldownStepSeconds)
	return s.OverrideCooldown(), true
}

// TakeShortUnblock releases the lock for ShortUnblockSeconds. It is only offered
// while a cooldown runs and only ShortUnblockLimit times per window; it is free,
// so it never lengthens the cooldown it is softening.
func (s *State) TakeShortUnblock() (OverrideCooldown, bool) {
	now := time.Now()

	s.mu.Lock()
	ledger := s.overrides.onDay(now)
	cooldown := computeCooldown(now, ledger)
	if !cooldown.Active() || cooldown.ShortUnblocksLeft == 0 {
		s.overrides = ledger
		s.mu.Unlock()
		return cooldown, false
	}
	ledger.shortUnblocksUsed++
	s.overrides = ledger
	s.mu.Unlock()

	s.ReleaseAgentLock(ShortUnblockSeconds * time.Second)
	log.Info("Short unblock taken", "used", ledger.shortUnblocksUsed, "limit", ShortUnblockLimit)
	return s.OverrideCooldown(), true
}

// RecordOverride bills the ledger for an override released through the other
// road — the HTTP path the agent and future callers use. It neither refuses nor
// releases, because that already happened; it exists so both roads to a
// kind='override' row tell the same story about the day, instead of diverging
// until the next restart re-reads the journal.
func (s *State) RecordOverride(d time.Duration) {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	ledger := s.overrides.onDay(now)
	ledger.overrides++
	if end := now.Add(d); end.After(ledger.lastOverrideEnd) {
		ledger.lastOverrideEnd = end
	}
	ledger.shortUnblocksUsed = 0
	s.overrides = ledger
	log.Info("Override recorded from HTTP path", "today", ledger.overrides)
}

// RestoreOverrideLedger seeds today's counters from the journal on startup, so a
// restart doesn't reset the escalation or hand back a free override. A
// lastOverrideEnd of nil means no override was taken today.
func (s *State) RestoreOverrideLedger(overridesToday int, lastOverrideEnd *time.Time, shortUnblocksUsed int) {
	if overridesToday <= 0 {
		return
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.overrides = overrideLedger{
		day:               db.LocalDayStart(now),
		overrides:         overridesToday,
		shortUnblocksUsed: shortUnblocksUsed,
	}
	if lastOverrideEnd != nil {
		s.overrides.lastOverrideEnd = *lastOverrideEnd
	}
	log.Info("Restored override ledger",
		"overrides", overridesToday,
		"short_unblocks_used", shortUnblocksUsed,
		"cooldown_remaining", computeCooldown(now, s.overrides).RemainingSeconds)
}

// EngageAgentLock cancels any active release window.
func (s *State) EngageAgentLock() {
	s.mu.Lock()
	changed := s.agentReleaseUntil != nil
	s.agentReleaseUntil = nil
	if s.agentLockTimer != nil {
		s.agentLockTimer.Stop()
		s.agentLockTimer = nil
	}
	if changed {
		s.persistAgentReleaseUntilLocked()
	}
	s.mu.Unlock()

	if changed {
		log.Info("Agent lock engaged")
		go s.NotifyAllClients(s.GetCurrentFocusInfo())
	}
}

// RestoreAgentLock seeds agentReleaseUntil from persisted state on startup and schedules
// the snap-back timer for the remainder. Past values are ignored (lock stays engaged).
func (s *State) RestoreAgentLock(t *time.Time) {
	if t == nil || !time.Now().Before(*t) {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	until := *t
	s.agentReleaseUntil = &until
	s.scheduleAgentLockTimerLocked()
	log.Info("Restored agent lock release", "until", until)
}

// isAgentLockedLocked must be called with s.mu held.
func (s *State) isAgentLockedLocked() bool {
	if s.agentReleaseUntil == nil {
		return true
	}
	return !time.Now().Before(*s.agentReleaseUntil)
}

// agentReleaseTimeLeftLocked returns seconds until release expiry, or nil if locked.
// Must be called with s.mu held.
func (s *State) agentReleaseTimeLeftLocked() *int64 {
	if s.isAgentLockedLocked() {
		return nil
	}
	secs := int64(time.Until(*s.agentReleaseUntil) / time.Second)
	if secs < 0 {
		secs = 0
	}
	return &secs
}

// scheduleAgentLockTimerLocked replaces the snap-back timer to fire at agentReleaseUntil.
// If the value is in the past, no timer is scheduled. Must be called with s.mu held.
func (s *State) scheduleAgentLockTimerLocked() {
	if s.agentLockTimer != nil {
		s.agentLockTimer.Stop()
		s.agentLockTimer = nil
	}
	if s.agentReleaseUntil == nil {
		return
	}
	d := time.Until(*s.agentReleaseUntil)
	if d <= 0 {
		return
	}
	s.agentLockTimer = time.AfterFunc(d, s.onAgentLockTimerFire)
}

// onAgentLockTimerFire is the snap-back callback. It double-checks the time so that a
// race with a longer ReleaseAgentLock call doesn't wipe a freshly extended release.
func (s *State) onAgentLockTimerFire() {
	s.mu.Lock()
	expired := s.agentReleaseUntil != nil && !time.Now().Before(*s.agentReleaseUntil)
	if expired {
		s.agentReleaseUntil = nil
		s.agentLockTimer = nil
		s.persistAgentReleaseUntilLocked()
	}
	s.mu.Unlock()

	if expired {
		log.Info("Agent lock release expired")
		go s.NotifyAllClients(s.GetCurrentFocusInfo())
	}
}

// persistAgentReleaseUntilLocked writes the current value to the DB. Best-effort, async.
// Must be called with s.mu held (only reads s.agentReleaseUntil).
func (s *State) persistAgentReleaseUntilLocked() {
	if s.dbManager == nil {
		return
	}
	var t *time.Time
	if s.agentReleaseUntil != nil {
		copy := *s.agentReleaseUntil
		t = &copy
	}
	go func() {
		if err := s.dbManager.SetAgentReleaseUntil(t); err != nil {
			log.Error("Failed to persist agent lock state", "error", err)
		}
	}()
}

// AddHook registers a new hook function to be called when focus state changes
func (s *State) AddHook(hook Hook) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.hooks = append(s.hooks, hook)
}

// RestoreFocus restores an active focus session from DB on startup.
// Unlike SetFocusing, it does not trigger hooks or bump stats (those were already recorded).
func (s *State) RestoreFocus(remaining time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	s.focusRequests = append(s.focusRequests, FocusRequest{
		StartTime: now,
		EndTime:   now.Add(remaining),
	})
	s.LastChange = now
	s.scheduleExpiryTimer()

	log.Info("Restored focus session from database", "remaining", remaining)
}

func (s *State) SetFocusing(duration time.Duration) {
	s.mu.Lock()

	// Only update LastChange if we're starting a new focus session
	// (not already focusing)
	if s.getTimeLeftLocked() <= 0 {
		s.LastChange = time.Now()
	}

	// Find the latest EndTime from existing focus requests
	now := time.Now()
	latestEndTime := now
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}

	// Add new focus period starting from the latest end time
	s.focusRequests = append(s.focusRequests, FocusRequest{
		StartTime: latestEndTime,
		EndTime:   latestEndTime.Add(duration),
	})

	if s.stats != nil {
		s.stats.BumpTodaysFocusCount()
	}

	// Schedule expiry timer while still holding the lock
	s.scheduleExpiryTimer()

	// Get a copy of hooks to execute outside the lock
	hooks := make([]Hook, len(s.hooks))
	copy(hooks, s.hooks)
	s.mu.Unlock()

	// Execute hooks outside the lock to prevent deadlocks
	for _, hook := range hooks {
		hook(s)
	}
}

// scheduleExpiryTimer schedules a single timer for when focus ends. Must be called with mutex held.
func (s *State) scheduleExpiryTimer() {
	// Cancel existing timer if any
	if s.expiryTimer != nil {
		s.expiryTimer.Stop()
		s.expiryTimer = nil
	}

	// Find the latest end time
	timeLeft := s.getTimeLeftLocked()
	if timeLeft <= 0 {
		return
	}

	s.expiryTimer = time.AfterFunc(timeLeft, func() {
		s.mu.Lock()
		// Remove all expired focus requests
		now := time.Now()
		for i := 0; i < len(s.focusRequests); i++ {
			if s.focusRequests[i].EndTime.Before(now) {
				s.focusRequests = slices.Delete(s.focusRequests, i, i+1)
				i--
			}
		}

		// Check if all focus periods have ended
		if len(s.focusRequests) == 0 {
			s.LastChange = now
			s.expiryTimer = nil
			s.mu.Unlock()

			log.Info("All focus periods expired")
			message := s.GetCurrentFocusInfo()
			go s.NotifyAllClients(message)
		} else {
			// Reschedule for remaining focus periods
			s.scheduleExpiryTimer()
			s.mu.Unlock()
		}
	})
}

// clearFocus cancels all focus requests and the expiry timer
func (s *State) clearFocus() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.expiryTimer != nil {
		s.expiryTimer.Stop()
		s.expiryTimer = nil
	}
	s.focusRequests = nil
	s.LastChange = time.Now()
}

func (s *State) HandleFocusChange(focusing bool, durationSeconds int) {
	if focusing {
		s.SetFocusing(time.Duration(durationSeconds) * time.Second)
	} else {
		s.clearFocus()
	}

	message := s.GetCurrentFocusInfo()
	go s.NotifyAllClients(message)
}

// getTimeLeftLocked calculates remaining focus time. Must be called with mutex held.
func (s *State) getTimeLeftLocked() time.Duration {
	now := time.Now()
	latestEndTime := now
	for _, req := range s.focusRequests {
		if req.EndTime.After(latestEndTime) {
			latestEndTime = req.EndTime
		}
	}
	return latestEndTime.Sub(now)
}

func (s *State) AddClient(client *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.clients == nil {
		s.clients = make(map[*websocket.Conn]bool)
	}
	s.clients[client] = true
}

func (s *State) RemoveClient(client *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.clients, client)
}

func (s *State) NotifySingleClient(client *websocket.Conn, message any) error {
	jsonMessage, err := json.Marshal(message)
	if err != nil {
		return err
	}

	err = client.WriteMessage(websocket.TextMessage, jsonMessage)
	log.Info("Notifying", "msg", string(jsonMessage), "to", client.RemoteAddr())
	if err != nil {
		log.Error("Error sending message to client", "err", err)
		client.Close()
		return err
	}
	return nil
}

func (s *State) NotifyAllClients(message any) {
	s.mu.Lock()
	log.Info("Notifying all clients", "count", len(s.clients))
	// Copy clients to avoid holding lock during I/O
	clients := make([]*websocket.Conn, 0, len(s.clients))
	for client := range s.clients {
		clients = append(clients, client)
	}
	s.mu.Unlock()

	// Notify clients without holding lock
	var failedClients []*websocket.Conn
	for _, client := range clients {
		if err := s.NotifySingleClient(client, message); err != nil {
			failedClients = append(failedClients, client)
		}
	}

	// Remove failed clients
	if len(failedClients) > 0 {
		s.mu.Lock()
		for _, client := range failedClients {
			delete(s.clients, client)
		}
		s.mu.Unlock()
	}
}
