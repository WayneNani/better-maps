// Package ohttp implements Oblivious HTTP (RFC 9458) message encapsulation on top of the HPKE
// implementation of github.com/cloudflare/circl.
//
// This package is a thin framing layer: all cryptographic primitives (KEM, key schedule, AEAD,
// HKDF) come from circl. It implements
//   - key configurations and the application/ohttp-keys format (RFC 9458, Section 3),
//   - request encapsulation and decapsulation (Section 4.3),
//   - response encapsulation and decapsulation (Section 4.4).
//
// It is checked against the complete example of RFC 9458, Appendix A.
package ohttp

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/cloudflare/circl/hpke"
	"github.com/cloudflare/circl/kem"
)

// Media types of RFC 9458.
const (
	ContentTypeKeys     = "application/ohttp-keys"
	ContentTypeRequest  = "message/ohttp-req"
	ContentTypeResponse = "message/ohttp-res"
)

const (
	labelRequest  = "message/bhttp request"
	labelResponse = "message/bhttp response"
)

// Errors returned by the decapsulation functions.
var (
	ErrMalformed        = errors.New("ohttp: malformed message")
	ErrUnknownKey       = errors.New("ohttp: unknown key identifier")
	ErrUnsupportedSuite = errors.New("ohttp: unsupported algorithms")
	ErrDecrypt          = errors.New("ohttp: decryption failed")
)

// SymmetricSuite is a pair of HPKE KDF and AEAD identifiers.
type SymmetricSuite struct {
	KDF  hpke.KDF
	AEAD hpke.AEAD
}

// DefaultSuites are the symmetric algorithms offered by the gateway: AES-128-GCM (fast with AES
// hardware) and ChaCha20-Poly1305 (fast on devices without it), both with HKDF-SHA256.
var DefaultSuites = []SymmetricSuite{
	{hpke.KDF_HKDF_SHA256, hpke.AEAD_AES128GCM},
	{hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305},
}

// DefaultKEM is DHKEM(X25519, HKDF-SHA256).
const DefaultKEM = hpke.KEM_X25519_HKDF_SHA256

// KeyConfig is an Oblivious Gateway key configuration (RFC 9458, Section 3.1).
type KeyConfig struct {
	KeyID     uint8
	KEM       hpke.KEM
	PublicKey []byte
	Suites    []SymmetricSuite
}

// MarshalBinary encodes the key configuration.
func (c KeyConfig) MarshalBinary() []byte {
	b := []byte{c.KeyID}
	b = binary.BigEndian.AppendUint16(b, uint16(c.KEM))
	b = append(b, c.PublicKey...)
	b = binary.BigEndian.AppendUint16(b, uint16(4*len(c.Suites)))
	for _, s := range c.Suites {
		b = binary.BigEndian.AppendUint16(b, uint16(s.KDF))
		b = binary.BigEndian.AppendUint16(b, uint16(s.AEAD))
	}
	return b
}

// ParseKeyConfig decodes a single key configuration. Only KEMs supported by circl are
// accepted; unknown KDF/AEAD pairs are kept so that a client can skip them.
func ParseKeyConfig(b []byte) (KeyConfig, error) {
	var c KeyConfig
	if len(b) < 3 {
		return c, fmt.Errorf("%w: key config too short", ErrMalformed)
	}
	c.KeyID = b[0]
	c.KEM = hpke.KEM(binary.BigEndian.Uint16(b[1:3]))
	if !c.KEM.IsValid() {
		return c, fmt.Errorf("%w: KEM %#04x", ErrUnsupportedSuite, uint16(c.KEM))
	}
	npk := c.KEM.Scheme().PublicKeySize()
	rest := b[3:]
	if len(rest) < npk+2 {
		return c, fmt.Errorf("%w: key config too short", ErrMalformed)
	}
	c.PublicKey = append([]byte(nil), rest[:npk]...)
	if _, err := c.KEM.Scheme().UnmarshalBinaryPublicKey(c.PublicKey); err != nil {
		return c, fmt.Errorf("%w: invalid public key", ErrMalformed)
	}
	rest = rest[npk:]
	l := int(binary.BigEndian.Uint16(rest))
	rest = rest[2:]
	if l == 0 || l%4 != 0 || l != len(rest) {
		return c, fmt.Errorf("%w: invalid symmetric algorithms length", ErrMalformed)
	}
	for i := 0; i < l; i += 4 {
		c.Suites = append(c.Suites, SymmetricSuite{
			KDF:  hpke.KDF(binary.BigEndian.Uint16(rest[i:])),
			AEAD: hpke.AEAD(binary.BigEndian.Uint16(rest[i+2:])),
		})
	}
	return c, nil
}

// MarshalKeyConfigs encodes a list of key configurations as application/ohttp-keys.
func MarshalKeyConfigs(cs []KeyConfig) []byte {
	var b []byte
	for _, c := range cs {
		e := c.MarshalBinary()
		b = binary.BigEndian.AppendUint16(b, uint16(len(e)))
		b = append(b, e...)
	}
	return b
}

// ParseKeyConfigs decodes application/ohttp-keys. Following RFC 9458, Section 3.2, the whole
// collection is rejected if any part of it is malformed.
func ParseKeyConfigs(b []byte) ([]KeyConfig, error) {
	var res []KeyConfig
	for len(b) > 0 {
		if len(b) < 2 {
			return nil, fmt.Errorf("%w: truncated key config list", ErrMalformed)
		}
		l := int(binary.BigEndian.Uint16(b))
		if l > len(b)-2 {
			return nil, fmt.Errorf("%w: truncated key config list", ErrMalformed)
		}
		c, err := ParseKeyConfig(b[2 : 2+l])
		b = b[2+l:]
		if errors.Is(err, ErrUnsupportedSuite) {
			continue // a KEM this client does not implement; the length prefix lets us skip it
		}
		if err != nil {
			return nil, err
		}
		res = append(res, c)
	}
	if len(res) == 0 {
		return nil, fmt.Errorf("%w: empty key config list", ErrMalformed)
	}
	return res, nil
}

// SelectSuite returns the first symmetric suite of the configuration that this implementation
// supports.
func (c KeyConfig) SelectSuite() (SymmetricSuite, error) {
	for _, s := range c.Suites {
		if s.KDF.IsValid() && s.AEAD.IsValid() {
			return s, nil
		}
	}
	return SymmetricSuite{}, ErrUnsupportedSuite
}

func (c KeyConfig) supports(s SymmetricSuite) bool {
	for _, x := range c.Suites {
		if x == s {
			return true
		}
	}
	return false
}

// header builds the request header hdr = key_id || kem_id || kdf_id || aead_id.
func header(keyID uint8, k hpke.KEM, s SymmetricSuite) []byte {
	h := []byte{keyID}
	h = binary.BigEndian.AppendUint16(h, uint16(k))
	h = binary.BigEndian.AppendUint16(h, uint16(s.KDF))
	return binary.BigEndian.AppendUint16(h, uint16(s.AEAD))
}

func requestInfo(hdr []byte) []byte {
	info := append([]byte(labelRequest), 0)
	return append(info, hdr...)
}

// PrivateKey is a gateway key: a key configuration together with the KEM private key.
type PrivateKey struct {
	Config KeyConfig
	sk     kem.PrivateKey
}

// NewPrivateKey derives a key pair deterministically from seed (RFC 9180 DeriveKeyPair).
func NewPrivateKey(keyID uint8, k hpke.KEM, seed []byte, suites []SymmetricSuite) (*PrivateKey, error) {
	if !k.IsValid() {
		return nil, ErrUnsupportedSuite
	}
	if len(seed) < k.Scheme().SeedSize() {
		return nil, fmt.Errorf("ohttp: seed must have at least %d bytes", k.Scheme().SeedSize())
	}
	pk, sk := k.Scheme().DeriveKeyPair(seed)
	return newPrivateKey(keyID, k, pk, sk, suites)
}

// PrivateKeyFromBytes creates a gateway key from a serialized KEM private key.
func PrivateKeyFromBytes(keyID uint8, k hpke.KEM, raw []byte, suites []SymmetricSuite) (*PrivateKey, error) {
	if !k.IsValid() {
		return nil, ErrUnsupportedSuite
	}
	sk, err := k.Scheme().UnmarshalBinaryPrivateKey(raw)
	if err != nil {
		return nil, err
	}
	return newPrivateKey(keyID, k, sk.Public(), sk, suites)
}

func newPrivateKey(keyID uint8, k hpke.KEM, pk kem.PublicKey, sk kem.PrivateKey, suites []SymmetricSuite) (*PrivateKey, error) {
	pub, err := pk.MarshalBinary()
	if err != nil {
		return nil, err
	}
	return &PrivateKey{
		Config: KeyConfig{KeyID: keyID, KEM: k, PublicKey: pub, Suites: append([]SymmetricSuite(nil), suites...)},
		sk:     sk,
	}, nil
}

// KeyLookup returns the gateway key for a key identifier.
type KeyLookup func(keyID uint8) (*PrivateKey, bool)

// RequestHeader is the cleartext header of an encapsulated request.
type RequestHeader struct {
	KeyID uint8
	KEM   hpke.KEM
	Suite SymmetricSuite
}

// ParseRequestHeader decodes the cleartext header of an encapsulated request without
// decrypting it.
func ParseRequestHeader(encReq []byte) (RequestHeader, error) {
	if len(encReq) < 7 {
		return RequestHeader{}, fmt.Errorf("%w: request too short", ErrMalformed)
	}
	return RequestHeader{
		KeyID: encReq[0],
		KEM:   hpke.KEM(binary.BigEndian.Uint16(encReq[1:3])),
		Suite: SymmetricSuite{
			KDF:  hpke.KDF(binary.BigEndian.Uint16(encReq[3:5])),
			AEAD: hpke.AEAD(binary.BigEndian.Uint16(encReq[5:7])),
		},
	}, nil
}

// ServerContext is the gateway state needed to encapsulate the response to one request.
type ServerContext struct {
	enc    []byte
	opener hpke.Opener
	suite  SymmetricSuite
}

// Enc returns the encapsulated KEM key of the request. It is unique per request and serves as
// replay identifier (RFC 9458, Section 6.5).
func (s *ServerContext) Enc() []byte { return s.enc }

// DecapsulateRequest decrypts an encapsulated request (RFC 9458, Section 4.3).
func DecapsulateRequest(encReq []byte, keys KeyLookup) ([]byte, *ServerContext, error) {
	h, err := ParseRequestHeader(encReq)
	if err != nil {
		return nil, nil, err
	}
	key, ok := keys(h.KeyID)
	if !ok {
		return nil, nil, ErrUnknownKey
	}
	if h.KEM != key.Config.KEM || !key.Config.supports(h.Suite) || !h.Suite.KDF.IsValid() || !h.Suite.AEAD.IsValid() {
		return nil, nil, ErrUnsupportedSuite
	}
	nenc := h.KEM.Scheme().CiphertextSize()
	rest := encReq[7:]
	if len(rest) < nenc+int(h.Suite.AEAD.CipherLen(0)) {
		return nil, nil, fmt.Errorf("%w: request too short", ErrMalformed)
	}
	enc, ct := rest[:nenc], rest[nenc:]
	suite := hpke.NewSuite(h.KEM, h.Suite.KDF, h.Suite.AEAD)
	recv, err := suite.NewReceiver(key.sk, requestInfo(encReq[:7]))
	if err != nil {
		return nil, nil, fmt.Errorf("%w: %v", ErrDecrypt, err)
	}
	opener, err := recv.Setup(enc)
	if err != nil {
		return nil, nil, fmt.Errorf("%w: %v", ErrDecrypt, err)
	}
	pt, err := opener.Open(ct, nil)
	if err != nil {
		return nil, nil, ErrDecrypt
	}
	return pt, &ServerContext{enc: append([]byte(nil), enc...), opener: opener, suite: h.Suite}, nil
}

// responseKeys derives the AEAD key and nonce of an encapsulated response.
func responseKeys(ctx hpke.Context, s SymmetricSuite, enc, nonce []byte) (key, aeadNonce []byte) {
	nk := s.AEAD.KeySize()
	secret := ctx.Export([]byte(labelResponse), nk)
	salt := append(append([]byte(nil), enc...), nonce...)
	prk := s.KDF.Extract(secret, salt)
	return s.KDF.Expand(prk, []byte("key"), nk), s.KDF.Expand(prk, []byte("nonce"), s.AEAD.NonceSize())
}

func responseNonceLen(s SymmetricSuite) int {
	return int(max(s.AEAD.KeySize(), s.AEAD.NonceSize()))
}

// EncapsulateResponse encrypts a response (RFC 9458, Section 4.4). rnd supplies the response
// nonce and must be crypto/rand.Reader outside of tests.
func (s *ServerContext) EncapsulateResponse(resp []byte, rnd io.Reader) ([]byte, error) {
	nonce := make([]byte, responseNonceLen(s.suite))
	if _, err := io.ReadFull(rnd, nonce); err != nil {
		return nil, err
	}
	key, aeadNonce := responseKeys(s.opener, s.suite, s.enc, nonce)
	aead, err := s.suite.AEAD.New(key)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nonce, aeadNonce, resp, nil), nil
}

// ClientContext is the client state needed to decrypt the response to one request.
type ClientContext struct {
	enc    []byte
	sealer hpke.Sealer
	suite  SymmetricSuite
}

// EncapsulateRequest encrypts a binary HTTP request for the gateway key configuration cfg. rnd
// supplies the ephemeral KEM key and must be crypto/rand.Reader outside of tests.
func EncapsulateRequest(cfg KeyConfig, req []byte, rnd io.Reader) ([]byte, *ClientContext, error) {
	s, err := cfg.SelectSuite()
	if err != nil {
		return nil, nil, err
	}
	pk, err := cfg.KEM.Scheme().UnmarshalBinaryPublicKey(cfg.PublicKey)
	if err != nil {
		return nil, nil, fmt.Errorf("%w: invalid public key", ErrMalformed)
	}
	hdr := header(cfg.KeyID, cfg.KEM, s)
	sender, err := hpke.NewSuite(cfg.KEM, s.KDF, s.AEAD).NewSender(pk, requestInfo(hdr))
	if err != nil {
		return nil, nil, err
	}
	enc, sealer, err := sender.Setup(rnd)
	if err != nil {
		return nil, nil, err
	}
	ct, err := sealer.Seal(req, nil)
	if err != nil {
		return nil, nil, err
	}
	out := append(append(hdr, enc...), ct...)
	return out, &ClientContext{enc: enc, sealer: sealer, suite: s}, nil
}

// DecapsulateResponse decrypts an encapsulated response.
func (c *ClientContext) DecapsulateResponse(encRes []byte) ([]byte, error) {
	n := responseNonceLen(c.suite)
	if len(encRes) < n+int(c.suite.AEAD.CipherLen(0)) {
		return nil, fmt.Errorf("%w: response too short", ErrMalformed)
	}
	key, aeadNonce := responseKeys(c.sealer, c.suite, c.enc, encRes[:n])
	aead, err := c.suite.AEAD.New(key)
	if err != nil {
		return nil, err
	}
	pt, err := aead.Open(nil, aeadNonce, encRes[n:], nil)
	if err != nil {
		return nil, ErrDecrypt
	}
	return pt, nil
}
