package db

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
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

// Manager handles database operations and authentication
type Manager struct {
	BaseURL   string
	AuthToken string
	Client    *http.Client
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
		BaseURL: baseURL,
		Client:  &http.Client{Timeout: 10 * time.Second},
	}

	// Authenticate
	token, err := manager.authenticate(pbEmail, pbPassword)
	if err != nil {
		return nil, fmt.Errorf("authentication failed: %w", err)
	}

	manager.AuthToken = token
	log.Info("Database manager initialized successfully")
	return manager, nil
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
