package coach

import (
	"context"
	"fmt"
	"time"

	"github.com/charmbracelet/log"

	"coach/internal/ai"
)

func NewAIHookDef(aiClient *ai.Client) *HookDef {
	return &HookDef{
		ID:          "ai_request",
		Name:        "AI Coaching Prompt",
		Description: "Sends focus context to AI and stores the response",
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
				Default: "You are a focus coach. Provide a brief motivational message based on the user's focus session data.",
			},
		},
		Run: func(ctx HookContext) error {
			model := ctx.Params["model"]
			prompt := ctx.Params["prompt"]

			// Gather focus context
			info := ctx.State.GetCurrentFocusInfo()
			userMessage := fmt.Sprintf(
				"Current state: focusing=%v, sessions today=%d, time since last change=%ds",
				info.Focusing, info.NumFocuses, info.SinceLastChange,
			)

			log.Info("Running AI hook", "model", model, "trigger", ctx.Trigger)

			// Call AI
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

			// Broadcast full result to connected clients
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
