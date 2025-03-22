package main

import (
	"bytes"
	"coach/internal/db"
	"encoding/json"
	"fmt"
	"github.com/charmbracelet/log"
	"io"
	"net/http"
)

// PocketBase API endpoints
const (
	collectionsEndpoint = "/api/collections"
)

// Collection schema for PocketBase
type Collection struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Fields  []Field  `json:"fields"`
	Indexes []string `json:"indexes"`
}

// Field represents a schema field in PocketBase
type Field struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Required bool   `json:"required"`
	Options  any    `json:"options,omitempty"`
}

// CollectionListResponse represents the response when listing collections
type CollectionListResponse struct {
	Page       int          `json:"page"`
	PerPage    int          `json:"perPage"`
	TotalItems int          `json:"totalItems"`
	Items      []Collection `json:"items"`
}

func main() {
	// Initialize the database manager
	manager, err := db.InitManager()
	if err != nil {
		log.Fatal("Failed to initialize database manager", "error", err)
	}
	log.Info("Authentication successful")

	// Check if collection exists
	exists, err := collectionExists(manager, "coach")
	if err != nil {
		log.Fatal("Failed to check if collection exists", "error", err)
	}

	if exists {
		log.Info("Collection 'coach' already exists")
		return
	}

	// Create the collection
	err = createCollection(manager)
	if err != nil {
		log.Fatal("Failed to create collection", "error", err)
	}
	log.Info("Collection 'coach' created successfully")
}

// collectionExists checks if a collection with the given name exists
func collectionExists(manager *db.Manager, name string) (bool, error) {
	req, err := http.NewRequest("GET", manager.BaseURL+collectionsEndpoint, nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("Authorization", manager.AuthToken)

	resp, err := manager.Client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, err
	}

	if resp.StatusCode != http.StatusOK {
		var errResp db.ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return false, fmt.Errorf("failed to list collections with status %d: %s", resp.StatusCode, string(body))
		}
		return false, fmt.Errorf("failed to list collections: %s", errResp.Message)
	}

	var listResp CollectionListResponse
	if err := json.Unmarshal(body, &listResp); err != nil {
		return false, err
	}

	for _, collection := range listResp.Items {
		if collection.Name == name {
			return true, nil
		}
	}

	return false, nil
}

// createCollection creates a new collection with the specified schema
func createCollection(manager *db.Manager) error {
	collection := Collection{
		Name: "coach",
		Type: "base",
		Fields: []Field{
			{
				Name:     "timestamp",
				Type:     "date",
				Required: true,
				Options: map[string]interface{}{
					"min": nil,
					"max": nil,
				},
			},
			{
				Name:     "duration",
				Type:     "number",
				Required: true,
				Options: map[string]interface{}{
					"min": 0,
					"max": nil,
				},
			},
		},
		Indexes: []string{"CREATE UNIQUE INDEX `ts_index` ON `coach` (`timestamp`)"},
	}

	jsonData, err := json.Marshal(collection)
	if err != nil {
		return err
	}

	req, err := http.NewRequest("POST", manager.BaseURL+collectionsEndpoint, bytes.NewBuffer(jsonData))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", manager.AuthToken)

	resp, err := manager.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		var errResp db.ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return fmt.Errorf("failed to create collection with status %d: %s", resp.StatusCode, string(body))
		}
		return fmt.Errorf("failed to create collection: %s", errResp.Message)
	}

	return nil
}
