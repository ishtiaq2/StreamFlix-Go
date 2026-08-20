// Package auth implements identity tokens and role-based access control.
//
// Tokens here are deliberately built by hand instead of pulling in a JWT
// library. The format is the same shape as a real JWT --
// base64url(header) + "." + base64url(payload) + "." + base64url(signature) --
// because the goal is to actually understand what "JWT" means before reaching
// for a library that hides it. In production, swap this for a maintained JWT
// library; the concepts (claims, expiry, HMAC signing) carry over exactly.
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// Claims is what's inside the token: who this is, what role they hold, and
// when it expires. A real identity system carries more (issuer, audience,
// token ID for revocation) -- this is the minimum that makes RBAC work.
type Claims struct {
	Subject   string `json:"sub"`
	Role      string `json:"role"`
	ExpiresAt int64  `json:"exp"`
}

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrExpiredToken = errors.New("token expired")
)

// NewToken issues a signed token for the given subject/role, valid for ttl.
func NewToken(secret []byte, subject, role string, ttl time.Duration) (string, error) {
	header := base64URLEncode([]byte(`{"alg":"HS256","typ":"AUTH"}`))

	claims := Claims{
		Subject:   subject,
		Role:      role,
		ExpiresAt: time.Now().Add(ttl).Unix(),
	}
	claimsJSON, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	payload := base64URLEncode(claimsJSON)

	signingInput := header + "." + payload
	signature := sign(secret, signingInput)

	return signingInput + "." + signature, nil
}

// ParseToken verifies the signature and expiry, and returns the claims if valid.
func ParseToken(secret []byte, token string) (*Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, ErrInvalidToken
	}

	signingInput := parts[0] + "." + parts[1]
	expectedSig := sign(secret, signingInput)

	// hmac.Equal (not ==) is deliberate: it runs in constant time, so an
	// attacker can't learn anything about the correct signature by measuring
	// how long a byte-by-byte comparison takes to fail. Using == here is a
	// real, subtle vulnerability class ("timing attack") worth being able to
	// name in an interview.
	if !hmac.Equal([]byte(expectedSig), []byte(parts[2])) {
		return nil, ErrInvalidToken
	}

	payloadBytes, err := base64URLDecode(parts[1])
	if err != nil {
		return nil, ErrInvalidToken
	}
	var claims Claims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return nil, ErrInvalidToken
	}

	if time.Now().Unix() > claims.ExpiresAt {
		return nil, ErrExpiredToken
	}
	return &claims, nil
}

func sign(secret []byte, data string) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(data))
	return base64URLEncode(mac.Sum(nil))
}

func base64URLEncode(b []byte) string       { return base64.RawURLEncoding.EncodeToString(b) }
func base64URLDecode(s string) ([]byte, error) { return base64.RawURLEncoding.DecodeString(s) }
