// auth-service is a small identity, authorization, and roles/permissions
// backend: it authenticates a user, issues a signed token, and protects
// admin-only endpoints with role-based access control. It's designed the
// same way you'd start a real Go identity service: pure logic (internal/auth)
// separated from HTTP wiring (this file) separated from storage (the
// UserStore interface below).
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"streamflix/auth-service/internal/auth"
)

// User is what this service knows about an identity. In a real deployment,
// fields like PasswordHash would likely live behind a proper credential
// provider rather than this table at all -- kept simple here to keep the
// login flow self-contained and easy to follow.
type User struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	PasswordHash string `json:"-"`
	Role         string `json:"role"`
}

// UserStore is the seam between HTTP handlers and wherever identities
// actually live. Every handler below only ever talks to this interface --
// never to a map or a database directly. That's what makes the storage
// decision in ADR-0001 (RDS vs. DynamoDB) a swap-in-a-new-struct decision
// later, not a rewrite-every-handler decision.
type UserStore interface {
	GetByEmail(email string) (*User, bool)
	Create(u User) error
	List() []User
}

// InMemoryStore is today's implementation: a mutex-guarded map. Good enough
// for local development and for this stack's docker-less runtime; the
// natural next step (see ADR-0001) is a DynamoStore or PostgresStore that
// satisfies the exact same interface.
type InMemoryStore struct {
	mu    sync.RWMutex
	users map[string]User // keyed by email
}

func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{users: make(map[string]User)}
}

func (s *InMemoryStore) GetByEmail(email string) (*User, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	u, ok := s.users[email]
	if !ok {
		return nil, false
	}
	return &u, true
}

func (s *InMemoryStore) Create(u User) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[u.Email] = u
	return nil
}

func (s *InMemoryStore) List() []User {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]User, 0, len(s.users))
	for _, u := range s.users {
		out = append(out, u)
	}
	return out
}

func hashPassword(pw string) string {
	// SHA-256 alone, no salt, no work factor -- fine for this learning
	// exercise, NOT how you'd hash passwords in production. Use bcrypt or
	// argon2 there; say so out loud if this comes up in an interview, it's
	// a legitimate "what would you change for production" question.
	sum := sha256.Sum256([]byte(pw))
	return hex.EncodeToString(sum[:])
}

// ---------- request/response payloads ----------

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token string `json:"token"`
}

type createUserRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Role     string `json:"role"`
}

// ---------- handlers ----------

func loginHandler(store UserStore, secret []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req loginRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		user, ok := store.GetByEmail(req.Email)
		if !ok || user.PasswordHash != hashPassword(req.Password) {
			// Same error for "no such user" and "wrong password" -- don't
			// let the response reveal which one it was.
			http.Error(w, "invalid credentials", http.StatusUnauthorized)
			return
		}

		token, err := auth.NewToken(secret, user.ID, user.Role, time.Hour)
		if err != nil {
			http.Error(w, "could not issue token", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(loginResponse{Token: token})
	}
}

func meHandler(w http.ResponseWriter, r *http.Request) {
	claims, ok := auth.ClaimsFromContext(r.Context())
	if !ok {
		http.Error(w, "no identity on request", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(claims)
}

func listUsersHandler(store UserStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(store.List())
	}
}

func createUserHandler(store UserStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req createUserRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		if req.Role == "" {
			req.Role = "viewer"
		}

		user := User{
			ID:           req.Email, // fine for a demo; a real store would generate a UUID
			Email:        req.Email,
			PasswordHash: hashPassword(req.Password),
			Role:         req.Role,
		}
		if err := store.Create(user); err != nil {
			http.Error(w, "could not create user", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"id": user.ID, "role": user.Role})
	}
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

// ---------- wiring ----------

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	secret := []byte(envOr("AUTH_SECRET", "dev-secret-change-me"))
	addr := envOr("HTTP_ADDR", ":8083")

	store := NewInMemoryStore()
	// Seed one admin so there's something to log in as on first boot.
	store.Create(User{
		ID:           "admin-seed",
		Email:        "admin@streamflix.local",
		PasswordHash: hashPassword("changeme"),
		Role:         "admin",
	})

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", healthHandler)
	mux.HandleFunc("/login", loginHandler(store, secret))
	mux.Handle("/me", auth.RequireAuth(secret)(http.HandlerFunc(meHandler)))
	mux.Handle("/admin/users", methodSwitch(map[string]http.Handler{
		http.MethodGet:  auth.RequirePermission(secret, auth.PermUsersRead)(listUsersHandler(store)),
		http.MethodPost: auth.RequirePermission(secret, auth.PermUsersWrite)(createUserHandler(store)),
	}))

	log.Printf("auth-service listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("http server error: %v", err)
	}
}

// methodSwitch routes a single path to different handlers based on HTTP
// method -- net/http's ServeMux doesn't do this natively pre-1.22 routing
// patterns, and being explicit about it here keeps the example portable.
func methodSwitch(handlers map[string]http.Handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h, ok := handlers[r.Method]
		if !ok {
			w.Header().Set("Allow", "GET, POST")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		h.ServeHTTP(w, r)
	}
}
