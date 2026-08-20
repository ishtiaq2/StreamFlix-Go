package auth

import (
	"context"
	"net/http"
	"strings"
)

type contextKey string

const claimsContextKey contextKey = "claims"

// RequireAuth checks for a valid token and attaches its claims to the
// request context, but does not check any specific permission. Use this for
// endpoints any authenticated identity may call (e.g. "who am I").
func RequireAuth(secret []byte) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, err := claimsFromRequest(secret, r)
			if err != nil {
				http.Error(w, err.Error(), http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), claimsContextKey, claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// RequirePermission wraps a handler so it only runs if the caller presents a
// valid token AND that token's role carries the given permission. This is
// the pattern used to protect the admin-only endpoints in cmd/authsvc.
func RequirePermission(secret []byte, perm Permission) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, err := claimsFromRequest(secret, r)
			if err != nil {
				http.Error(w, err.Error(), http.StatusUnauthorized)
				return
			}
			if !HasPermission(claims.Role, perm) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			ctx := context.WithValue(r.Context(), claimsContextKey, claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func claimsFromRequest(secret []byte, r *http.Request) (*Claims, error) {
	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		return nil, ErrInvalidToken
	}
	tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
	return ParseToken(secret, tokenStr)
}

// ClaimsFromContext lets a handler downstream of RequireAuth/RequirePermission
// read who the caller is without re-parsing the token itself.
func ClaimsFromContext(ctx context.Context) (*Claims, bool) {
	claims, ok := ctx.Value(claimsContextKey).(*Claims)
	return claims, ok
}
