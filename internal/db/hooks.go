package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/charmbracelet/log"
)

// HookConfigRecord represents a hook config stored in PocketBase
type HookConfigRecord struct {
	RecordID  string            `json:"id"`
	HookID    string            `json:"hook_id"`
	Enabled   bool              `json:"enabled"`
	Trigger   string            `json:"trigger"`
	FirstRun  string            `json:"first_run"`
	LastRun   string            `json:"last_run"`
	Frequency string            `json:"frequency"`
	Params    map[string]string `json:"params"`
}

// HookResultRecord represents a hook result stored in PocketBase
type HookResultRecord struct {
	ID      string `json:"id"`
	HookID  string `json:"hook_id"`
	Content string `json:"content"`
	Read    bool   `json:"read"`
	Created string `json:"created"`
}

// GetHookConfig loads a single hook config by hook_id from PocketBase
func (m *Manager) GetHookConfig(hookID string) (*HookConfigRecord, error) {
	configs, err := m.fetchHookConfigs(fmt.Sprintf("filter=(hook_id='%s')&perPage=1", hookID))
	if err != nil {
		return nil, err
	}
	if len(configs) == 0 {
		return nil, nil
	}
	return &configs[0], nil
}

// GetHookConfigs loads all hook configs from PocketBase
func (m *Manager) GetHookConfigs() ([]HookConfigRecord, error) {
	return m.fetchHookConfigs("perPage=100")
}

func (m *Manager) fetchHookConfigs(query string) ([]HookConfigRecord, error) {
	endpoint := fmt.Sprintf("%s/api/collections/hooks/records?%s", m.BaseURL, query)
	req, err := http.NewRequest("GET", endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := m.DoRequest(req)
	if err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		log.Warn("Failed to load hook configs", "status", resp.StatusCode, "body", string(body))
		return nil, nil
	}

	var result struct {
		Items []HookConfigRecord `json:"items"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	return result.Items, nil
}

// CreateHookConfig creates a new hook config record in PocketBase
func (m *Manager) CreateHookConfig(data map[string]any) (string, error) {
	return m.createRecord("hooks", data)
}

// UpdateHookConfig updates a hook config record in PocketBase
func (m *Manager) UpdateHookConfig(recordID string, data map[string]any) error {
	return m.updateRecord("hooks", recordID, data)
}

// AddHookResult stores a hook result in PocketBase and returns the record ID
func (m *Manager) AddHookResult(data map[string]any) (string, error) {
	return m.createRecord("hook_results", data)
}

// GetHookResults returns recent hook results
func (m *Manager) GetHookResults(limit int) ([]HookResultRecord, error) {
	endpoint := fmt.Sprintf("%s/api/collections/hook_results/records?perPage=%d", m.BaseURL, limit)
	req, err := http.NewRequest("GET", endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := m.DoRequest(req)
	if err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to load hook results: status %d, body: %s", resp.StatusCode, string(body))
	}

	var result struct {
		Items []HookResultRecord `json:"items"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	return result.Items, nil
}

// MarkHookResultRead marks a hook result as read
func (m *Manager) MarkHookResultRead(recordID string) error {
	return m.updateRecord("hook_results", recordID, map[string]any{"read": true})
}

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
