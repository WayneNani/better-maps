package regions

import (
	"strings"
	"testing"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
)

const poly = `hessen
1
   7.0 49.0
   10.0 49.0
   10.0 52.0
   7.0 52.0
   7.0 49.0
END
!hole
   8.0 50.0
   9.0 50.0
   9.0 51.0
END
2
   20 0
   21 0
   21 1
   20 1
END
END
`

func TestParsePoly(t *testing.T) {
	r, err := ParsePoly("hessen", strings.NewReader(poly))
	if err != nil {
		t.Fatal(err)
	}
	cases := map[geo.Point]bool{
		{Lon: 7.5, Lat: 50}: true, {Lon: 8.9, Lat: 50.1}: false, {Lon: 8.1, Lat: 50.8}: true,
		{Lon: 20.5, Lat: 0.5}: true, {Lon: 15, Lat: 50}: false, {Lon: 6, Lat: 50}: false,
	}
	for p, want := range cases {
		if r.Contains(p) != want {
			t.Errorf("Contains(%v) != %v", p, want)
		}
	}
}

func TestParsePolyErrors(t *testing.T) {
	for _, in := range []string{
		"",
		"name\n1\n 1 2\n",
		"name\n1\n 1 2\n 3 4\n 5 6\nEND\n",
		"name\n1\n 1 2 3\nEND\nEND\n",
		"name\n1\n x 2\nEND\nEND\n",
		"name\n1\n 200 2\nEND\nEND\n",
		"name\n1\n 1 2\n 3 4\nEND\nEND\n", // degenerate only
	} {
		if _, err := ParsePoly("x", strings.NewReader(in)); err == nil {
			t.Errorf("accepted %q", in)
		}
	}
}
