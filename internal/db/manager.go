package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/joho/godotenv"
)

const (
	loginEndpoint = "/api/collections/_superusers/auth-with-password"
	envFile       = ".env"
)

// AuthResponse represents the authentication response from PocketBase
type AuthResponse struct {
	Token string `json:"token"`
	Admin struct {
		ID    string `json:"id"`
		Email string `json:"email"`
	} `json:"admin"`
}

// ErrorResponse represents an error response from PocketBase
type ErrorResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data"`
}

// FocusRecord represents a focus session record
type FocusRecord struct {
	Timestamp time.Time `json:"timestamp"`
	Duration  int       `json:"duration"`
}

// Manager handles database operations and authentication
type Manager struct {
	BaseURL   string
	AuthToken string
	Client    *http.Client
	email     string
	password  string
}

// InitManager initializes a new database manager
// It loads credentials from the .env file and authenticates with PocketBase
func InitManager() (*Manager, error) {
	// Load environment variables from .env file
	if err := godotenv.Load(envFile); err != nil {
		return nil, fmt.Errorf("failed to load .env file: %w", err)
	}

	// Get credentials from environment variables
	pbURL := os.Getenv("PB_URL")
	pbEmail := os.Getenv("PB_EMAIL")
	pbPassword := os.Getenv("PB_PASSWORD")

	// Validate credentials
	if pbURL == "" || pbEmail == "" || pbPassword == "" {
		return nil, fmt.Errorf("missing required environment variables: PB_URL, PB_EMAIL, PB_PASSWORD")
	}

	// Ensure URL has proper format
	baseURL := pbURL
	if !strings.HasPrefix(baseURL, "http") {
		baseURL = "http://" + baseURL
	}
	if strings.HasSuffix(baseURL, "/") {
		baseURL = baseURL[:len(baseURL)-1]
	}

	// Create manager
	manager := &Manager{
		BaseURL:  baseURL,
		Client:   &http.Client{Timeout: 10 * time.Second},
		email:    pbEmail,
		password: pbPassword,
	}

	// Authenticate
	token, err := manager.authenticate()
	if err != nil {
		return nil, fmt.Errorf("authentication failed: %w", err)
	}

	manager.AuthToken = token
	log.Info("Database manager initialized successfully")
	return manager, nil
}

func (m *Manager) GetTodayFocusCount() (int, error) {
	log.Info("Getting today's focus count")
	today := time.Now().Format("2006-01-02")

	baseEndpoint := fmt.Sprintf("%s/api/collections/coach/records", m.BaseURL)
	u, err := url.Parse(baseEndpoint)
	if err != nil {
		return 0, fmt.Errorf("failed to parse URL: %w", err)
	}

	q := u.Query()
	filter := fmt.Sprintf("timestamp ~ '%s'", today)
	q.Set("filter", filter)
	u.RawQuery = q.Encode()

	fullURL := u.String()

	req, err := http.NewRequest("GET", fullURL, nil)
	if err != nil {
		return 0, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Authorization", m.AuthToken)

	resp, err := m.Client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return 0, fmt.Errorf("request failed with status %d: %s", resp.StatusCode, string(body))
		}
		return 0, fmt.Errorf("request failed: %s", errResp.Message)
	}

	// Parse the response to count today's focus sessions
	var result struct {
		Items []struct {
			ID        string `json:"id"`
			Timestamp string `json:"timestamp"`
			Duration  int    `json:"duration"`
		} `json:"items"`
		TotalItems int `json:"totalItems"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return 0, fmt.Errorf("failed to parse response: %w", err)
	}

	log.Info("Found focus sessions", "count", result.TotalItems)
	return result.TotalItems, nil
}

// GetActiveFocus returns the remaining duration if a focus session is still active (timestamp + duration > now).
// Returns 0 if no active session exists.
func (m *Manager) GetActiveFocus() (time.Duration, error) {
	log.Info("Checking for active focus session")

	baseEndpoint := fmt.Sprintf("%s/api/collections/coach/records", m.BaseURL)
	u, err := url.Parse(baseEndpoint)
	if err != nil {
		return 0, fmt.Errorf("failed to parse URL: %w", err)
	}

	q := u.Query()
	q.Set("sort", "-timestamp")
	q.Set("perPage", "1")
	u.RawQuery = q.Encode()

	req, err := http.NewRequest("GET", u.String(), nil)
	if err != nil {
		return 0, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Authorization", m.AuthToken)

	resp, err := m.Client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return 0, fmt.Errorf("request failed with status %d: %s", resp.StatusCode, string(body))
		}
		return 0, fmt.Errorf("request failed: %s", errResp.Message)
	}

	var result struct {
		Items []struct {
			Timestamp string `json:"timestamp"`
			Duration  int    `json:"duration"`
		} `json:"items"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return 0, fmt.Errorf("failed to parse response: %w", err)
	}

	if len(result.Items) == 0 {
		return 0, nil
	}

	item := result.Items[0]
	ts, err := time.Parse("2006-01-02 15:04:05.000Z", item.Timestamp)
	if err != nil {
		return 0, fmt.Errorf("failed to parse timestamp: %w", err)
	}

	endTime := ts.Add(time.Duration(item.Duration) * time.Second)
	remaining := time.Until(endTime)
	if remaining <= 0 {
		return 0, nil
	}

	log.Info("Found active focus session", "remaining", remaining)
	return remaining, nil
}

// GetFocusHistory returns focus records for the last N days
func (m *Manager) GetFocusHistory(days int) ([]FocusRecord, error) {
	log.Info("Getting focus history", "days", days)

	// Calculate the start date
	startDate := time.Now().AddDate(0, 0, -days).Format("2006-01-02")

	baseEndpoint := fmt.Sprintf("%s/api/collections/coach/records", m.BaseURL)
	u, err := url.Parse(baseEndpoint)
	if err != nil {
		return nil, fmt.Errorf("failed to parse URL: %w", err)
	}

	q := u.Query()
	filter := fmt.Sprintf("timestamp >= '%s 00:00:00'", startDate)
	q.Set("filter", filter)
	q.Set("sort", "-timestamp")
	q.Set("perPage", "500") // Get up to 500 records
	u.RawQuery = q.Encode()

	fullURL := u.String()

	req, err := http.NewRequest("GET", fullURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Authorization", m.AuthToken)

	resp, err := m.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return nil, fmt.Errorf("request failed with status %d: %s", resp.StatusCode, string(body))
		}
		return nil, fmt.Errorf("request failed: %s", errResp.Message)
	}

	var result struct {
		Items []struct {
			ID        string `json:"id"`
			Timestamp string `json:"timestamp"`
			Duration  int    `json:"duration"`
		} `json:"items"`
		TotalItems int `json:"totalItems"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	// Convert to FocusRecord slice
	records := make([]FocusRecord, 0, len(result.Items))
	for _, item := range result.Items {
		// PocketBase uses "2006-01-02 15:04:05.000Z" format
		ts, err := time.Parse("2006-01-02 15:04:05.000Z", item.Timestamp)
		if err != nil {
			log.Warn("Failed to parse timestamp", "timestamp", item.Timestamp, "error", err)
			continue
		}
		records = append(records, FocusRecord{
			Timestamp: ts,
			Duration:  item.Duration,
		})
	}

	log.Info("Found focus records", "count", len(records))
	return records, nil
}

// DoRequest executes an HTTP request with auth token and automatic token refresh on 401/403.
func (m *Manager) DoRequest(req *http.Request) (*http.Response, error) {
	return m.doRequestWithRetry(req, true)
}

func (m *Manager) doRequestWithRetry(req *http.Request, canRetry bool) (*http.Response, error) {
	req.Header.Set("Authorization", m.AuthToken)

	resp, err := m.Client.Do(req)
	if err != nil {
		return nil, err
	}

	if (resp.StatusCode == 401 || resp.StatusCode == 403) && canRetry {
		resp.Body.Close()
		log.Info("Auth token expired, refreshing...")
		token, err := m.authenticate()
		if err != nil {
			return nil, fmt.Errorf("failed to refresh token: %w", err)
		}
		m.AuthToken = token
		log.Info("Token refreshed, retrying request")
		req.Header.Set("Authorization", m.AuthToken)
		return m.doRequestWithRetry(req, false)
	}

	return resp, nil
}

func (m *Manager) AddRecord(data map[string]any) error {
	return m.addRecordWithRetry(data, true)
}

func (m *Manager) addRecordWithRetry(data map[string]any, canRetry bool) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("failed to marshal record data: %w", err)
	}

	endpoint := fmt.Sprintf("%s/api/collections/coach/records", m.BaseURL)
	req, err := http.NewRequest("POST", endpoint, strings.NewReader(string(jsonData)))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", m.AuthToken)

	resp, err := m.Client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("failed to read response body: %w", err)
	}

	// Handle 401/403 by refreshing token and retrying once
	if (resp.StatusCode == 401 || resp.StatusCode == 403) && canRetry {
		log.Info("Auth token expired, refreshing...")
		token, err := m.authenticate()
		if err != nil {
			return fmt.Errorf("failed to refresh token: %w", err)
		}
		m.AuthToken = token
		log.Info("Token refreshed, retrying request")
		return m.addRecordWithRetry(data, false)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return fmt.Errorf("request failed with status %d: %s", resp.StatusCode, string(body))
		}
		return fmt.Errorf("request failed: %s", errResp.Message)
	}

	return nil
}

func (m *Manager) authenticate() (string, error) {
	data := map[string]string{
		"identity": m.email,
		"password": m.password,
	}
	jsonData, err := json.Marshal(data)
	if err != nil {
		return "", err
	}

	req, err := http.NewRequest("POST", m.BaseURL+loginEndpoint, strings.NewReader(string(jsonData)))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.Client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	if resp.StatusCode != http.StatusOK {
		var errResp ErrorResponse
		if err := json.Unmarshal(body, &errResp); err != nil {
			return "", fmt.Errorf("authentication failed with status %d: %s", resp.StatusCode, string(body))
		}
		return "", fmt.Errorf("authentication failed: %s", errResp.Message)
	}

	var authResp AuthResponse
	if err := json.Unmarshal(body, &authResp); err != nil {
		return "", err
	}

	return authResp.Token, nil
}
