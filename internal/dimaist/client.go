package dimaist

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

type Task struct {
	ID          uint     `json:"id"`
	Title       string   `json:"title"`
	Description *string  `json:"description,omitempty"`
	DueDate     *string  `json:"due_date,omitempty"`
	DueDatetime *string  `json:"due_datetime,omitempty"`
	CompletedAt *string  `json:"completed_at,omitempty"`
	Labels      []string `json:"labels,omitempty"`
	Project     *Project `json:"project,omitempty"`
}

type Project struct {
	Name string `json:"name"`
}

type Client struct {
	baseURL    string
	httpClient *http.Client
}

func NewClient() (*Client, error) {
	baseURL := os.Getenv("DIMAIST_URL")
	if baseURL == "" {
		return nil, fmt.Errorf("DIMAIST_URL not set")
	}

	baseURL = strings.TrimRight(baseURL, "/")

	return &Client{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}, nil
}

// syncResponse is the response from the /sync endpoint.
type syncResponse struct {
	Tasks             []json.RawMessage `json:"tasks"`
	Projects          []json.RawMessage `json:"projects"`
	DeletedTaskIds    []uint            `json:"deleted_task_ids"`
	DeletedProjectIds []uint            `json:"deleted_project_ids"`
}

// WasActiveToday checks whether the user interacted with dimaist today
// by calling /sync with start-of-today as the sync token.
func (c *Client) WasActiveToday(ctx context.Context) (bool, error) {
	now := time.Now()
	startOfDay := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	token := startOfDay.Format(time.RFC3339)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/sync?sync_token="+url.QueryEscape(token), nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to fetch sync: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("dimaist sync API returned status %d", resp.StatusCode)
	}

	var sr syncResponse
	if err := json.NewDecoder(resp.Body).Decode(&sr); err != nil {
		return false, fmt.Errorf("failed to decode sync response: %w", err)
	}

	return len(sr.Tasks) > 0 || len(sr.Projects) > 0 || len(sr.DeletedTaskIds) > 0 || len(sr.DeletedProjectIds) > 0, nil
}

// GetTodayTasks fetches all tasks and returns those due today or earlier that are not completed.
func (c *Client) GetTodayTasks(ctx context.Context) ([]Task, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/tasks", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch tasks: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("dimaist API returned status %d", resp.StatusCode)
	}

	var allTasks []Task
	if err := json.NewDecoder(resp.Body).Decode(&allTasks); err != nil {
		return nil, fmt.Errorf("failed to decode tasks: %w", err)
	}

	endOfDay := endOfToday()
	var today []Task
	for _, t := range allTasks {
		if t.CompletedAt != nil {
			continue
		}
		if isDueBy(t, endOfDay) {
			today = append(today, t)
		}
	}

	return today, nil
}

func endOfToday() time.Time {
	now := time.Now()
	return time.Date(now.Year(), now.Month(), now.Day(), 23, 59, 59, 0, now.Location())
}

// parseFlexibleTime tries multiple date formats that dimaist may return.
func parseFlexibleTime(s string) (time.Time, bool) {
	formats := []string{
		time.RFC3339,
		"2006-01-02T15:04Z07:00",   // truncated RFC3339 (no seconds)
		"2006-01-02T15:04:05Z07:00", // with seconds and tz offset
		"2006-01-02",
	}
	for _, f := range formats {
		if parsed, err := time.Parse(f, s); err == nil {
			return parsed, true
		}
	}
	return time.Time{}, false
}

func isDueBy(t Task, deadline time.Time) bool {
	if t.DueDatetime != nil {
		if parsed, ok := parseFlexibleTime(*t.DueDatetime); ok {
			return !parsed.After(deadline)
		}
	}
	if t.DueDate != nil {
		if parsed, ok := parseFlexibleTime(*t.DueDate); ok {
			return !parsed.After(deadline)
		}
	}
	return false
}
