package coach

import (
	"fmt"
	"testing"
	"time"
)

// fakeAttentionStore records calls instead of talking to PocketBase.
type fakeAttentionStore struct {
	creates []beacon
	touches []string
	nextID  int
	failAll bool
}

func (f *fakeAttentionStore) CreateAttentionInterval(state, site string, at time.Time) (string, error) {
	if f.failAll {
		return "", fmt.Errorf("pb down")
	}
	f.creates = append(f.creates, beacon{state: state, site: site, at: at})
	f.nextID++
	return fmt.Sprintf("rec%d", f.nextID), nil
}

func (f *fakeAttentionStore) TouchAttentionInterval(recordID string, at time.Time) error {
	if f.failAll {
		return fmt.Errorf("pb down")
	}
	f.touches = append(f.touches, recordID)
	return nil
}

// newTestTracker builds a tracker without the run goroutine; tests drive
// processBeacon directly to stay deterministic.
func newTestTracker(store attentionStore) *AttentionTracker {
	return &AttentionTracker{store: store}
}

func TestAttentionTrackerExtendsSameInterval(t *testing.T) {
	store := &fakeAttentionStore{}
	tracker := newTestTracker(store)
	now := time.Now()

	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now})
	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now.Add(30 * time.Second)})
	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now.Add(60 * time.Second)})

	if len(store.creates) != 1 {
		t.Fatalf("expected 1 create, got %d", len(store.creates))
	}
	if len(store.touches) != 2 {
		t.Fatalf("expected 2 touches, got %d", len(store.touches))
	}
	if store.touches[0] != "rec1" || store.touches[1] != "rec1" {
		t.Fatalf("touches should target the open record, got %v", store.touches)
	}
}

func TestAttentionTrackerNewIntervalOnChange(t *testing.T) {
	store := &fakeAttentionStore{}
	tracker := newTestTracker(store)
	now := time.Now()

	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now})
	tracker.processBeacon(beacon{state: "site", site: "reddit.com", at: now.Add(10 * time.Second)})
	tracker.processBeacon(beacon{state: "idle", site: "", at: now.Add(20 * time.Second)})
	tracker.processBeacon(beacon{state: "away", site: "", at: now.Add(30 * time.Second)})

	if len(store.creates) != 4 {
		t.Fatalf("expected 4 creates, got %d", len(store.creates))
	}
	if len(store.touches) != 0 {
		t.Fatalf("expected 0 touches, got %d", len(store.touches))
	}
}

func TestAttentionTrackerNewIntervalAfterGap(t *testing.T) {
	store := &fakeAttentionStore{}
	tracker := newTestTracker(store)
	now := time.Now()

	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now})
	// Same site, but past the gap threshold — browser was gone, don't bridge.
	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now.Add(attentionGap + time.Second)})

	if len(store.creates) != 2 {
		t.Fatalf("expected 2 creates, got %d", len(store.creates))
	}
	if len(store.touches) != 0 {
		t.Fatalf("expected 0 touches, got %d", len(store.touches))
	}
}

func TestAttentionTrackerRecoversFromStoreErrors(t *testing.T) {
	store := &fakeAttentionStore{failAll: true}
	tracker := newTestTracker(store)
	now := time.Now()

	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now})
	if tracker.recordID != "" {
		t.Fatalf("recordID should stay empty after failed create")
	}

	// Store comes back: the next beacon opens a fresh interval.
	store.failAll = false
	tracker.processBeacon(beacon{state: "site", site: "github.com", at: now.Add(30 * time.Second)})

	if len(store.creates) != 1 {
		t.Fatalf("expected 1 create after recovery, got %d", len(store.creates))
	}
	if tracker.recordID != "rec1" {
		t.Fatalf("expected open record rec1, got %q", tracker.recordID)
	}
}
