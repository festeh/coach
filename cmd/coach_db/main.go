package main

import (
	"coach/internal/db"

	"github.com/charmbracelet/log"
)

func main() {
	manager, err := db.InitManager()
	if err != nil {
		log.Fatal("Failed to initialize database manager", "error", err)
	}
	log.Info("Authentication successful")

	for _, c := range collections() {
		ensure(manager, c)
	}
}

func ensure(manager *db.Manager, c db.Collection) {
	created, err := manager.EnsureCollection(c)
	if err != nil {
		log.Fatal("Failed to ensure collection", "name", c.Name, "error", err)
	}
	if created {
		log.Info("Created collection", "name", c.Name)
	} else {
		log.Info("Collection already exists", "name", c.Name)
	}
}

// collections is the canonical list of PB collections coach owns. Add new
// schemas here when they should be set up by `coach_db`. Collections that
// auto-migrate from coach itself (e.g. agent_lock via NewServer) can also
// be listed here so a fresh PB can be initialized without first running
// the server.
func collections() []db.Collection {
	return []db.Collection{
		{
			Name: "coach",
			Type: "base",
			Fields: []db.Field{
				{Name: "timestamp", Type: "date", Required: true, Options: map[string]any{"min": nil, "max": nil}},
				{Name: "duration", Type: "number", Required: true, Options: map[string]any{"min": 0, "max": nil}},
			},
			Indexes: []string{"CREATE UNIQUE INDEX `ts_index` ON `coach` (`timestamp`)"},
		},
		{
			Name: "hooks",
			Type: "base",
			Fields: []db.Field{
				{Name: "hook_id", Type: "text", Required: true},
				{Name: "enabled", Type: "bool", Required: false},
				{Name: "trigger", Type: "text", Required: false},
				{Name: "first_run", Type: "text", Required: false},
				{Name: "last_run", Type: "text", Required: false},
				{Name: "frequency", Type: "text", Required: false},
				{Name: "params", Type: "json", Required: false},
			},
			Indexes: []string{"CREATE UNIQUE INDEX `hook_id_index` ON `hooks` (`hook_id`)"},
		},
		{
			Name: "hook_results",
			Type: "base",
			Fields: []db.Field{
				{Name: "hook_id", Type: "text", Required: true},
				{Name: "content", Type: "text", Required: true},
				{Name: "read", Type: "bool", Required: false},
			},
		},
		{
			Name: "agent_lock",
			Type: "base",
			Fields: []db.Field{
				{Name: "release_until", Type: "text", Required: false},
			},
		},
	}
}
