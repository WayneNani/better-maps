package ohttp

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/cloudflare/circl/hpke"
)

func h(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(strings.Join(strings.Fields(s), ""))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// RFC 9458, Appendix A.
const (
	rfcSecretKey = "3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a"
	rfcKeyConfig = `01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e
		79815500080001000100010003`
	rfcRequest    = "00034745540568747470730b6578616d706c652e636f6d012f"
	rfcEncRequest = `010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad1
		9dec96c208b4726374e469135906992e1268c594d2a10c695d858c40a026e796
		5e7d86b83dd440b2c0185204b4d63525`
	rfcResponse      = "0140c8"
	rfcResponseNonce = "c789e7151fcba46158ca84b04464910d"
	rfcEncResponse   = `c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f
		857fbd`
)

func rfcKey(t *testing.T) *PrivateKey {
	t.Helper()
	k, err := PrivateKeyFromBytes(1, hpke.KEM_X25519_HKDF_SHA256, h(t, rfcSecretKey), []SymmetricSuite{
		{hpke.KDF_HKDF_SHA256, hpke.AEAD_AES128GCM},
		{hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305},
	})
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func lookupOf(keys ...*PrivateKey) KeyLookup {
	return func(id uint8) (*PrivateKey, bool) {
		for _, k := range keys {
			if k.Config.KeyID == id {
				return k, true
			}
		}
		return nil, false
	}
}

func TestRFC9458Example(t *testing.T) {
	key := rfcKey(t)
	if got := key.Config.MarshalBinary(); !bytes.Equal(got, h(t, rfcKeyConfig)) {
		t.Fatalf("key config = %x", got)
	}
	cfg, err := ParseKeyConfig(h(t, rfcKeyConfig))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.KeyID != 1 || cfg.KEM != hpke.KEM_X25519_HKDF_SHA256 || len(cfg.Suites) != 2 {
		t.Fatalf("parsed config = %+v", cfg)
	}

	pt, ctx, err := DecapsulateRequest(h(t, rfcEncRequest), lookupOf(key))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(pt, h(t, rfcRequest)) {
		t.Fatalf("request = %x", pt)
	}
	if !bytes.Equal(ctx.Enc(), h(t, "4b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472")) {
		t.Fatalf("enc = %x", ctx.Enc())
	}
	encRes, err := ctx.EncapsulateResponse(h(t, rfcResponse), bytes.NewReader(h(t, rfcResponseNonce)))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(encRes, h(t, rfcEncResponse)) {
		t.Fatalf("encapsulated response = %x", encRes)
	}
}

func TestRoundTripAllSuites(t *testing.T) {
	seed := bytes.Repeat([]byte{7}, 32)
	for _, s := range DefaultSuites {
		key, err := NewPrivateKey(9, DefaultKEM, seed, []SymmetricSuite{s})
		if err != nil {
			t.Fatal(err)
		}
		req := []byte("hello gateway")
		encReq, cctx, err := EncapsulateRequest(key.Config, req, rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		hdr, err := ParseRequestHeader(encReq)
		if err != nil || hdr.KeyID != 9 || hdr.Suite != s {
			t.Fatalf("header = %+v, %v", hdr, err)
		}
		pt, sctx, err := DecapsulateRequest(encReq, lookupOf(key))
		if err != nil || !bytes.Equal(pt, req) {
			t.Fatalf("decapsulate: %q, %v", pt, err)
		}
		encRes, err := sctx.EncapsulateResponse([]byte("hello client"), rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		res, err := cctx.DecapsulateResponse(encRes)
		if err != nil || string(res) != "hello client" {
			t.Fatalf("response: %q, %v", res, err)
		}
		// Tampered response.
		encRes[len(encRes)-1] ^= 1
		if _, err := cctx.DecapsulateResponse(encRes); !errors.Is(err, ErrDecrypt) {
			t.Fatalf("tampered response: %v", err)
		}
		if _, err := cctx.DecapsulateResponse(encRes[:10]); !errors.Is(err, ErrMalformed) {
			t.Fatalf("short response: %v", err)
		}
	}
}

func TestDecapsulateErrors(t *testing.T) {
	key := rfcKey(t)
	other, err := NewPrivateKey(1, DefaultKEM, bytes.Repeat([]byte{1}, 32), DefaultSuites)
	if err != nil {
		t.Fatal(err)
	}
	encReq, _, err := EncapsulateRequest(key.Config, []byte("x"), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	// Same key ID, different key: decryption must fail.
	if _, _, err := DecapsulateRequest(encReq, lookupOf(other)); !errors.Is(err, ErrDecrypt) {
		t.Errorf("wrong key: %v", err)
	}
	// Unknown key ID.
	if _, _, err := DecapsulateRequest(encReq, lookupOf()); !errors.Is(err, ErrUnknownKey) {
		t.Errorf("unknown key: %v", err)
	}
	// Tampered ciphertext.
	bad := append([]byte(nil), encReq...)
	bad[len(bad)-1] ^= 0x80
	if _, _, err := DecapsulateRequest(bad, lookupOf(key)); !errors.Is(err, ErrDecrypt) {
		t.Errorf("tampered: %v", err)
	}
	// Tampered header (AEAD switched): authenticated through the HPKE info.
	bad = append([]byte(nil), encReq...)
	bad[6] = byte(hpke.AEAD_ChaCha20Poly1305)
	if _, _, err := DecapsulateRequest(bad, lookupOf(key)); !errors.Is(err, ErrDecrypt) {
		t.Errorf("header swap: %v", err)
	}
	// Unsupported suite / KEM.
	bad = append([]byte(nil), encReq...)
	bad[6] = byte(hpke.AEAD_AES256GCM)
	if _, _, err := DecapsulateRequest(bad, lookupOf(key)); !errors.Is(err, ErrUnsupportedSuite) {
		t.Errorf("unsupported AEAD: %v", err)
	}
	bad = append([]byte(nil), encReq...)
	bad[2] = 0x10
	if _, _, err := DecapsulateRequest(bad, lookupOf(key)); !errors.Is(err, ErrUnsupportedSuite) {
		t.Errorf("other KEM: %v", err)
	}
	// Truncated.
	for _, n := range []int{0, 6, 7, 30, 7 + 32 + 15} {
		if _, _, err := DecapsulateRequest(encReq[:n], lookupOf(key)); !errors.Is(err, ErrMalformed) {
			t.Errorf("truncated to %d: %v", n, err)
		}
	}
	// Invalid enc (low-order point) is rejected by the KEM.
	bad = append([]byte(nil), encReq[:7]...)
	bad = append(bad, make([]byte, 32+16)...)
	if _, _, err := DecapsulateRequest(bad, lookupOf(key)); !errors.Is(err, ErrDecrypt) {
		t.Errorf("zero enc: %v", err)
	}
}

func TestKeyConfigParsing(t *testing.T) {
	key := rfcKey(t)
	other, _ := NewPrivateKey(2, DefaultKEM, bytes.Repeat([]byte{2}, 32), DefaultSuites)
	list := MarshalKeyConfigs([]KeyConfig{key.Config, other.Config})
	got, err := ParseKeyConfigs(list)
	if err != nil || len(got) != 2 || got[1].KeyID != 2 {
		t.Fatalf("got %+v, %v", got, err)
	}
	// A config with an unknown KEM is skipped, not fatal.
	unknown := KeyConfig{KeyID: 3, KEM: 0x7777, PublicKey: []byte{1, 2}, Suites: DefaultSuites}
	got, err = ParseKeyConfigs(MarshalKeyConfigs([]KeyConfig{unknown, key.Config}))
	if err != nil || len(got) != 1 || got[0].KeyID != 1 {
		t.Fatalf("skip unknown KEM: %+v, %v", got, err)
	}
	bad := map[string][]byte{
		"empty":          nil,
		"only unknown":   MarshalKeyConfigs([]KeyConfig{unknown}),
		"odd":            {0},
		"overlong":       {0, 50, 1},
		"truncated":      list[:len(list)-1],
		"short config":   {0, 2, 1, 0},
		"no algorithms":  append([]byte{0, 37}, append(key.Config.MarshalBinary()[:35], 0, 0)...),
		"odd algorithms": append([]byte{0, 38}, append(key.Config.MarshalBinary()[:35], 0, 1, 0)...),
		"bad public key": func() []byte {
			c := key.Config
			c.PublicKey = make([]byte, 31)
			return MarshalKeyConfigs([]KeyConfig{c})
		}(),
	}
	for name, b := range bad {
		if _, err := ParseKeyConfigs(b); err == nil {
			t.Errorf("%s: no error", name)
		}
	}
	// Suite selection skips unknown algorithms.
	c := key.Config
	c.Suites = []SymmetricSuite{{0x99, 0x99}, {hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305}}
	if s, err := c.SelectSuite(); err != nil || s.AEAD != hpke.AEAD_ChaCha20Poly1305 {
		t.Fatalf("select = %+v, %v", s, err)
	}
	c.Suites = []SymmetricSuite{{0x99, 0x99}}
	if _, err := c.SelectSuite(); !errors.Is(err, ErrUnsupportedSuite) {
		t.Fatal("expected unsupported suite")
	}
	if _, _, err := EncapsulateRequest(c, nil, rand.Reader); !errors.Is(err, ErrUnsupportedSuite) {
		t.Fatal("encapsulate with unsupported suite")
	}
	c.Suites = DefaultSuites
	c.PublicKey = []byte{1}
	if _, _, err := EncapsulateRequest(c, nil, rand.Reader); !errors.Is(err, ErrMalformed) {
		t.Fatal("encapsulate with invalid key")
	}
}

func TestNewPrivateKeyErrors(t *testing.T) {
	if _, err := NewPrivateKey(1, 0x7777, make([]byte, 32), DefaultSuites); err == nil {
		t.Error("invalid KEM accepted")
	}
	if _, err := NewPrivateKey(1, DefaultKEM, make([]byte, 8), DefaultSuites); err == nil {
		t.Error("short seed accepted")
	}
	if _, err := PrivateKeyFromBytes(1, 0x7777, make([]byte, 32), DefaultSuites); err == nil {
		t.Error("invalid KEM accepted")
	}
	if _, err := PrivateKeyFromBytes(1, DefaultKEM, make([]byte, 3), DefaultSuites); err == nil {
		t.Error("short private key accepted")
	}
}

func TestKeyRing(t *testing.T) {
	if _, err := NewKeyRing(make([]byte, 16), time.Hour); err == nil {
		t.Error("short master accepted")
	}
	if _, err := NewKeyRing(make([]byte, 32), time.Second); err == nil {
		t.Error("short period accepted")
	}
	master := bytes.Repeat([]byte{42}, 32)
	kr, err := NewKeyRing(master, 24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	kr2, _ := NewKeyRing(master, 24*time.Hour)
	now := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	cfgs := kr.Configs(now)
	if len(cfgs) != 2 || cfgs[0].KeyID+1 != cfgs[1].KeyID {
		t.Fatalf("configs = %+v", cfgs)
	}
	// Deterministic across instances, different across epochs.
	if !bytes.Equal(MarshalKeyConfigs(cfgs), MarshalKeyConfigs(kr2.Configs(now))) {
		t.Fatal("key ring is not deterministic")
	}
	if bytes.Equal(cfgs[0].PublicKey, cfgs[1].PublicKey) {
		t.Fatal("epochs share a key")
	}
	// A request to the current key is accepted one epoch later, but not two epochs later.
	encReq, _, err := EncapsulateRequest(cfgs[0], []byte("x"), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		at   time.Time
		want error
	}{
		{now, nil},
		{now.Add(24 * time.Hour), nil},
		{now.Add(-24 * time.Hour), nil},
		{now.Add(48 * time.Hour), ErrUnknownKey},
	} {
		_, _, err := DecapsulateRequest(encReq, kr.Lookup(tc.at))
		if !errors.Is(err, tc.want) {
			t.Errorf("at %v: err = %v, want %v", tc.at, err, tc.want)
		}
	}
	// Cache stays small.
	for e := int64(0); e < 20; e++ {
		kr.Key(e)
	}
	if len(kr.cache) > 5 {
		t.Fatalf("cache has %d entries", len(kr.cache))
	}
}

func TestReplayCache(t *testing.T) {
	c := NewReplayCache(time.Minute, 2)
	now := time.Unix(1000, 0)
	if c.Check([]byte("a"), now) != Fresh {
		t.Fatal("first request not fresh")
	}
	if c.Check([]byte("a"), now.Add(30*time.Second)) != Replayed {
		t.Fatal("replay not detected")
	}
	if c.Check([]byte("b"), now) != Fresh {
		t.Fatal("b not fresh")
	}
	if c.Check([]byte("c"), now) != CacheFull {
		t.Fatal("full cache accepted request")
	}
	// After expiry the entries are purged and accepted again.
	later := now.Add(2 * time.Minute)
	if c.Check([]byte("c"), later) != Fresh {
		t.Fatal("c not accepted after purge")
	}
	c.Purge(later.Add(2 * time.Minute))
	if c.Len() != 0 {
		t.Fatalf("len = %d after purge", c.Len())
	}
}
