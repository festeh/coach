package stats

import (
	"testing"
	"time"

	"coach/internal/db"
)

func ts(t time.Time) string { return t.UTC().Format(time.RFC3339) }

func TestSummarizeAttentionEmpty(t *testing.T) {
	now := time.Date(2026, 6, 10, 12, 0, 0, 0, time.UTC)
	day := now.Truncate(24 * time.Hour)

	got := SummarizeAttention(nil, day, now)

	if got.Now != nil {
		t.Errorf("Now = %+v, want nil", got.Now)
	}
	if got.SiteMinutesToday != 0 {
		t.Errorf("SiteMinutesToday = %d, want 0", got.SiteMinutesToday)
	}
	if len(got.TopSitesToday) != 0 {
		t.Errorf("TopSitesToday = %v, want empty", got.TopSitesToday)
	}
}

func TestSummarizeAttentionSumsAndRanks(t *testing.T) {
	now := time.Date(2026, 6, 10, 12, 0, 0, 0, time.UTC)
	day := now.Add(-12 * time.Hour)

	intervals := []db.AttentionInterval{
		{State: "site", Site: "youtube.com", StartedAt: ts(now.Add(-100 * time.Minute)), LastSeen: ts(now.Add(-60 * time.Minute))},
		{State: "site", Site: "github.com", StartedAt: ts(now.Add(-60 * time.Minute)), LastSeen: ts(now.Add(-50 * time.Minute))},
		{State: "idle", StartedAt: ts(now.Add(-50 * time.Minute)), LastSeen: ts(now.Add(-40 * time.Minute))},
		{State: "site", Site: "youtube.com", StartedAt: ts(now.Add(-40 * time.Minute)), LastSeen: ts(now.Add(-30 * time.Minute))},
		// Browser-internal page: counts toward the total, never the top list.
		{State: "site", Site: "", StartedAt: ts(now.Add(-30 * time.Minute)), LastSeen: ts(now.Add(-25 * time.Minute))},
		// Under a minute: stays out of the top list.
		{State: "site", Site: "blink.example", StartedAt: ts(now.Add(-25 * time.Minute)), LastSeen: ts(now.Add(-25*time.Minute + 30*time.Second))},
	}

	got := SummarizeAttention(intervals, day, now)

	if got.SiteMinutesToday != 50+10+5 {
		t.Errorf("SiteMinutesToday = %d, want 65", got.SiteMinutesToday)
	}
	want := []SiteMinutes{{Site: "youtube.com", Minutes: 50}, {Site: "github.com", Minutes: 10}}
	if len(got.TopSitesToday) != len(want) {
		t.Fatalf("TopSitesToday = %v, want %v", got.TopSitesToday, want)
	}
	for i := range want {
		if got.TopSitesToday[i] != want[i] {
			t.Errorf("TopSitesToday[%d] = %v, want %v", i, got.TopSitesToday[i], want[i])
		}
	}
	if got.Now != nil {
		t.Errorf("Now = %+v, want nil (last beacon is 24.5 minutes stale)", got.Now)
	}
}

func TestSummarizeAttentionClipsToDay(t *testing.T) {
	now := time.Date(2026, 6, 10, 0, 30, 0, 0, time.UTC)
	day := time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC)

	// Started 23:50 yesterday, still running: only the 30 in-window minutes
	// count toward today, but the streak runs from the true start.
	intervals := []db.AttentionInterval{
		{State: "site", Site: "youtube.com", StartedAt: ts(day.Add(-10 * time.Minute)), LastSeen: ts(now)},
	}

	got := SummarizeAttention(intervals, day, now)

	if got.SiteMinutesToday != 30 {
		t.Errorf("SiteMinutesToday = %d, want 30", got.SiteMinutesToday)
	}
	if got.Now == nil {
		t.Fatal("Now = nil, want current span")
	}
	if got.Now.State != "site" || got.Now.Site != "youtube.com" || got.Now.Minutes != 40 {
		t.Errorf("Now = %+v, want site youtube.com for 40 minutes", got.Now)
	}
}

func TestSummarizeAttentionFreshIdle(t *testing.T) {
	now := time.Date(2026, 6, 10, 12, 0, 0, 0, time.UTC)
	day := now.Add(-12 * time.Hour)

	intervals := []db.AttentionInterval{
		{State: "site", Site: "github.com", StartedAt: ts(now.Add(-20 * time.Minute)), LastSeen: ts(now.Add(-8 * time.Minute))},
		{State: "idle", StartedAt: ts(now.Add(-8 * time.Minute)), LastSeen: ts(now.Add(-30 * time.Second))},
	}

	got := SummarizeAttention(intervals, day, now)

	if got.Now == nil {
		t.Fatal("Now = nil, want fresh idle span")
	}
	if got.Now.State != "idle" || got.Now.Minutes != 8 {
		t.Errorf("Now = %+v, want idle for 8 minutes", got.Now)
	}
}

func TestSummarizeAttentionSkipsMalformed(t *testing.T) {
	now := time.Date(2026, 6, 10, 12, 0, 0, 0, time.UTC)
	day := now.Add(-12 * time.Hour)

	intervals := []db.AttentionInterval{
		{State: "site", Site: "bad.example", StartedAt: "not a time", LastSeen: ts(now)},
		{State: "site", Site: "github.com", StartedAt: ts(now.Add(-5 * time.Minute)), LastSeen: ts(now)},
	}

	got := SummarizeAttention(intervals, day, now)

	if got.SiteMinutesToday != 5 {
		t.Errorf("SiteMinutesToday = %d, want 5", got.SiteMinutesToday)
	}
	if got.Now == nil || got.Now.Site != "github.com" {
		t.Errorf("Now = %+v, want github.com", got.Now)
	}
}
