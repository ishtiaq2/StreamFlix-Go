// device-gateway simulates a fleet of IoT/streaming devices, each sending
// telemetry concurrently. It fans events into a bounded worker pool that
// forwards them to a broker (Redis Pub/Sub here — the same *shape* as GCP
// Pub/Sub: publisher, channel/topic, decoupled subscriber) and exposes an
// HTTP status endpoint so the rest of StreamFlix-7 can see device health.
//
// Concepts on display, each one an interview-relevant Go concurrency pattern:
//   - goroutines            : one lightweight "thread" per simulated device
//   - channels              : a thread-safe queue connecting producers/consumers
//   - worker pool           : a fixed number of goroutines draining one channel
//   - context.Context       : cooperative cancellation / graceful shutdown
//   - sync.WaitGroup        : waiting for a dynamic set of goroutines to finish
//   - sync.RWMutex          : protecting shared state (the device registry)
//     read by the HTTP handler and written by the workers concurrently
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"
)

// ---------- domain types ----------

// TelemetryEvent is what a simulated device "sends" every tick.
type TelemetryEvent struct {
	DeviceID  string    `json:"deviceId"`
	Kind      string    `json:"kind"`
	Value     float64   `json:"value"`
	Timestamp time.Time `json:"timestamp"`
}

// DeviceStatus is what /devices/status reports back — a snapshot, not a
// live stream, which is why it needs a mutex: HTTP handlers and worker
// goroutines touch it from different goroutines at the same time.
type DeviceStatus struct {
	DeviceID   string    `json:"deviceId"`
	LastSeen   time.Time `json:"lastSeen"`
	EventCount int       `json:"eventCount"`
}

// Registry is the shared state. sync.RWMutex lets many readers (HTTP
// requests) proceed in parallel, but a writer (a worker recording a new
// event) gets exclusive access.
type Registry struct {
	mu      sync.RWMutex
	devices map[string]*DeviceStatus
}

func NewRegistry() *Registry {
	return &Registry{devices: make(map[string]*DeviceStatus)}
}

func (r *Registry) Touch(deviceID string, at time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.devices[deviceID]
	if !ok {
		d = &DeviceStatus{DeviceID: deviceID}
		r.devices[deviceID] = d
	}
	d.LastSeen = at
	d.EventCount++
}

func (r *Registry) Snapshot() []DeviceStatus {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]DeviceStatus, 0, len(r.devices))
	for _, d := range r.devices {
		out = append(out, *d)
	}
	return out
}

// ---------- producers: simulated devices ----------

// simulateDevice is one goroutine per device. It's cheap — Go goroutines
// start around a few KB of stack, which is why "one goroutine per device"
// scales to thousands in a way "one OS thread per device" would not.
func simulateDevice(ctx context.Context, id string, interval time.Duration, out chan<- TelemetryEvent, wg *sync.WaitGroup) {
	defer wg.Done()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			// Cooperative shutdown: we don't kill the goroutine from the
			// outside, we ask it to stop via ctx and it exits on its own.
			return
		case t := <-ticker.C:
			event := TelemetryEvent{
				DeviceID:  id,
				Kind:      "heartbeat",
				Value:     rand.Float64() * 100,
				Timestamp: t,
			}
			select {
			case out <- event:
				// delivered to the worker pool
			case <-ctx.Done():
				return
			}
		}
	}
}

// ---------- consumers: a bounded worker pool ----------

// worker drains the shared events channel until it's closed. Using a fixed
// pool of workers (instead of a goroutine per event) caps how much work can
// run concurrently — the same reasoning behind connection pools and
// thread pools in any backend stack.
func worker(id int, in <-chan TelemetryEvent, registry *Registry, redisAddr string, wg *sync.WaitGroup) {
	defer wg.Done()
	for event := range in {
		registry.Touch(event.DeviceID, event.Timestamp)

		payload, err := json.Marshal(event)
		if err != nil {
			log.Printf("worker %d: marshal error: %v", id, err)
			continue
		}
		if err := publishToRedis(redisAddr, "device-events", string(payload)); err != nil {
			// In a real system this is exactly what would trip a chaos-agent
			// alarm — log and keep going rather than crash the worker.
			log.Printf("worker %d: publish failed: %v", id, err)
			continue
		}
	}
}

// publishToRedis speaks just enough RESP (REdis Serialization Protocol) to
// issue a PUBLISH command over a raw TCP connection. Deliberately written
// by hand instead of pulling in a client library: PUBLISH really is this
// simple on the wire, and it's a small, honest example of "networking and
// communication between systems" — a line straight from the job posting.
func publishToRedis(addr, channel, message string) error {
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return fmt.Errorf("dial redis: %w", err)
	}
	defer conn.Close()

	// RESP array: ["PUBLISH", "<channel>", "<message>"]
	cmd := respArray("PUBLISH", channel, message)
	if _, err := conn.Write(cmd); err != nil {
		return fmt.Errorf("write publish: %w", err)
	}

	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		return fmt.Errorf("read reply: %w", err)
	}
	// A successful PUBLISH reply looks like ":<n>\r\n" — n subscribers received it.
	if len(line) == 0 || line[0] != ':' {
		return fmt.Errorf("unexpected redis reply: %q", line)
	}
	return nil
}

func respArray(parts ...string) []byte {
	out := fmt.Sprintf("*%d\r\n", len(parts))
	for _, p := range parts {
		out += fmt.Sprintf("$%d\r\n%s\r\n", len(p), p)
	}
	return []byte(out)
}

// ---------- HTTP surface ----------

func statusHandler(registry *Registry) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(registry.Snapshot())
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

func envIntOr(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}

func main() {
	numDevices := envIntOr("NUM_DEVICES", 12)
	numWorkers := envIntOr("NUM_WORKERS", 4)
	redisAddr := envOr("REDIS_ADDR", "redis:6379")
	httpAddr := envOr("HTTP_ADDR", ":8082")
	interval := time.Duration(envIntOr("EVENT_INTERVAL_MS", 2000)) * time.Millisecond

	// signal.NotifyContext ties the context's cancellation to SIGINT/SIGTERM,
	// so "podman stop" triggers the exact same graceful-shutdown path as
	// pressing Ctrl+C locally.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	registry := NewRegistry()
	events := make(chan TelemetryEvent, 100) // buffered: absorbs bursts without blocking producers

	var producers sync.WaitGroup
	for i := 0; i < numDevices; i++ {
		producers.Add(1)
		go simulateDevice(ctx, fmt.Sprintf("device-%03d", i), interval, events, &producers)
	}

	var workers sync.WaitGroup
	for i := 0; i < numWorkers; i++ {
		workers.Add(1)
		go worker(i, events, registry, redisAddr, &workers)
	}

	// Closer goroutine: only close(events) once every producer has stopped
	// sending. Closing a channel while something might still write to it is
	// a classic Go bug — this pattern avoids it.
	go func() {
		producers.Wait()
		close(events)
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("/devices/status", statusHandler(registry))
	mux.HandleFunc("/healthz", healthHandler)
	server := &http.Server{Addr: httpAddr, Handler: mux}

	go func() {
		log.Printf("device-gateway listening on %s (%d devices, %d workers, redis=%s)",
			httpAddr, numDevices, numWorkers, redisAddr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http server error: %v", err)
		}
	}()

	<-ctx.Done()
	log.Println("shutdown signal received, draining...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	server.Shutdown(shutdownCtx)

	workers.Wait()
	log.Println("device-gateway stopped cleanly")
}