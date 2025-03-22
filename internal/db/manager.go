package db

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"strings"
	"time"

	log "golang.org/x/exp/slog"
)

const (
	loginEndpoint = "/api/collections/_superusers/auth-with-password"
	secretsFile   = "secrets.json"
)

// Credentials holds the PocketBase authentication credentials
type Credentials struct {
	URL      string `json:"pb_url"`
	Email    string `json:"pb_email"`
	Password string `json:"pb_password"`
}

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

// Manager handles database operations and authentication
type Manager struct {
	BaseURL   string
	AuthToken string
	Client    *http.Client
}

// InitManager initializes a new database manager
// It loads credentials from the secrets file and authenticates with PocketBase
func InitManager() (*Manager, error) {
	// Load credentials from secrets file
	creds, err := loadCredentials()
	if err != nil {
		return nil, fmt.Errorf("failed to load credentials: %w", err)
	}

	// Validate credentials
	if creds.URL == "" || creds.Email == "" || creds.Password == "" {
		return nil, fmt.Errorf("missing required credentials in secrets file")
	}

	// Ensure URL has proper format
	baseURL := creds.URL
	if !strings.HasPrefix(baseURL, "http") {
		baseURL = "http://" + baseURL
	}
	if strings.HasSuffix(baseURL, "/") {
		baseURL = baseURL[:len(baseURL)-1]
	}

	// Create manager
	manager := &Manager{
		BaseURL: baseURL,
		Client:  &http.Client{Timeout: 10 * time.Second},
	}

	// Authenticate
	token, err := manager.authenticate(creds.Email, creds.Password)
	if err != nil {
		return nil, fmt.Errorf("authentication failed: %w", err)
	}

	manager.AuthToken = token
	log.Info("Database manager initialized successfully")
	return manager, nil
}

// loadCredentials loads the PocketBase credentials from the secrets file
func loadCredentials() (*Credentials, error) {
	data, err := os.ReadFile(secretsFile)
	if err != nil {
		return nil, err
	}

	var creds Credentials
	if err := json.Unmarshal(data, &creds); err != nil {
		return nil, err
	}

	return &creds, nil
}

// authenticate authenticates with PocketBase and returns the auth token
func (m *Manager) authenticate(email, password string) (string, error) {
	data := map[string]string{
		"identity": email,
		"password": password,
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

	body, err := ioutil.ReadAll(resp.Body)
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
