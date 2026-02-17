package coach

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/log"

	"coach/internal/ai"
	"coach/internal/dimaist"
)

const defaultSystemPrompt = `You are a productivity coach. The user is trying to stay focused and get things done today.

Based on their focus stats and task list, suggest which task to tackle next and encourage them to start a focus session if they aren't in one. Keep it to 1-2 plain sentences. No markdown, no headings, no bullet points, no emojis. Just talk like a person.`

func NewAIHookDef(aiClient *ai.Client, dimaistClient *dimaist.Client) *HookDef {
	return &HookDef{
		ID:          "ai_request",
		Name:        "AI Coaching Prompt",
		Description: "Gathers focus stats and today's tasks, then sends context to AI for a coaching message",
		Params: []ParamDef{
			{
				Key:     "model",
				Name:    "Model",
				Type:    "text",
				Default: "claude-sonnet-4-5",
			},
			{
				Key:     "prompt",
				Name:    "System Prompt",
				Type:    "textarea",
				Default: defaultSystemPrompt,
			},
		},
		Run: func(ctx HookContext) error {
			model := ctx.Params["model"]
			prompt := ctx.Params["prompt"]

			// Phase 1: Gather context
			userMessage := GatherContext(ctx, dimaistClient)

			log.Info("Running AI hook", "model", model, "trigger", ctx.Trigger)

			// Phase 2: AI call
			aiCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()

			response, err := aiClient.Complete(aiCtx, model, prompt, userMessage)
			if err != nil {
				return fmt.Errorf("AI request failed: %w", err)
			}

			log.Info("AI hook response received", "length", len(response))

			// Store result in PocketBase
			resultData := map[string]any{
				"hook_id": "ai_request",
				"content": response,
				"read":    false,
			}

			resultID, err := ctx.Server.DBManager.AddHookResult(resultData)
			if err != nil {
				return fmt.Errorf("failed to store hook result: %w", err)
			}

			// Broadcast to connected clients
			ctx.Server.State.NotifyAllClients(map[string]any{
				"type":    "hook_result",
				"hook_id": "ai_request",
				"id":      resultID,
				"content": response,
			})

			return nil
		},
	}
}

func GatherContext(ctx HookContext, dimaistClient *dimaist.Client) string {
	var b strings.Builder

	// Coach focus stats
	info := ctx.State.GetCurrentFocusInfo()
	b.WriteString("## Focus Stats\n")
	fmt.Fprintf(&b, "- Currently focusing: %v\n", info.Focusing)
	fmt.Fprintf(&b, "- Sessions today: %d\n", info.NumFocuses)
	fmt.Fprintf(&b, "- Time since last state change: %ds\n", info.SinceLastChange)
	if info.Focusing && info.FocusTimeLeft > 0 {
		fmt.Fprintf(&b, "- Focus time remaining: %ds\n", info.FocusTimeLeft)
	}

	// Dimaist tasks
	if dimaistClient == nil {
		return b.String()
	}

	fetchCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tasks, err := dimaistClient.GetTodayTasks(fetchCtx)
	if err != nil {
		log.Warn("Failed to fetch dimaist tasks for AI context", "error", err)
		return b.String()
	}

	if len(tasks) == 0 {
		b.WriteString("\n## Today's Tasks\nNo tasks due today.\n")
		return b.String()
	}

	b.WriteString("\n## Today's Tasks\n")
	for i, t := range tasks {
		fmt.Fprintf(&b, "%d. %s", i+1, t.Title)
		if t.Project != nil && t.Project.Name != "" {
			fmt.Fprintf(&b, " (project: %s)", t.Project.Name)
		}
		if len(t.Labels) > 0 {
			fmt.Fprintf(&b, " [%s]", strings.Join(t.Labels, ", "))
		}
		b.WriteString("\n")
	}

	return b.String()
}
