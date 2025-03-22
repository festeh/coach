package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"
)

// PocketBase API endpoints
const (
	loginEndpoint      = "/api/admins/auth-with-password"
	collectionsEndpoint = "/api/collections"
)

// Authentication response from PocketBase
type AuthResponse struct {
	Token string `json:"token"`
	Admin struct {
		ID    string `json:"id"`
		Email string `json:"email"`
	} `json:"admin"`
}

// Collection schema for PocketBase
type Collection struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Schema  []Field  `json:"schema"`
	Indexes []string `json:"indexes,omitempty"`
}

// Field represents a schema field in PocketBase
type Field struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Required bool   `json:"required"`
	Options  any    `json:"options,omitempty"`
}

// Error response from PocketBase
type ErrorResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data"`
}

// CollectionListResponse represents the response when listing collections
type CollectionListResponse struct {
	Page       int          `json:"page"`
	PerPage    int          `json:"perPage"`
	TotalItems int          `json:"totalItems"`
	Items      []Collection `json:"items"`
}

func main() {
	// Get environment variables
	pbURL := os.Getenv("PB_URL")
	pbEmail := os.Getenv("PB_EMAIL")
	pbPassword := os.Getenv("PB_PASSWORD")

	// Validate environment variables
	if pbURL == "" || pbEmail == "" || pbPassword == "" {
		log.Fatal("Missing required environment variables: PB_URL, PB_EMAIL, PB_PASSWORD")
	}

	// Ensure URL doesn't end with a slash
	if pbURL[len(pbURL)-1] == '/' {
		pbURL = pbURL[:len(pbURL)-1]
	}

	// Authenticate with PocketBase
	token, err := authenticate(pbURL, pbEmail, pbPassword)
	if err != nil {
		log.Fatalf("Authentication failed: %v", err)
	}
	log.Println("Authentication successful")

	// Check if collection exists
	exists, err := collectionExists(pbURL, token, "coach")
	if err != nil {
		log.Fatalf("Failed to check if collection exists: %v", err)
	}

	if exists {
		log.Println("Collection 'coach' already exists")
		return
	}

	// Create the collection
	err = createCollection(pbURL, token)
	if err != nil {
		log.Fatalf("Failed to create collection: %v", err)
	}
	log.Println("Collection 'coach' created successfully")
}

// authenticate logs in to PocketBase and returns an auth token
func authenticate(baseURL, email, password string) (string, error) {
	data := map[string]string{
		"identity": email,
		"password": password,
	}
	jsonData, err := json.Marshal(data)
	if err != nil {
		return "", err
	}

	resp, err := http.Post(baseURL+loginEndpoint, "application/json", bytes.NewBuffer(jsonData))
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

// collectionExists checks if a collection with the given name exists
func collectionExists(baseURL, token, name string) (bool, error) {
	req, err := http.NewRequest("GET", baseURL+collectionsEndpoint, nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("Authorization", token)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
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
func createCollection(baseURL, token string) error {
	collection := Collection{
		Name: "coach",
		Type: "base",
		Schema: []Field{
			{
				Name:     "id",
				Type:     "number",
				Required: true,
				Options: map[string]interface{}{
					"min": 1,
					"max": nil,
				},
			},
			{
				Name:     "timestamp",
				Type:     "number",
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
		Indexes: []string{"id"},
	}

	jsonData, err := json.Marshal(collection)
	if err != nil {
		return err
	}

	req, err := http.NewRequest("POST", baseURL+collectionsEndpoint, bytes.NewBuffer(jsonData))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", token)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
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
			return fmt.Errorf("failed to create collection with status %d: %s", resp.StatusCode, string(body))
		}
		return fmt.Errorf("failed to create collection: %s", errResp.Message)
	}

	return nil
}
