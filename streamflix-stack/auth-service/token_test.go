package auth

import (
	"testing"
	"time"
)

func TestNewAndParseToken(t *testing.T) {
	secret := []byte("test-secret")

	tok, err := NewToken(secret, "user-1", "admin", time.Minute)
	if err != nil {
		t.Fatalf("NewToken returned error: %v", err)
	}

	claims, err := ParseToken(secret, tok)
	if err != nil {
		t.Fatalf("ParseToken returned error: %v", err)
	}
	if claims.Subject != "user-1" {
		t.Errorf("expected subject %q, got %q", "user-1", claims.Subject)
	}
	if claims.Role != "admin" {
		t.Errorf("expected role %q, got %q", "admin", claims.Role)
	}
}

func TestParseToken_TamperedSignatureRejected(t *testing.T) {
	secret := []byte("test-secret")
	tok, err := NewToken(secret, "user-1", "admin", time.Minute)
	if err != nil {
		t.Fatalf("NewToken returned error: %v", err)
	}

	// Flip the last two characters of the signature -- simulates someone
	// tampering with the token in transit.
	tampered := tok[:len(tok)-2] + "xx"

	if _, err := ParseToken(secret, tampered); err == nil {
		t.Fatal("expected an error for a tampered token, got nil")
	}
}

func TestParseToken_ExpiredRejected(t *testing.T) {
	secret := []byte("test-secret")
	// Negative TTL: the token is already expired the moment it's issued.
	tok, err := NewToken(secret, "user-1", "viewer", -time.Minute)
	if err != nil {
		t.Fatalf("NewToken returned error: %v", err)
	}

	_, err = ParseToken(secret, tok)
	if err != ErrExpiredToken {
		t.Fatalf("expected ErrExpiredToken, got %v", err)
	}
}

func TestParseToken_WrongSecretRejected(t *testing.T) {
	tok, err := NewToken([]byte("secret-a"), "user-1", "admin", time.Minute)
	if err != nil {
		t.Fatalf("NewToken returned error: %v", err)
	}

	if _, err := ParseToken([]byte("secret-b"), tok); err == nil {
		t.Fatal("expected an error when parsing with the wrong secret, got nil")
	}
}

func TestParseToken_MalformedRejected(t *testing.T) {
	secret := []byte("test-secret")
	cases := []string{"", "not-a-token", "only.two-parts", "a.b.c.d"}
	for _, tc := range cases {
		if _, err := ParseToken(secret, tc); err == nil {
			t.Errorf("expected an error for malformed token %q, got nil", tc)
		}
	}
}
