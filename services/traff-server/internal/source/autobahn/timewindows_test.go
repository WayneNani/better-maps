package autobahn

import (
	"testing"
	"time"
)

func berlin(t *testing.T) *time.Location {
	t.Helper()
	tz, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	return tz
}

func TestParseSchedule(t *testing.T) {
	tz := berlin(t)
	at := func(y, mo, d, h, mi int) time.Time { return time.Date(y, time.Month(mo), d, h, mi, 0, 0, tz) }
	tests := []struct {
		name    string
		lines   []string
		want    []Window
		overall time.Time
	}{
		{
			name:    "begin and end",
			lines:   []string{"Zeitraum dieser Bauphase:", "Beginn: 24.08.26 um 07:00 Uhr", "Ende: 30.10.26 um 18:00 Uhr", "(Ende der Gesamtmaßnahme: 30.10.26)"},
			want:    []Window{{at(2026, 8, 24, 7, 0), at(2026, 10, 30, 18, 0)}},
			overall: at(2026, 10, 31, 0, 0),
		},
		{
			name:  "begin only (warnings)",
			lines: []string{"Beginn: 25.10.26 um 16:08 Uhr", "", "Angespannte Verkehrslage"},
			want:  []Window{{Start: at(2026, 10, 25, 16, 8)}},
		},
		{
			name:  "end before begin is ignored",
			lines: []string{"Beginn: 25.10.26 um 16:08 Uhr", "Ende: 24.10.26 um 16:08 Uhr"},
		},
		{
			name:  "same day",
			lines: []string{"Die Baustelle ist zu folgenden Zeiträumen gültig:", "27.10.26 von 17:00 bis 22:00 Uhr", "28.10.26 von 22:00 bis 00:00 Uhr"},
			want:  []Window{{at(2026, 10, 27, 17, 0), at(2026, 10, 27, 22, 0)}, {at(2026, 10, 28, 22, 0), at(2026, 10, 29, 0, 0)}},
		},
		{
			name:  "range across midnight and 24:00",
			lines: []string{"28.10.26 19:00 bis zum 29.10.26 05:15 Uhr.", "25.10.26 00:00 Uhr bis zum 25.10.26 24:00 Uhr."},
			want:  []Window{{at(2026, 10, 25, 0, 0), at(2026, 10, 26, 0, 0)}, {at(2026, 10, 28, 19, 0), at(2026, 10, 29, 5, 15)}},
		},
		{
			name:  "weekly",
			lines: []string{"Jeden Freitag und Samstag zwischen dem 30.10.26 und dem 01.11.26 von 20:00 bis 00:00 Uhr."},
			want:  []Window{{at(2026, 10, 30, 20, 0), at(2026, 10, 31, 0, 0)}, {at(2026, 10, 31, 20, 0), at(2026, 11, 1, 0, 0)}},
		},
		{
			name:  "weekly working days",
			lines: []string{"Jeden Montag, Dienstag, Mittwoch, Donnerstag und Freitag zwischen dem 02.11.26 und dem 06.11.26 von 08:00 bis 15:00 Uhr.\n(Ende der Gesamtmaßnahme: 06.11.26)"},
			want: []Window{
				{at(2026, 11, 2, 8, 0), at(2026, 11, 2, 15, 0)}, {at(2026, 11, 3, 8, 0), at(2026, 11, 3, 15, 0)},
				{at(2026, 11, 4, 8, 0), at(2026, 11, 4, 15, 0)}, {at(2026, 11, 5, 8, 0), at(2026, 11, 5, 15, 0)},
				{at(2026, 11, 6, 8, 0), at(2026, 11, 6, 15, 0)},
			},
			overall: at(2026, 11, 7, 0, 0),
		},
		{
			name: "invalid values are ignored",
			lines: []string{"31.02.26 von 08:00 bis 09:00 Uhr", "27.13.26 von 08:00 bis 09:00 Uhr", "27.10.26 von 25:00 bis 09:00 Uhr",
				"27.10.26 08:00 bis zum 26.10.26 09:00 Uhr", "Jeden Feiertag zwischen dem 01.11.26 und dem 02.11.26 von 08:00 bis 09:00 Uhr",
				"Jeden Montag zwischen dem 09.11.26 und dem 02.11.26 von 08:00 bis 09:00 Uhr", "10.11.26, 11.11.26, 12.11.26",
				"Beginn: 1.1 um 08:00", "(Ende der Gesamtmaßnahme: 99.99.99)", "Ende: 00.10.26 um 08:00 Uhr", "27.10.26 von 08:61 bis 09:00 Uhr"},
		},
		{
			name:  "four digit year",
			lines: []string{"27.10.2026 von 08:00 bis 09:00 Uhr"},
			want:  []Window{{at(2026, 10, 27, 8, 0), at(2026, 10, 27, 9, 0)}},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, overall := ParseSchedule(tc.lines, tz)
			if len(got) != len(tc.want) {
				t.Fatalf("windows = %v, want %v", got, tc.want)
			}
			for i := range got {
				if !got[i].Start.Equal(tc.want[i].Start) || !got[i].End.Equal(tc.want[i].End) {
					t.Errorf("window %d = %v, want %v", i, got[i], tc.want[i])
				}
			}
			if !overall.Equal(tc.overall) {
				t.Errorf("overall end = %v, want %v", overall, tc.overall)
			}
		})
	}
}

func TestRecurringExpansionIsBounded(t *testing.T) {
	got, _ := ParseSchedule([]string{"Jeden Montag, Dienstag, Mittwoch, Donnerstag, Freitag, Samstag und Sonntag zwischen dem 01.01.26 und dem 31.12.30 von 08:00 bis 09:00 Uhr."}, berlin(t))
	if len(got) != maxRecurringDays {
		t.Errorf("expanded to %d windows, want %d", len(got), maxRecurringDays)
	}
}

func TestCurrentWindow(t *testing.T) {
	base := time.Date(2026, 10, 25, 0, 0, 0, 0, time.UTC)
	ws := []Window{
		{base.Add(1 * time.Hour), base.Add(2 * time.Hour)},
		{base.Add(5 * time.Hour), base.Add(6 * time.Hour)},
	}
	cases := []struct {
		now  time.Duration
		want int // index, -1 = none
	}{{0, 0}, {90 * time.Minute, 0}, {2 * time.Hour, 1}, {3 * time.Hour, 1}, {6 * time.Hour, -1}}
	for _, c := range cases {
		w, ok := CurrentWindow(ws, base.Add(c.now))
		if c.want < 0 {
			if ok {
				t.Errorf("now+%v: got %v, want none", c.now, w)
			}
			continue
		}
		if !ok || w != ws[c.want] {
			t.Errorf("now+%v: got %v %v, want %v", c.now, w, ok, ws[c.want])
		}
	}
	if w, ok := CurrentWindow([]Window{{Start: base}}, base.Add(1000*time.Hour)); !ok || !w.End.IsZero() {
		t.Error("open-ended window must always be current")
	}
}
