package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// createRecord creates a record in a PocketBase collection and returns the record ID
func (m *Manager) createRecord(collection string, data map[string]any) (string, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return "", fmt.Errorf("failed to marshal data: %w", err)
	}

	endpoint := fmt.Sprintf("%s/api/collections/%s/records", m.BaseURL, collection)
	req, err := http.NewRequest("POST", endpoint, strings.NewReader(string(jsonData)))
	if err != nil {
		return "", fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.DoRequest(req)
	if err != nil {
		return "", fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("create failed with status %d: %s", resp.StatusCode, string(body))
	}

	var record struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(body, &record); err != nil {
		return "", fmt.Errorf("failed to parse response: %w", err)
	}

	return record.ID, nil
}

// updateRecord updates a record in a PocketBase collection
func (m *Manager) updateRecord(collection, recordID string, data map[string]any) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("failed to marshal data: %w", err)
	}

	endpoint := fmt.Sprintf("%s/api/collections/%s/records/%s", m.BaseURL, collection, recordID)
	req, err := http.NewRequest("PATCH", endpoint, strings.NewReader(string(jsonData)))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.DoRequest(req)
	if err != nil {
		return fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("update failed with status %d: %s", resp.StatusCode, string(body))
	}

	return nil
}
