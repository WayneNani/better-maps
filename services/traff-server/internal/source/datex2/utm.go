package datex2

import (
	"math"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// utmToLatLon converts ETRS89/UTM coordinates (northern hemisphere) to WGS84 latitude and
// longitude using the series expansion of the inverse transverse Mercator projection (Snyder,
// "Map Projections – A Working Manual", p. 63). ETRS89 and WGS84 differ by less than a metre in
// Germany, which is far below the precision of traffic messages.
func utmToLatLon(zone int, northing, easting float64) traff.LatLon {
	const (
		a  = 6378137.0         // GRS80 semi-major axis
		f  = 1 / 298.257222101 // GRS80 flattening
		k0 = 0.9996
	)
	e2 := f * (2 - f)
	ep2 := e2 / (1 - e2)
	x := easting - 500000
	m := northing / k0
	mu := m / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256))
	e1 := (1 - math.Sqrt(1-e2)) / (1 + math.Sqrt(1-e2))
	phi1 := mu + (3*e1/2-27*math.Pow(e1, 3)/32)*math.Sin(2*mu) +
		(21*e1*e1/16-55*math.Pow(e1, 4)/32)*math.Sin(4*mu) +
		(151*math.Pow(e1, 3)/96)*math.Sin(6*mu) +
		(1097*math.Pow(e1, 4)/512)*math.Sin(8*mu)
	sin, cos, tan := math.Sin(phi1), math.Cos(phi1), math.Tan(phi1)
	n1 := a / math.Sqrt(1-e2*sin*sin)
	t1 := tan * tan
	c1 := ep2 * cos * cos
	r1 := a * (1 - e2) / math.Pow(1-e2*sin*sin, 1.5)
	d := x / (n1 * k0)
	lat := phi1 - (n1*tan/r1)*(d*d/2-(5+3*t1+10*c1-4*c1*c1-9*ep2)*math.Pow(d, 4)/24+
		(61+90*t1+298*c1+45*t1*t1-252*ep2-3*c1*c1)*math.Pow(d, 6)/720)
	lon := (d - (1+2*t1+c1)*math.Pow(d, 3)/6 + (5-2*c1+28*t1-3*c1*c1+8*ep2+24*t1*t1)*math.Pow(d, 5)/120) / cos
	lon0 := float64(zone*6 - 183)
	return traff.LatLon{Lat: lat * 180 / math.Pi, Lon: lon0 + lon*180/math.Pi}
}
