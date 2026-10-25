package clientcheck

import "testing"

func TestParseResponse(t *testing.T) {
	doc := `<response status="OK" subscription_id="x" timeout="60"><feed>
<message id="a" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z">
 <location><from>+52.0 +13.0</from><to>+52.1 +13.1</to></location>
 <events><event class="CONGESTION" type="CONGESTION_QUEUE"/><event class="CONSTRUCTION" type="CONSTRUCTION_ROADWORKS"/></events>
</message>
<message id="b" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z" cancellation="true"/>
<message id="c" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z">
 <location><at>+52.0 +13.0</at></location><events><event class="CONGESTION" type="CONGESTION_QUEUE"/></events>
</message>
<message id="d" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z">
 <location><from>+52.0 +13.0</from><to>x</to><at>+52.0 +13.0</at></location></message>
<message id="e" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z">
 <location><from>+52.0 +13.0</from><to>+52.1 +13.1</to></location><events><event class="WEATHER" type="WEATHER_FOG"/></events></message>
<message id="f" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z"/>
<message id="g" receive_time="2026-10-26T08:00:00Z" update_time="2026-10-26T08:00:00Z" expiration_time="bad"/>
<message receive_time="2026-10-26T08:00:00Z"/>
<message id="h" update_time="2026-10-26T08:00:00Z" expiration_time="2026-10-26T09:00:00Z"/>
</feed></response>`
	res, err := ParseResponse([]byte(doc))
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != "OK" || res.Timeout != 60 || !res.HasFeed || len(res.Accepted) != 2 || len(res.Rejected) != 7 ||
		res.SkippedEvents["CONSTRUCTION_ROADWORKS"] != 1 || !res.Accepted[1].Cancellation {
		t.Fatalf("result = %+v", res)
	}
	for _, bad := range []string{`<x/>`, `<response/>`, `<response status="NOPE"/>`, `<response status="OK" timeout="x"/>`, `<`} {
		if _, err := ParseResponse([]byte(bad)); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
}
