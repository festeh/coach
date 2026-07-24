package db

import (
	"testing"
	"time"
)

// bucketUnlockDays is the half of GetUnlockStats that has no database in it, so
// the day boundaries can be checked without one.
func TestBucketUnlockDaysZeroFillsQuietDays(t *testing.T) {
	start := time.Date(2026, 7, 19, 0, 0, 0, 0, time.Local)

	stats := bucketUnlockDays(nil, start, 7)

	if len(stats) != 7 {
		t.Fatalf("expected 7 days, got %d", len(stats))
	}
	if stats[0].Day != "2026-07-19" || stats[6].Day != "2026-07-25" {
		t.Errorf("expected the window 07-19..07-25, got %s..%s", stats[0].Day, stats[6].Day)
	}
	for _, day := range stats {
		if day.Unlocks != 0 || day.UnlockedSeconds != 0 {
			t.Errorf("day %s should be empty, got %+v", day.Day, day)
		}
	}
}

func TestBucketUnlockDaysSumsPerDay(t *testing.T) {
	start := time.Date(2026, 7, 19, 0, 0, 0, 0, time.Local)
	rows := []unlockRow{
		{createdAt: time.Date(2026, 7, 19, 9, 30, 0, 0, time.Local), durationSeconds: 900},
		{createdAt: time.Date(2026, 7, 19, 22, 15, 0, 0, time.Local), durationSeconds: 600},
		{createdAt: time.Date(2026, 7, 25, 8, 0, 0, 0, time.Local), durationSeconds: 300},
	}

	stats := bucketUnlockDays(rows, start, 7)

	if stats[0].Unlocks != 2 || stats[0].UnlockedSeconds != 1500 {
		t.Errorf("expected 2 unlocks / 1500s on 07-19, got %+v", stats[0])
	}
	if stats[6].Unlocks != 1 || stats[6].UnlockedSeconds != 300 {
		t.Errorf("expected 1 unlock / 300s on 07-25, got %+v", stats[6])
	}
	if stats[3].Unlocks != 0 {
		t.Errorf("expected 07-22 to stay empty, got %+v", stats[3])
	}
}

// A late-evening decision belongs to the day the user lived, not to the UTC day
// it happens to fall in.
func TestBucketUnlockDaysUsesLocalDay(t *testing.T) {
	berlin, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Skipf("tzdata unavailable: %v", err)
	}
	start := time.Date(2026, 7, 24, 0, 0, 0, 0, berlin)
	// 23:30 Berlin on the 24th is 21:30 UTC on the same day, but stored as UTC it
	// would bucket differently if the code ignored the location.
	rows := []unlockRow{
		{createdAt: time.Date(2026, 7, 24, 23, 30, 0, 0, berlin).UTC(), durationSeconds: 900},
	}

	stats := bucketUnlockDays(rows, start, 2)

	if stats[0].Day != "2026-07-24" || stats[0].Unlocks != 1 {
		t.Errorf("expected the unlock on 07-24 local, got %+v", stats)
	}
}

func TestBucketUnlockDaysIgnoresRowsOutsideWindow(t *testing.T) {
	start := time.Date(2026, 7, 24, 0, 0, 0, 0, time.Local)
	rows := []unlockRow{
		{createdAt: time.Date(2026, 7, 1, 12, 0, 0, 0, time.Local), durationSeconds: 900},
	}

	stats := bucketUnlockDays(rows, start, 2)

	for _, day := range stats {
		if day.Unlocks != 0 {
			t.Errorf("a row from outside the window landed on %s: %+v", day.Day, day)
		}
	}
}
