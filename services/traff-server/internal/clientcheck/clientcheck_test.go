package clientcheck

import (
	"os"
	"strings"
	"testing"
)

func head(id string) string {
	return `<message id="` + id + `" receive_time="2026-10-25T16:00:00Z" update_time="2026-10-25T16:00:00Z" expiration_time="2026-10-25T16:30:00Z"`
}

func TestParseResponse(t *testing.T) {
	doc := `<response status="OK" subscription_id="abc" timeout="60"><feed>
` + head("m1") + `><location><from>+50.1 +8.6</from><to>50.2 8.7</to></location>
  <events><event class="CONSTRUCTION" type="CONSTRUCTION_ROADWORKS"/><event class="RESTRICTION" type="RESTRICTION_SPEED_LIMIT" speed="60"/></events></message>
<message id="c" receive_time="2026-10-25T16:00:00Z" update_time="2026-10-25T16:00:00+01:00" expiration_time="2026-10-25T16:30:00.5Z" cancellation="true"/>
` + head("m2") + `><location><at>50 8</at></location><events><event class="CONGESTION" type="CONGESTION_QUEUE"/></events></message>
` + head("m3") + `><location><from>50 8</from><to>50 8</to></location><events><event class="CONGESTION" type="DELAY_DELAY"/></events></message>
` + head("m4") + `><location><from>50 8</from><to>x y</to><at>50 8</at></location></message>
` + head("m5") + `/>
<message receive_time="2026-10-25T16:00:00Z"/>
<message id="t" receive_time="soon" update_time="2026-10-25T16:00:00Z" expiration_time="2026-10-25T16:30:00Z"/>
<message id="u" receive_time="2026-10-25T16:00:00Z" expiration_time="2026-10-25T16:30:00Z"/>
</feed></response>`
	res, err := ParseResponse([]byte(doc))
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != "OK" || res.SubscriptionID != "abc" || res.Timeout != 60 || !res.HasFeed {
		t.Errorf("header = %+v", res)
	}
	if len(res.Accepted) != 2 || res.Accepted[0].Events[0] != "RESTRICTION_SPEED_LIMIT" || !res.Accepted[1].Cancellation {
		t.Errorf("accepted = %+v", res.Accepted)
	}
	if res.SkippedEvents["CONSTRUCTION_ROADWORKS"] != 1 || res.SkippedEvents["DELAY_DELAY"] != 1 {
		t.Errorf("skipped = %v", res.SkippedEvents)
	}
	wantReasons := []string{"only 1 of from/to/at", "no event known to the client", "no events", "no location", "no id", "invalid receive_time", "missing update_time"}
	got := map[string]bool{}
	for _, r := range res.Rejected {
		got[r] = true
	}
	for _, w := range wantReasons {
		if !got[w] {
			t.Errorf("missing rejection %q in %v", w, res.Rejected)
		}
	}
}

func TestParseResponseErrors(t *testing.T) {
	for _, doc := range []string{
		`<response`,
		`<feed/>`,
		`<response/>`,
		`<response status="MAYBE"/>`,
		`<response status="OK" timeout="soon"/>`,
	} {
		if _, err := ParseResponse([]byte(doc)); err == nil {
			t.Errorf("ParseResponse(%q) accepted", doc)
		}
	}
	res, err := ParseResponse([]byte(`<response status="SUBSCRIPTION_UNKNOWN"/>`))
	if err != nil || res.HasFeed || res.Status != "SUBSCRIPTION_UNKNOWN" {
		t.Errorf("%+v %v", res, err)
	}
}

// TestUpstreamSampleFeeds checks the parser port against a sample feed shipped with the CoMaps
// traffic branch (data/test_data/traff/LT-A5-Kaunas-Eastbound.xml), wrapped in a response.
func TestUpstreamSampleFeeds(t *testing.T) {
	data, err := os.ReadFile("testdata/LT-A5-Kaunas-Eastbound.xml")
	if err != nil {
		t.Fatal(err)
	}
	doc := `<response status="OK">` + strings.SplitN(string(data), "-->", 2)[1] + `</response>`
	res, err := ParseResponse([]byte(doc))
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Accepted) != 1 || len(res.Accepted[0].Events) != 1 || res.Accepted[0].Events[0] != "RESTRICTION_SPEED_LIMIT" {
		t.Errorf("accepted = %+v rejected = %v", res.Accepted, res.Rejected)
	}
	if res.SkippedEvents["RESTRICTION_MAX_WIDTH"] != 1 || res.SkippedEvents["CONSTRUCTION_ROADWORKS"] != 1 {
		t.Errorf("skipped = %v", res.SkippedEvents)
	}
}
