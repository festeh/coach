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
				{Name: "timestamp", Type: "date", Required: true},
				{Name: "duration", Type: "number", Required: true},
			},
			Indexes: []string{"CREATE UNIQUE INDEX `ts_index` ON `coach` (`timestamp`)"},
		},
		{
			Name: "agent_lock",
			Type: "base",
			Fields: append([]db.Field{
				{Name: "release_until", Type: "text", Required: false},
			}, db.TimestampFields()...),
		},
	}
}
