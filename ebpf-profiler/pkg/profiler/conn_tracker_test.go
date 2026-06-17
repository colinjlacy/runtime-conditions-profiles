//go:build linux

package profiler

import (
	"encoding/binary"
	"net/netip"
	"testing"
	"time"
)

func TestConnTracker_GetOrCreate(t *testing.T) {
	ct := NewConnTracker(1 * time.Minute)

	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}

	// First call should create
	state1, isNew1 := ct.GetOrCreate(key, 1234)
	if !isNew1 {
		t.Error("expected first call to be new")
	}
	if state1.PID != 1234 {
		t.Errorf("expected PID 1234, got %d", state1.PID)
	}

	// Second call should return existing
	state2, isNew2 := ct.GetOrCreate(key, 5678)
	if isNew2 {
		t.Error("expected second call to return existing")
	}
	if state2.PID != 1234 {
		t.Errorf("expected original PID 1234, got %d", state2.PID)
	}
}

func TestConnTracker_Classify(t *testing.T) {
	ct := NewConnTracker(1 * time.Minute)

	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}

	meta := ConnMeta{
		PID:        1234,
		LocalIP:    key.LocalIP,
		LocalPort:  key.LocalPort,
		RemoteIP:   key.RemoteIP,
		RemotePort: key.RemotePort,
	}

	// Create PostgreSQL startup packet
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000)

	// First classification
	result1, isFirst1 := ct.Classify(key, 1234, meta, payload)
	if !result1.Matched {
		t.Fatal("expected classification to match")
	}
	if result1.Protocol != ProtocolPostgres {
		t.Errorf("expected PostgreSQL, got %s", result1.Protocol)
	}
	if !isFirst1 {
		t.Error("expected first classification to be marked as first")
	}

	// Second classification should return cached result
	result2, isFirst2 := ct.Classify(key, 1234, meta, payload)
	if !result2.Matched {
		t.Fatal("expected cached classification to match")
	}
	if result2.Protocol != ProtocolPostgres {
		t.Errorf("expected cached PostgreSQL, got %s", result2.Protocol)
	}
	if isFirst2 {
		t.Error("expected second classification not to be marked as first")
	}
}

func TestConnTracker_ClassifyStoresFirstBytes(t *testing.T) {
	ct := NewConnTracker(1 * time.Minute)

	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}

	meta := ConnMeta{
		PID:        1234,
		LocalIP:    key.LocalIP,
		LocalPort:  key.LocalPort,
		RemoteIP:   key.RemoteIP,
		RemotePort: key.RemotePort,
	}

	// First call with empty payload (port-only detection)
	result1, _ := ct.Classify(key, 1234, meta, []byte{})
	if result1.Protocol != ProtocolPostgres {
		t.Errorf("expected PostgreSQL from port, got %s", result1.Protocol)
	}
	if result1.Confidence != ConfidenceMedium {
		t.Errorf("expected medium confidence without payload, got %d", result1.Confidence)
	}

	// Note: Once classified with medium confidence, subsequent calls return cached result
	// This is the expected behavior - we don't re-classify
}

func TestConnTracker_Stats(t *testing.T) {
	ct := NewConnTracker(1 * time.Minute)

	// Add some connections
	for i := 0; i < 5; i++ {
		key := ConnKey{
			LocalIP:    netip.MustParseAddr("10.0.0.1"),
			LocalPort:  uint16(45678 + i),
			RemoteIP:   netip.MustParseAddr("10.0.0.2"),
			RemotePort: 5432,
		}
		ct.GetOrCreate(key, uint32(1234+i))
	}

	total, classified := ct.Stats()
	if total != 5 {
		t.Errorf("expected 5 total connections, got %d", total)
	}
	if classified != 0 {
		t.Errorf("expected 0 classified connections, got %d", classified)
	}

	// Classify one
	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}
	meta := ConnMeta{
		PID:        1234,
		LocalIP:    key.LocalIP,
		LocalPort:  key.LocalPort,
		RemoteIP:   key.RemoteIP,
		RemotePort: key.RemotePort,
	}
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000)
	ct.Classify(key, 1234, meta, payload)

	total, classified = ct.Stats()
	if total != 5 {
		t.Errorf("expected 5 total connections, got %d", total)
	}
	if classified != 1 {
		t.Errorf("expected 1 classified connection, got %d", classified)
	}
}

func TestConnTracker_Remove(t *testing.T) {
	ct := NewConnTracker(1 * time.Minute)

	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}

	ct.GetOrCreate(key, 1234)
	total, _ := ct.Stats()
	if total != 1 {
		t.Errorf("expected 1 connection, got %d", total)
	}

	ct.Remove(key)
	total, _ = ct.Stats()
	if total != 0 {
		t.Errorf("expected 0 connections after remove, got %d", total)
	}
}

func TestConnKey_String(t *testing.T) {
	key := ConnKey{
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: 5432,
	}

	expected := "10.0.0.1:45678->10.0.0.2:5432"
	if key.String() != expected {
		t.Errorf("expected %s, got %s", expected, key.String())
	}
}
