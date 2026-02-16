package ai

import (
	"context"
	"fmt"
	"os"

	"github.com/openai/openai-go"
	"github.com/openai/openai-go/option"
)

type Client struct {
	client *openai.Client
}

func NewClient() (*Client, error) {
	aiURL := os.Getenv("AI_URL")
	aiKey := os.Getenv("AI_API_KEY")

	if aiURL == "" || aiKey == "" {
		return nil, fmt.Errorf("missing required environment variables: AI_URL, AI_API_KEY")
	}

	client := openai.NewClient(
		option.WithBaseURL(aiURL+"/v1/"),
		option.WithAPIKey(aiKey),
	)

	return &Client{client: &client}, nil
}

func (c *Client) Complete(ctx context.Context, model, systemPrompt, userMessage string) (string, error) {
	resp, err := c.client.Chat.Completions.New(ctx, openai.ChatCompletionNewParams{
		Model: model,
		Messages: []openai.ChatCompletionMessageParamUnion{
			openai.SystemMessage(systemPrompt),
			openai.UserMessage(userMessage),
		},
	})
	if err != nil {
		return "", fmt.Errorf("chat completion failed: %w", err)
	}

	if len(resp.Choices) == 0 {
		return "", fmt.Errorf("no choices in response")
	}

	return resp.Choices[0].Message.Content, nil
}
