package autobahn

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Window is a validity period. A zero End means "open end".
type Window struct {
	Start, End time.Time
}

// The Autobahn API only states validity periods as German free text in the description, e.g.
//
//	Beginn: 24.08.26 um 07:00 Uhr / Ende: 30.10.26 um 18:00 Uhr
//	27.10.26 von 17:00 bis 22:00 Uhr
//	29.10.26 20:00 bis zum 30.10.26 05:00 Uhr.
//	Jeden Montag, Dienstag und Freitag zwischen dem 02.11.26 und dem 27.11.26 von 08:00 bis 15:00 Uhr.
//	(Ende der Gesamtmaßnahme: 31.03.27)
//
// All times are local German time.
var (
	reDate    = `(\d{1,2}\.\d{1,2}\.\d{2,4})`
	reClock   = `(\d{1,2}:\d{2})`
	reBeginn  = regexp.MustCompile(`^\s*Beginn:\s*` + reDate + `(?:\s+um)?\s+` + reClock)
	reEnde    = regexp.MustCompile(`^\s*Ende:\s*` + reDate + `(?:\s+um)?\s+` + reClock)
	reSameDay = regexp.MustCompile(`^\s*` + reDate + `\s+von\s+` + reClock + `\s+bis\s+` + reClock)
	reRange   = regexp.MustCompile(`^\s*` + reDate + `\s+` + reClock + `(?:\s*Uhr)?\s+bis\s+(?:zum\s+)?` + reDate + `\s+` + reClock)
	reWeekly  = regexp.MustCompile(`^\s*Jeden\s+(.+?)\s+zwischen\s+dem\s+` + reDate + `\s+und\s+dem\s+` + reDate + `\s+von\s+` + reClock + `\s+bis\s+` + reClock)
	reOverall = regexp.MustCompile(`Ende der Gesamtmaßnahme:\s*` + reDate)
)

var weekdays = map[string]time.Weekday{
	"montag": time.Monday, "dienstag": time.Tuesday, "mittwoch": time.Wednesday, "donnerstag": time.Thursday,
	"freitag": time.Friday, "samstag": time.Saturday, "sonntag": time.Sunday,
}

// maxRecurringDays limits the expansion of recurring ("Jeden ...") schedules.
const maxRecurringDays = 400

func parseDate(s string, loc *time.Location) (time.Time, bool) {
	parts := strings.Split(s, ".")
	if len(parts) != 3 {
		return time.Time{}, false
	}
	d, err1 := strconv.Atoi(parts[0])
	m, err2 := strconv.Atoi(parts[1])
	y, err3 := strconv.Atoi(parts[2])
	if err1 != nil || err2 != nil || err3 != nil || m < 1 || m > 12 || d < 1 || d > 31 {
		return time.Time{}, false
	}
	if y < 100 {
		y += 2000
	}
	t := time.Date(y, time.Month(m), d, 0, 0, 0, 0, loc)
	if t.Day() != d { // e.g. 31.02.
		return time.Time{}, false
	}
	return t, true
}

// atClock returns the given day at hh:mm; "24:00" yields midnight of the following day.
func atClock(day time.Time, clock string) (time.Time, bool) {
	hm := strings.Split(clock, ":")
	h, err1 := strconv.Atoi(hm[0])
	m, err2 := strconv.Atoi(hm[1])
	if err1 != nil || err2 != nil || h > 24 || m > 59 || (h == 24 && m != 0) {
		return time.Time{}, false
	}
	return time.Date(day.Year(), day.Month(), day.Day(), h, m, 0, 0, day.Location()), true
}

func dateTime(date, clock string, loc *time.Location) (time.Time, bool) {
	d, ok := parseDate(date, loc)
	if !ok {
		return time.Time{}, false
	}
	return atClock(d, clock)
}

// dailyWindow builds a window on day from start to end clock; an end at or before the start
// refers to the following day (e.g. "von 22:00 bis 00:00").
func dailyWindow(day time.Time, from, to string) (Window, bool) {
	s, ok1 := atClock(day, from)
	e, ok2 := atClock(day, to)
	if !ok1 || !ok2 {
		return Window{}, false
	}
	if !e.After(s) {
		e = e.AddDate(0, 0, 1)
	}
	return Window{s, e}, true
}

// ParseSchedule extracts validity windows and the end of the overall measure from description
// lines. Windows are returned sorted by start time. overallEnd is zero if not stated.
func ParseSchedule(lines []string, loc *time.Location) (windows []Window, overallEnd time.Time) {
	var begin, end time.Time
	for _, raw := range lines {
		for _, line := range strings.Split(raw, "\n") {
			if m := reBeginn.FindStringSubmatch(line); m != nil {
				if t, ok := dateTime(m[1], m[2], loc); ok {
					begin = t
				}
				continue
			}
			if m := reEnde.FindStringSubmatch(line); m != nil {
				if t, ok := dateTime(m[1], m[2], loc); ok {
					end = t
				}
				continue
			}
			if m := reOverall.FindStringSubmatch(line); m != nil {
				if d, ok := parseDate(m[1], loc); ok {
					overallEnd = d.AddDate(0, 0, 1)
				}
				continue
			}
			if m := reSameDay.FindStringSubmatch(line); m != nil {
				if d, ok := parseDate(m[1], loc); ok {
					if w, ok := dailyWindow(d, m[2], m[3]); ok {
						windows = append(windows, w)
					}
				}
				continue
			}
			if m := reRange.FindStringSubmatch(line); m != nil {
				s, ok1 := dateTime(m[1], m[2], loc)
				e, ok2 := dateTime(m[3], m[4], loc)
				if ok1 && ok2 && e.After(s) {
					windows = append(windows, Window{s, e})
				}
				continue
			}
			if m := reWeekly.FindStringSubmatch(line); m != nil {
				windows = append(windows, expandWeekly(m[1], m[2], m[3], m[4], m[5], loc)...)
			}
		}
	}
	if !begin.IsZero() && (end.IsZero() || end.After(begin)) {
		windows = append(windows, Window{begin, end})
	}
	sort.Slice(windows, func(i, j int) bool { return windows[i].Start.Before(windows[j].Start) })
	return windows, overallEnd
}

func expandWeekly(days, from, to, timeFrom, timeTo string, loc *time.Location) []Window {
	set := map[time.Weekday]bool{}
	for _, w := range strings.FieldsFunc(strings.ToLower(days), func(r rune) bool { return r == ',' || r == ' ' }) {
		if wd, ok := weekdays[w]; ok {
			set[wd] = true
		}
	}
	first, ok1 := parseDate(from, loc)
	last, ok2 := parseDate(to, loc)
	if !ok1 || !ok2 || len(set) == 0 || last.Before(first) {
		return nil
	}
	var res []Window
	for d, n := first, 0; !d.After(last) && n < maxRecurringDays; d, n = d.AddDate(0, 0, 1), n+1 {
		if !set[d.Weekday()] {
			continue
		}
		if w, ok := dailyWindow(d, timeFrom, timeTo); ok {
			res = append(res, w)
		}
	}
	return res
}

// CurrentWindow returns the window that is active at now or, if none is, the next one to start.
// ok is false if all windows have ended.
func CurrentWindow(windows []Window, now time.Time) (Window, bool) {
	for _, w := range windows {
		if w.End.IsZero() || w.End.After(now) {
			return w, true
		}
	}
	return Window{}, false
}
