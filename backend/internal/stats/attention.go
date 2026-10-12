package stats

import (
	"sort"
	"time"

	"coach/internal/db"
)

// freshWindow is how recently a span must have been seen to count as "now".
// The extension heartbeats every 30s; 90s forgives two missed beats and
// matches the gap rule that closes intervals.
const freshWindow = 90 * time.Second

// SiteMinutes is one site's share of today's attention.
type SiteMinutes struct {
	Site    string `json:"site"`
	Minutes int    `json:"minutes"`
}

// CurrentAttention is the span that has the user's attention right now.
type CurrentAttention struct {
	State   string `json:"state"`
	Site    string `json:"site,omitempty"`
	Minutes int    `json:"minutes"`
}

// AttentionSummary is the judge-facing digest of today's attention.
type AttentionSummary struct {
	Now              *CurrentAttention `json:"now"`
	SiteMinutesToday int               `json:"site_minutes_today"`
	TopSitesToday    []SiteMinutes     `json:"top_sites_today"`
}

// SummarizeAttention folds attention intervals into the summary. Intervals are
// clipped to [dayStart, now] for the sums; the "now" streak runs from its
// span's true start, even when that start was before dayStart. Rows with
// malformed timestamps are skipped.
func SummarizeAttention(intervals []db.AttentionInterval, dayStart, now time.Time) AttentionSummary {
	out := AttentionSummary{TopSitesToday: []SiteMinutes{}}

	perSite := map[string]time.Duration{}
	var total time.Duration
	var latest *db.AttentionInterval
	var latestSeen, latestStart time.Time

	for i := range intervals {
		iv := &intervals[i]
		started, err := time.Parse(time.RFC3339, iv.StartedAt)
		if err != nil {
			continue
		}
		seen, err := time.Parse(time.RFC3339, iv.LastSeen)
		if err != nil {
			continue
		}

		if iv.State == "site" {
			start, end := started, seen
			if start.Before(dayStart) {
				start = dayStart
			}
			if end.After(now) {
				end = now
			}
			if d := end.Sub(start); d > 0 {
				total += d
				if iv.Site != "" {
					perSite[iv.Site] += d
				}
			}
		}

		if latest == nil || seen.After(latestSeen) {
			latest, latestSeen, latestStart = iv, seen, started
		}
	}

	out.SiteMinutesToday = int(total.Minutes())

	for site, d := range perSite {
		if d >= time.Minute {
			out.TopSitesToday = append(out.TopSitesToday, SiteMinutes{Site: site, Minutes: int(d.Minutes())})
		}
	}
	sort.Slice(out.TopSitesToday, func(a, b int) bool {
		x, y := out.TopSitesToday[a], out.TopSitesToday[b]
		if x.Minutes != y.Minutes {
			return x.Minutes > y.Minutes
		}
		return x.Site < y.Site
	})
	if len(out.TopSitesToday) > 5 {
		out.TopSitesToday = out.TopSitesToday[:5]
	}

	if latest != nil && now.Sub(latestSeen) <= freshWindow {
		out.Now = &CurrentAttention{
			State:   latest.State,
			Site:    latest.Site,
			Minutes: int(now.Sub(latestStart).Minutes()),
		}
	}

	return out
}
