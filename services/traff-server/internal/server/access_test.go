package server

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/auth"
)

const (
	tokenPixel  = "pixelpixelpixelpixelpixelpixelpixelpixel123"
	tokenTablet = "tablettablettablettablettablettablettablet1"
)

// newAuthEnv returns a server that requires the tokens of the devices "pixel" and "tablet".
func newAuthEnv(t *testing.T, limits Limits) *env {
	t.Helper()
	e := newEnv(t, 0)
	tokens, err := auth.Parse(strings.NewReader(auth.HashLine("pixel", tokenPixel) + "\n" + auth.HashLine("tablet", tokenTablet) + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	e.srv.Tokens = tokens
	e.srv.Limits = limits
	e.http.Close()
	e.http = httptest.NewServer(e.srv.Handler())
	t.Cleanup(e.http.Close)
	return e
}

// do sends a request with the Authorization header auth (none if empty).
func (e *env) do(method, path, authz, body string) (*http.Response, []byte) {
	e.t.Helper()
	req, err := http.NewRequest(method, e.http.URL+path, strings.NewReader(body))
	if err != nil {
		e.t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/xml")
	req.Header.Set("User-Agent", "CoMaps/2026.10 secret-agent")
	if authz != "" {
		req.Header.Set("Authorization", authz)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

const capabilitiesReq = `<request operation="GET_CAPABILITIES"/>`

func TestAuthRequired(t *testing.T) {
	e := newAuthEnv(t, DefaultLimits())
	cases := []struct{ method, path, body string }{
		{"POST", "/", capabilitiesReq},
		{"POST", "/traff", subscribeReq(bboxHamburg)},
		{"GET", "/", ""},
		{"GET", "/traff", ""},
		{"GET", "/status", ""},
		{"GET", "/unknown", ""},
		{"DELETE", "/", ""},
	}
	for _, c := range cases {
		for _, authz := range []string{"", "Bearer", "Bearer " + strings.Repeat("x", 43), "Basic " + tokenPixel, tokenPixel, "Bearer short"} {
			resp, data := e.do(c.method, c.path, authz, c.body)
			if resp.StatusCode != http.StatusUnauthorized {
				t.Errorf("%s %s with %q = %d", c.method, c.path, authz, resp.StatusCode)
				continue
			}
			// No details: neither TraFF nor anything about the server or the token.
			if string(data) != "unauthorized\n" || resp.Header.Get("WWW-Authenticate") != `Bearer realm="traff"` {
				t.Errorf("%s %s: 401 body %q, headers %v", c.method, c.path, data, resp.Header)
			}
		}
	}
	if e.subs.Count() != 0 {
		t.Error("unauthorized SUBSCRIBE created a subscription")
	}

	// The body of an unauthorized request is not even read.
	big := `<request operation="POLL">` + strings.Repeat("<x/>", MaxRequestBytes/4) + `</request>`
	if resp, _ := e.do("POST", "/", "", big); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("big unauthorized request = %d", resp.StatusCode)
	}

	// With a valid token (any case of "Bearer") everything works.
	for _, authz := range []string{"Bearer " + tokenPixel, "bearer " + tokenTablet} {
		resp, data := e.do("POST", "/", authz, capabilitiesReq)
		if resp.StatusCode != 200 || !bytes.Contains(data, []byte("<capabilities")) {
			t.Errorf("capabilities with %q = %d %s", authz[:6], resp.StatusCode, data)
		}
	}
	resp, data := e.do("POST", "/traff", "Bearer "+tokenPixel, subscribeReq(bboxHamburg))
	if resp.StatusCode != 200 || !bytes.Contains(data, []byte(`subscription_id="SUB0001"`)) {
		t.Errorf("subscribe = %d %s", resp.StatusCode, data)
	}
	if resp, data := e.do("GET", "/status", "Bearer "+tokenPixel, ""); resp.StatusCode != 200 || !bytes.Contains(data, []byte(`"subscriptions":1`)) {
		t.Errorf("status = %d %s", resp.StatusCode, data)
	}
	if resp, _ := e.do("GET", "/unknown", "Bearer "+tokenPixel, ""); resp.StatusCode != http.StatusNotFound {
		t.Errorf("unknown path with token = %d", resp.StatusCode)
	}

	// A token removed from the store is rejected from then on.
	empty, _ := auth.Parse(strings.NewReader(""))
	e.srv.Tokens.Replace(empty)
	if resp, _ := e.do("POST", "/", "Bearer "+tokenPixel, capabilitiesReq); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("revoked token = %d", resp.StatusCode)
	}
}

func TestHealthzWithoutTokenAndContent(t *testing.T) {
	e := newAuthEnv(t, Limits{RatePerMinute: 1, RateBurst: 1, UnauthorizedPerSecond: 1, UnauthorizedBurst: 1})
	e.do("POST", "/", "Bearer "+tokenPixel, subscribeReq(bboxHamburg))
	for i := 0; i < 5; i++ {
		for _, method := range []string{"GET", "HEAD"} {
			resp, data := e.do(method, "/healthz", "", "")
			want := "ok\n"
			if method == "HEAD" {
				want = ""
			}
			if resp.StatusCode != 200 || string(data) != want {
				t.Errorf("%s /healthz = %d %q", method, resp.StatusCode, data)
			}
		}
		// A wrong token does not matter either (and is not counted as a failed attempt).
		if resp, _ := e.do("GET", "/healthz", "Bearer "+strings.Repeat("y", 43), ""); resp.StatusCode != 200 {
			t.Errorf("/healthz with wrong token = %d", resp.StatusCode)
		}
	}
	if resp, _ := e.do("POST", "/healthz", "", ""); resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("POST /healthz = %d", resp.StatusCode)
	}
	if strings.Contains(e.logs.String(), "healthz") {
		t.Errorf("/healthz is logged:\n%s", e.logs)
	}
}

func TestRateLimitPerToken(t *testing.T) {
	e := newAuthEnv(t, Limits{RatePerMinute: 6, RateBurst: 3, UnauthorizedPerSecond: 5, UnauthorizedBurst: 20})
	pixel, tablet := "Bearer "+tokenPixel, "Bearer "+tokenTablet
	for i := 0; i < 3; i++ {
		if resp, _ := e.do("POST", "/", pixel, capabilitiesReq); resp.StatusCode != 200 {
			t.Fatalf("request %d = %d", i, resp.StatusCode)
		}
	}
	resp, data := e.do("POST", "/", pixel, capabilitiesReq)
	if resp.StatusCode != http.StatusTooManyRequests || resp.Header.Get("Retry-After") != "10" || string(data) != "too many requests\n" {
		t.Errorf("4th request = %d, Retry-After %q, %q", resp.StatusCode, resp.Header.Get("Retry-After"), data)
	}
	// The status page counts as well.
	if resp, _ := e.do("GET", "/status", pixel, ""); resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("status over the limit = %d", resp.StatusCode)
	}
	// Other devices have their own budget.
	if resp, _ := e.do("POST", "/", tablet, capabilitiesReq); resp.StatusCode != 200 {
		t.Errorf("other device = %d", resp.StatusCode)
	}
	// 6 per minute: one more after 10 s.
	e.now = e.now.Add(10 * time.Second)
	if resp, _ := e.do("POST", "/", pixel, capabilitiesReq); resp.StatusCode != 200 {
		t.Errorf("after 10 s = %d", resp.StatusCode)
	}
	if resp, _ := e.do("POST", "/", pixel, capabilitiesReq); resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("second after 10 s = %d", resp.StatusCode)
	}
	if !strings.Contains(e.logs.String(), "device=pixel reason=rate_limit") {
		t.Errorf("rate limit not logged:\n%s", e.logs)
	}
	// Full buckets are pruned.
	e.now = e.now.Add(time.Hour)
	e.srv.Prune()
	if n := e.srv.perTok.Len(); n != 0 {
		t.Errorf("%d buckets after prune", n)
	}
}

func TestDefaultLimitsFitTheApp(t *testing.T) {
	// The app polls every 5 minutes; after start it subscribes, and moving across maps changes the
	// subscription. A burst of 30 and 1 per second leave plenty of room for that.
	l := DefaultLimits()
	if l.RatePerMinute < 12 || l.RateBurst < 10 {
		t.Errorf("limits too tight for the app: %+v", l)
	}
	e := newAuthEnv(t, l)
	for i := 0; i < l.RateBurst; i++ {
		if resp, _ := e.do("POST", "/", "Bearer "+tokenPixel, capabilitiesReq); resp.StatusCode != 200 {
			t.Fatalf("request %d = %d", i, resp.StatusCode)
		}
	}
}

func TestUnauthorizedFlood(t *testing.T) {
	e := newAuthEnv(t, Limits{RatePerMinute: 60, RateBurst: 30, UnauthorizedPerSecond: 1, UnauthorizedBurst: 3})
	wrong := "Bearer " + strings.Repeat("w", 43)
	for i := 0; i < 3; i++ {
		if resp, _ := e.do("POST", "/", wrong, capabilitiesReq); resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("wrong token %d = %d", i, resp.StatusCode)
		}
	}
	if resp, _ := e.do("POST", "/", wrong, capabilitiesReq); resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("flood = %d", resp.StatusCode)
	}
	// Requests without a token are answered with 401 and do not use up the budget.
	if resp, _ := e.do("POST", "/", "", capabilitiesReq); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("no token during flood = %d", resp.StatusCode)
	}
	e.now = e.now.Add(time.Second)
	// A valid token gets its attempt back.
	for i := 0; i < 3; i++ {
		if resp, _ := e.do("POST", "/", "Bearer "+tokenPixel, capabilitiesReq); resp.StatusCode != 200 {
			t.Errorf("valid token %d after flood = %d", i, resp.StatusCode)
		}
	}
}

func TestInsecureModeServesWithoutToken(t *testing.T) {
	e := newEnv(t, 0) // Tokens == nil
	if resp, _ := e.do("POST", "/", "", capabilitiesReq); resp.StatusCode != 200 {
		t.Errorf("without token = %d", resp.StatusCode)
	}
	if resp, _ := e.do("GET", "/status", "Bearer whatever", ""); resp.StatusCode != 200 {
		t.Errorf("status = %d", resp.StatusCode)
	}
	if !strings.Contains(e.logs.String(), "device=-") {
		t.Errorf("request not logged:\n%s", e.logs)
	}
}

// The log contains the label of the device, but never the token, the bounding box, the
// subscription ID, the user agent or the client address.
func TestLogsWithoutTokensCoordinatesOrClients(t *testing.T) {
	e := newAuthEnv(t, DefaultLimits())
	pixel := "Bearer " + tokenPixel
	_, data := e.do("POST", "/", pixel, subscribeReq(bboxHamburg))
	if !bytes.Contains(data, []byte("SUB0001")) {
		t.Fatalf("subscribe: %s", data)
	}
	e.do("POST", "/", pixel, `<request operation="SUBSCRIPTION_CHANGE" subscription_id="SUB0001"><filter_list><filter bbox="52.3 13.1 52.7 13.7"/></filter_list></request>`)
	e.do("POST", "/", pixel, `<request operation="POLL" subscription_id="SUB0001"/>`)
	e.do("POST", "/", "Bearer "+tokenTablet+"x", subscribeReq(bboxHamburg))
	e.do("GET", "/secret/path/53.5", pixel, "")
	e.do("GET", "/status", pixel, "")

	logs := e.logs.String()
	for _, secret := range []string{tokenPixel, tokenTablet, "53.3", "9.7", "52.3", "13.1", "SUB0001", "secret", "127.0.0.1", "CoMaps"} {
		if strings.Contains(logs, secret) {
			t.Errorf("log contains %q:\n%s", secret, logs)
		}
	}
	for _, want := range []string{
		"msg=request method=POST path=traff status=200",
		"device=pixel",
		"path=traff status=401",
		"reason=auth",
		"path=other status=404",
		"path=status status=200",
		"operation=SUBSCRIBE",
	} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
}
