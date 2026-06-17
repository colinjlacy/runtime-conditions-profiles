//go:build linux

package profiler

import (
	"fmt"
	"net/netip"
	"sync"
	"time"
)

// ConnKey uniquely identifies a connection by its socket tuple.
type ConnKey struct {
	LocalIP    netip.Addr
	LocalPort  uint16
	RemoteIP   netip.Addr
	RemotePort uint16
}

func (k ConnKey) String() string {
	return fmt.Sprintf("%s:%d->%s:%d", k.LocalIP, k.LocalPort, k.RemoteIP, k.RemotePort)
}

// ConnState tracks the classification state of a connection.
type ConnState struct {
	Key            ConnKey
	PID            uint32
	FirstSeenAt    time.Time
	Classified     bool
	Classification DetectionResult
	FirstBytes     []byte // Store first bytes for classification
}

// ConnTracker maintains state for active connections to enable
// first-payload-based protocol classification.
type ConnTracker struct {
	mu    sync.RWMutex
	conns map[ConnKey]*ConnState

	// TTL for connection state (cleanup stale entries)
	ttl time.Duration
}

// NewConnTracker creates a new connection tracker.
func NewConnTracker(ttl time.Duration) *ConnTracker {
	ct := &ConnTracker{
		conns: make(map[ConnKey]*ConnState),
		ttl:   ttl,
	}
	// Start background cleanup goroutine
	go ct.cleanupLoop()
	return ct
}

// GetOrCreate returns existing connection state or creates new entry.
// Returns the state and whether this is a new connection.
func (ct *ConnTracker) GetOrCreate(key ConnKey, pid uint32) (*ConnState, bool) {
	ct.mu.Lock()
	defer ct.mu.Unlock()

	if state, exists := ct.conns[key]; exists {
		return state, false
	}

	state := &ConnState{
		Key:         key,
		PID:         pid,
		FirstSeenAt: time.Now(),
		Classified:  false,
	}
	ct.conns[key] = state
	return state, true
}

// Get returns connection state if it exists.
func (ct *ConnTracker) Get(key ConnKey) *ConnState {
	ct.mu.RLock()
	defer ct.mu.RUnlock()
	return ct.conns[key]
}

// Classify attempts to classify a connection based on payload.
// Returns the detection result and whether this is the first classification.
func (ct *ConnTracker) Classify(key ConnKey, pid uint32, meta ConnMeta, payload []byte) (DetectionResult, bool) {
	ct.mu.Lock()
	defer ct.mu.Unlock()

	state, exists := ct.conns[key]
	if !exists {
		state = &ConnState{
			Key:         key,
			PID:         pid,
			FirstSeenAt: time.Now(),
			Classified:  false,
		}
		ct.conns[key] = state
	}

	// If already classified, return cached result
	if state.Classified {
		return state.Classification, false
	}

	// Store first bytes if we don't have them yet
	if len(state.FirstBytes) == 0 && len(payload) > 0 {
		// Store up to 256 bytes for classification
		maxBytes := 256
		if len(payload) < maxBytes {
			maxBytes = len(payload)
		}
		state.FirstBytes = make([]byte, maxBytes)
		copy(state.FirstBytes, payload[:maxBytes])
	}

	// Attempt classification
	result := DetectConnection(meta, state.FirstBytes)

	// Only mark as classified if we got a definitive result
	if result.Matched && result.Confidence >= ConfidenceMedium {
		state.Classified = true
		state.Classification = result
		return result, true
	}

	// Not yet classified - return the result but don't cache it
	return result, false
}

// Remove removes a connection from tracking.
func (ct *ConnTracker) Remove(key ConnKey) {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	delete(ct.conns, key)
}

// cleanupLoop periodically removes stale connections.
func (ct *ConnTracker) cleanupLoop() {
	ticker := time.NewTicker(ct.ttl / 2)
	defer ticker.Stop()

	for range ticker.C {
		ct.cleanup()
	}
}

func (ct *ConnTracker) cleanup() {
	ct.mu.Lock()
	defer ct.mu.Unlock()

	now := time.Now()
	for key, state := range ct.conns {
		if now.Sub(state.FirstSeenAt) > ct.ttl {
			delete(ct.conns, key)
		}
	}
}

// Stats returns current tracker statistics.
func (ct *ConnTracker) Stats() (total, classified int) {
	ct.mu.RLock()
	defer ct.mu.RUnlock()

	total = len(ct.conns)
	for _, state := range ct.conns {
		if state.Classified {
			classified++
		}
	}
	return
}
