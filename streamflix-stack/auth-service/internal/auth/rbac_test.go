package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHasPermission(t *testing.T) {
	cases := []struct {
		name string
		role string
		perm Permission
		want bool
	}{
		{"admin can write users", "admin", PermUsersWrite, true},
		{"viewer cannot write users", "viewer", PermUsersWrite, false},
		{"operator can write devices", "operator", PermDevicesWrite, true},
		{"operator cannot admin devices", "operator", PermDevicesAdmin, false},
		{"unknown role has no permissions", "unknown-role", PermDevicesRead, false},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := HasPermission(c.role, c.perm)
			if got != c.want {
				t.Errorf("HasPermission(%q, %q) = %v, want %v", c.role, c.perm, got, c.want)
			}
		})
	}
}

func TestRequirePermission_Middleware(t *testing.T) {
	secret := []byte("test-secret")
	handlerCalled := false
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlerCalled = true
		w.WriteHeader(http.StatusOK)
	})
	protected := RequirePermission(secret, PermDevicesAdmin)(next)

	t.Run("missing token returns 401", func(t *testing.T) {
		handlerCalled = false
		req := httptest.NewRequest(http.MethodGet, "/admin", nil)
		rec := httptest.NewRecorder()

		protected.ServeHTTP(rec, req)

		if rec.Code != http.StatusUnauthorized {
			t.Errorf("expected status %d, got %d", http.StatusUnauthorized, rec.Code)
		}
		if handlerCalled {
			t.Error("protected handler should not have been called")
		}
	})

	t.Run("valid token, wrong role returns 403", func(t *testing.T) {
		handlerCalled = false
		tok, _ := NewToken(secret, "user-1", "viewer", time.Minute)
		req := httptest.NewRequest(http.MethodGet, "/admin", nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		rec := httptest.NewRecorder()

		protected.ServeHTTP(rec, req)

		if rec.Code != http.StatusForbidden {
			t.Errorf("expected status %d, got %d", http.StatusForbidden, rec.Code)
		}
		if handlerCalled {
			t.Error("protected handler should not have been called")
		}
	})

	t.Run("valid token, correct role is allowed through", func(t *testing.T) {
		handlerCalled = false
		tok, _ := NewToken(secret, "user-1", "admin", time.Minute)
		req := httptest.NewRequest(http.MethodGet, "/admin", nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		rec := httptest.NewRecorder()

		protected.ServeHTTP(rec, req)

		if rec.Code != http.StatusOK {
			t.Errorf("expected status %d, got %d", http.StatusOK, rec.Code)
		}
		if !handlerCalled {
			t.Error("protected handler should have been called")
		}
	})
}
