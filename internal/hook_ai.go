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

You receive: current time, today's focus sessions with intervals, a 14-day focus history, whether the user has used their task manager today, and their task list.

Based on all of this, give a short nudge: suggest what to do next, encourage a focus session if idle, or remind them to check their task manager if they haven't today. Notice trends in the 14-day history (e.g. streaks, drop-offs, unusually busy or quiet days).

Keep it to 1-2 plain sentences. No markdown, no headings, no bullet points, no emojis. Just talk like a person.`

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
				Default: "claude-sonnet-4-6",
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

	// Current time
	fmt.Fprintf(&b, "## Current Time\n%s\n", time.Now().Local().Format("Monday, 2006-01-02 15:04"))

	// Coach focus stats
	info := ctx.State.GetCurrentFocusInfo()
	b.WriteString("\n## Focus Stats\n")
	fmt.Fprintf(&b, "- Currently focusing: %v\n", info.Focusing)
	fmt.Fprintf(&b, "- Sessions today: %d\n", info.NumFocuses)
	fmt.Fprintf(&b, "- Time since last state change: %ds\n", info.SinceLastChange)
	if info.Focusing && info.FocusTimeLeft > 0 {
		fmt.Fprintf(&b, "- Focus time remaining: %ds\n", info.FocusTimeLeft)
	}

	// 14-day focus history
	if ctx.Server != nil && ctx.Server.DBManager != nil {
		records, err := ctx.Server.DBManager.GetFocusHistory(14)
		if err != nil {
			log.Warn("Failed to fetch 14-day focus history", "error", err)
		} else {
			today := time.Now().Local().Format("2006-01-02")

			// Today's detailed sessions
			b.WriteString("\n## Today's Focus Sessions\n")
			todayCount := 0
			todayDuration := 0
			for _, r := range records {
				local := r.Timestamp.Local()
				if local.Format("2006-01-02") == today {
					endTime := local.Add(time.Duration(r.Duration) * time.Second)
					fmt.Fprintf(&b, "- %s – %s (%dm)\n", local.Format("15:04"), endTime.Format("15:04"), r.Duration/60)
					todayCount++
					todayDuration += r.Duration
				}
			}
			if todayCount == 0 {
				b.WriteString("No focus sessions yet today.\n")
			} else {
				fmt.Fprintf(&b, "Total today: %d sessions, %dm\n", todayCount, todayDuration/60)
			}

			// 14-day summary
			b.WriteString("\n## Last 14 Days Focus History\n")
			if len(records) == 0 {
				b.WriteString("No focus sessions in the last 14 days.\n")
			} else {
				// Aggregate by date
				type dayStat struct {
					count    int
					duration int
				}
				byDay := make(map[string]*dayStat)
				for _, r := range records {
					day := r.Timestamp.Local().Format("2006-01-02")
					s, ok := byDay[day]
					if !ok {
						s = &dayStat{}
						byDay[day] = s
					}
					s.count++
					s.duration += r.Duration
				}

				// Print sorted by date
				totalSessions := 0
				totalDuration := 0
				activeDays := 0
				for d := 13; d >= 0; d-- {
					day := time.Now().AddDate(0, 0, -d).Format("2006-01-02")
					if s, ok := byDay[day]; ok {
						fmt.Fprintf(&b, "- %s: %d sessions, %dm total\n", day, s.count, s.duration/60)
						totalSessions += s.count
						totalDuration += s.duration
						activeDays++
					}
				}
				fmt.Fprintf(&b, "- Total: %d sessions over %d active days, %dm total focus time\n", totalSessions, activeDays, totalDuration/60)
			}
		}
	}

	// Dimaist tasks
	if dimaistClient == nil {
		return b.String()
	}

	fetchCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Check if user interacted with dimaist today
	active, err := dimaistClient.WasActiveToday(fetchCtx)
	if err != nil {
		log.Warn("Failed to check dimaist activity", "error", err)
	} else {
		b.WriteString("\n## Task Manager Activity\n")
		if active {
			b.WriteString("User has been using their task manager today.\n")
		} else {
			b.WriteString("User has NOT opened their task manager today. Encourage them to review and plan their tasks.\n")
		}
	}

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
