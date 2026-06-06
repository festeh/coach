package coach

import (
	"time"

	"github.com/charmbracelet/log"
)

// attentionGap is how stale an open interval may be before a new beacon starts a
// fresh one. Heartbeats arrive every ~30s; missing two means the browser was gone
// and the silence must not be bridged into the interval.
const attentionGap = 90 * time.Second

// attentionStore is the slice of db.Manager the tracker needs (kept narrow for tests).
type attentionStore interface {
	CreateAttentionInterval(state, site string, at time.Time) (string, error)
	TouchAttentionInterval(recordID string, at time.Time) error
}

type beacon struct {
	state string
	site  string
	at    time.Time
}

// AttentionTracker folds the attention beacon stream into interval records:
// consecutive beacons with the same (state, site) extend one record's last_seen;
// a changed pair or a gap in beacons opens a new record.
//
// Beacons are queued onto a channel and processed by a single goroutine, so the
// WebSocket read loop is never blocked on PocketBase I/O.
type AttentionTracker struct {
	store   attentionStore
	beacons chan beacon

	// processing state, owned by the run goroutine
	recordID string
	state    string
	site     string
	lastSeen time.Time
}

func NewAttentionTracker(store attentionStore) *AttentionTracker {
	t := &AttentionTracker{
		store:   store,
		beacons: make(chan beacon, 64),
	}
	go t.run()
	return t
}

// Handle ingests one beacon. Never blocks; drops the beacon if the queue is full
// (the next heartbeat re-establishes state anyway).
func (t *AttentionTracker) Handle(state, site string) {
	select {
	case t.beacons <- beacon{state: state, site: site, at: time.Now()}:
	default:
		log.Warn("Attention beacon queue full, dropping beacon")
	}
}

func (t *AttentionTracker) run() {
	for b := range t.beacons {
		t.processBeacon(b)
	}
}

func (t *AttentionTracker) processBeacon(b beacon) {
	same := t.recordID != "" && t.state == b.state && t.site == b.site
	fresh := b.at.Sub(t.lastSeen) <= attentionGap

	if same && fresh {
		t.lastSeen = b.at
		if err := t.store.TouchAttentionInterval(t.recordID, b.at); err != nil {
			log.Error("Failed to update attention interval", "error", err)
			// Forget the broken record; the next beacon opens a fresh one
			// instead of retrying a failing PATCH every 30 seconds.
			t.recordID = ""
		}
		return
	}

	id, err := t.store.CreateAttentionInterval(b.state, b.site, b.at)
	if err != nil {
		log.Error("Failed to create attention interval", "error", err)
		t.recordID = ""
		return
	}
	t.recordID = id
	t.state = b.state
	t.site = b.site
	t.lastSeen = b.at
}
