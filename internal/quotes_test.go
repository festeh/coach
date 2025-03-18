package coach

import (
	"testing"
)

func TestLoad(t *testing.T) {
  store := QuoteStore{}
  err := store.Load()
  if err != nil {
    t.Errorf("Failed to load quotes: %v", err)
  }
  s := store.Quotes
  if len(s) != 5 {
    t.Errorf("Expected 5 quotes, got %d", len(s))
  }
}
