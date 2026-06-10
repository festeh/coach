package db

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Collection is the PocketBase wire format for a collection schema.
type Collection struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Fields  []Field  `json:"fields"`
	Indexes []string `json:"indexes,omitempty"`
}

// Field is the PocketBase wire format for a collection field.
type Field struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Required bool   `json:"required"`
	OnCreate bool   `json:"onCreate,omitempty"` // autodate: stamp when the record is created
	OnUpdate bool   `json:"onUpdate,omitempty"` // autodate: restamp when the record is updated
}

// TimestampFields returns the created/updated autodate fields. PocketBase
// 0.23+ no longer adds these to new collections implicitly, so any collection
// whose queries filter or sort on created must declare them.
func TimestampFields() []Field {
	return []Field{
		{Name: "created", Type: "autodate", OnCreate: true},
		{Name: "updated", Type: "autodate", OnCreate: true, OnUpdate: true},
	}
}

// CollectionExists returns true if a collection with the given name is registered in PB.
func (m *Manager) CollectionExists(name string) (bool, error) {
	req, err := http.NewRequest("GET", m.BaseURL+"/api/collections", nil)
	if err != nil {
		return false, err
	}

	resp, err := m.DoRequest(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, err
	}

	if resp.StatusCode != http.StatusOK {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return false, fmt.Errorf("failed to list collections (status %d): %s", resp.StatusCode, string(body))
		}
		return false, fmt.Errorf("failed to list collections: %s", errResp.Message)
	}

	var listResp struct {
		Items []struct {
			Name string `json:"name"`
		} `json:"items"`
	}
	if err := json.Unmarshal(body, &listResp); err != nil {
		return false, err
	}

	for _, c := range listResp.Items {
		if c.Name == name {
			return true, nil
		}
	}
	return false, nil
}

// CreateCollection registers a new collection in PB.
func (m *Manager) CreateCollection(c Collection) error {
	jsonData, err := json.Marshal(c)
	if err != nil {
		return err
	}

	req, err := http.NewRequest("POST", m.BaseURL+"/api/collections", bytes.NewBuffer(jsonData))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.DoRequest(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return fmt.Errorf("failed to create collection (status %d): %s", resp.StatusCode, string(body))
		}
		return fmt.Errorf("failed to create collection: %s", errResp.Message)
	}
	return nil
}

// EnsureCollection creates the collection only if it doesn't already exist. Idempotent.
func (m *Manager) EnsureCollection(c Collection) (created bool, err error) {
	exists, err := m.CollectionExists(c.Name)
	if err != nil {
		return false, err
	}
	if exists {
		return false, nil
	}
	return true, m.CreateCollection(c)
}
