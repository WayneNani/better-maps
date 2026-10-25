package ohttp

import (
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"sync"
	"time"
)

// KeyRing derives gateway keys for consecutive epochs from one master secret.
//
// Epoch e covers [e*Period, (e+1)*Period) in Unix time. The key of epoch e has key identifier
// e mod 256 and its seed is HKDF-SHA256(master, salt, "epoch" || e). Deriving keys instead of
// storing them means that rotation needs no state, and that several gateway instances with the
// same master secret serve identical key configurations.
//
// The gateway publishes the configurations of the current and the next epoch (so that clients
// can switch over without a gap) and accepts requests for the previous, current and next epoch.
// At most three keys are therefore valid at any time, which limits the possibility to tell
// clients apart by the key they use (key consistency, RFC 9458, Section 7).
type KeyRing struct {
	master []byte
	period time.Duration
	suites []SymmetricSuite

	mu    sync.Mutex
	cache map[int64]*PrivateKey
}

const keyRingSalt = "better-maps crowd-traffic ohttp key v1"

// NewKeyRing creates a key ring. master must have at least 32 bytes.
func NewKeyRing(master []byte, period time.Duration) (*KeyRing, error) {
	if len(master) < 32 {
		return nil, errors.New("ohttp: master secret must have at least 32 bytes")
	}
	if period < time.Minute {
		return nil, errors.New("ohttp: key period must be at least one minute")
	}
	return &KeyRing{
		master: append([]byte(nil), master...),
		period: period,
		suites: DefaultSuites,
		cache:  map[int64]*PrivateKey{},
	}, nil
}

// Epoch returns the epoch number of t.
func (k *KeyRing) Epoch(t time.Time) int64 {
	return t.Unix() / int64(k.period/time.Second)
}

// Key returns the key of epoch e.
func (k *KeyRing) Key(e int64) *PrivateKey {
	k.mu.Lock()
	defer k.mu.Unlock()
	if key, ok := k.cache[e]; ok {
		return key
	}
	info := binary.BigEndian.AppendUint64([]byte("epoch"), uint64(e))
	seed, err := hkdf.Key(sha256.New, k.master, []byte(keyRingSalt), string(info), 32)
	if err != nil {
		panic(err) // only fails for invalid lengths
	}
	key, err := NewPrivateKey(uint8(e), DefaultKEM, seed, k.suites)
	if err != nil {
		panic(err) // DefaultKEM is valid and the seed is long enough
	}
	// Keep the cache small: only epochs that can still be used.
	for old := range k.cache {
		if old < e-2 || old > e+2 {
			delete(k.cache, old)
		}
	}
	k.cache[e] = key
	return key
}

// Configs returns the key configurations to publish at time now: the current epoch first, then
// the next one.
func (k *KeyRing) Configs(now time.Time) []KeyConfig {
	e := k.Epoch(now)
	return []KeyConfig{k.Key(e).Config, k.Key(e + 1).Config}
}

// Lookup returns a KeyLookup that accepts the previous, current and next epoch at time now.
func (k *KeyRing) Lookup(now time.Time) KeyLookup {
	e := k.Epoch(now)
	return func(id uint8) (*PrivateKey, bool) {
		for _, c := range []int64{e, e - 1, e + 1} {
			if uint8(c) == id {
				return k.Key(c), true
			}
		}
		return nil, false
	}
}
