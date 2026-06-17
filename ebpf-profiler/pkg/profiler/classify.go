//go:build linux

package profiler

import (
	"bytes"
	"encoding/binary"
	"net/netip"
	"regexp"
	"strings"
)

// Category describes the high-level kind of remote system.
type Category string

const (
	CategoryUnknown    Category = "unknown"
	CategoryDatabase   Category = "database"
	CategoryCache      Category = "cache"
	CategoryMessageBus Category = "message_bus"
	CategoryHTTP       Category = "http"
)

// Protocol is a logical protocol name.
type Protocol string

const (
	ProtocolUnknown   Protocol = "unknown"
	ProtocolPostgres  Protocol = "postgres"
	ProtocolMySQL     Protocol = "mysql"
	ProtocolMongoDB   Protocol = "mongodb"
	ProtocolMSSQL     Protocol = "mssql"
	ProtocolRedis     Protocol = "redis"
	ProtocolMemcached Protocol = "memcached"
	ProtocolKafka     Protocol = "kafka"
	ProtocolAMQP      Protocol = "amqp"
	ProtocolNATS      Protocol = "nats"
	ProtocolHTTP1     Protocol = "http/1.x"
	ProtocolHTTP2     Protocol = "http/2"
)

// Confidence is a simple 0–100 score for how confident the detector is.
type Confidence int

const (
	ConfidenceLow    Confidence = 25
	ConfidenceMedium Confidence = 60
	ConfidenceHigh   Confidence = 90
)

// ConnMeta describes a single connection from the eBPF layer.
type ConnMeta struct {
	PID        uint32
	LocalIP    netip.Addr
	LocalPort  uint16
	RemoteIP   netip.Addr
	RemotePort uint16
	IsTLS      bool // if we can infer this from handshake
}

// DetectionResult is the outcome of a single detector.
type DetectionResult struct {
	Matched    bool
	Protocol   Protocol
	Category   Category
	Confidence Confidence
	Reason     string
}

// Detector is implemented by each protocol-specific detector.
type Detector interface {
	Name() string
	Detect(meta ConnMeta, firstBytes []byte) DetectionResult
}

// Registry of detectors in priority order.
// Databases first, then message buses, then caches, then HTTP.
var detectors = []Detector{
	// Databases
	PostgresDetector{},
	MySQLDetector{},
	MongoDetector{},
	MSSQLDetector{},
	// Message buses
	KafkaDetector{},
	AMQPDetector{},
	NATSDetector{},
	// Caches
	RedisDetector{},
	MemcacheDetector{},
	// HTTP (lowest priority - most common)
	HTTPDetector{},
}

// DetectConnection runs all detectors and returns the best match.
func DetectConnection(meta ConnMeta, firstBytes []byte) DetectionResult {
	var best DetectionResult

	for _, d := range detectors {
		res := d.Detect(meta, firstBytes)
		if !res.Matched {
			continue
		}
		if !best.Matched || res.Confidence > best.Confidence {
			best = res
		}
	}

	if !best.Matched {
		return DetectionResult{
			Matched:    false,
			Protocol:   ProtocolUnknown,
			Category:   CategoryUnknown,
			Confidence: 0,
			Reason:     "no detector matched",
		}
	}

	// If TLS is detected, reduce confidence
	if meta.IsTLS && best.Confidence > ConfidenceMedium {
		best.Confidence = ConfidenceMedium
		best.Reason = best.Reason + " (TLS - reduced confidence)"
	}

	return best
}

// =============================================================================
// Database Detectors
// =============================================================================

// PostgresDetector uses port 5432 plus the startup/SSLRequest header.
type PostgresDetector struct{}

func (PostgresDetector) Name() string { return "postgres" }

func (PostgresDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 5432 {
		return DetectionResult{}
	}
	if len(b) < 8 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolPostgres,
			Category:   CategoryDatabase,
			Confidence: ConfidenceMedium,
			Reason:     "port 5432, insufficient bytes to confirm",
		}
	}

	length := binary.BigEndian.Uint32(b[0:4])
	version := binary.BigEndian.Uint32(b[4:8])

	// Basic sanity checks
	if length < 8 || length > 10*1024*1024 {
		return DetectionResult{}
	}

	// Common Postgres startup versions (e.g., 3.0) or SSLRequest magic.
	if version == 0x00030000 || version == 0x00030001 || version == 0x04D2162F {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolPostgres,
			Category:   CategoryDatabase,
			Confidence: ConfidenceHigh,
			Reason:     "port 5432, valid Postgres startup/SSLRequest header",
		}
	}

	// Fallback: still likely Postgres given port.
	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolPostgres,
		Category:   CategoryDatabase,
		Confidence: ConfidenceMedium,
		Reason:     "port 5432, header not obviously invalid",
	}
}

// MySQLDetector uses port 3306 and the server handshake structure.
type MySQLDetector struct{}

func (MySQLDetector) Name() string { return "mysql" }

func (MySQLDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 3306 {
		return DetectionResult{}
	}

	if len(b) < 5 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMySQL,
			Category:   CategoryDatabase,
			Confidence: ConfidenceMedium,
			Reason:     "port 3306, insufficient bytes to confirm",
		}
	}

	// MySQL server handshake: 3-byte length, 1-byte seq, 1-byte protocol version.
	length := int(b[0]) | int(b[1])<<8 | int(b[2])<<16
	seq := b[3]
	prot := b[4]

	if length <= 0 || length > 10*1024*1024 {
		return DetectionResult{}
	}

	if seq == 0 && prot >= 0x0A && prot < 0x20 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMySQL,
			Category:   CategoryDatabase,
			Confidence: ConfidenceHigh,
			Reason:     "port 3306, valid MySQL handshake header",
		}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolMySQL,
		Category:   CategoryDatabase,
		Confidence: ConfidenceMedium,
		Reason:     "port 3306, header not obviously invalid",
	}
}

// MongoDetector uses port 27017 and a basic check of MongoDB's binary frame.
type MongoDetector struct{}

func (MongoDetector) Name() string { return "mongodb" }

func (MongoDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 27017 {
		return DetectionResult{}
	}
	if len(b) < 16 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMongoDB,
			Category:   CategoryDatabase,
			Confidence: ConfidenceMedium,
			Reason:     "port 27017, insufficient bytes to confirm",
		}
	}

	length := int32(binary.LittleEndian.Uint32(b[0:4]))
	if length <= 0 || length > 16*1024*1024 {
		return DetectionResult{}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolMongoDB,
		Category:   CategoryDatabase,
		Confidence: ConfidenceHigh,
		Reason:     "port 27017, sane MongoDB frame length",
	}
}

// MSSQLDetector uses port 1433 and the TDS packet type byte.
type MSSQLDetector struct{}

func (MSSQLDetector) Name() string { return "mssql" }

func (MSSQLDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 1433 {
		return DetectionResult{}
	}
	if len(b) < 8 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMSSQL,
			Category:   CategoryDatabase,
			Confidence: ConfidenceMedium,
			Reason:     "port 1433, insufficient bytes to confirm",
		}
	}

	t := b[0] // TDS packet type: 0x12 = PRELOGIN, 0x10 = LOGIN, etc.
	if t != 0x12 && t != 0x10 {
		return DetectionResult{}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolMSSQL,
		Category:   CategoryDatabase,
		Confidence: ConfidenceHigh,
		Reason:     "port 1433, TDS PRELOGIN/LOGIN header",
	}
}

// =============================================================================
// Message Bus Detectors
// =============================================================================

// KafkaDetector uses common Kafka ports and basic frame sanity checks.
type KafkaDetector struct{}

func (KafkaDetector) Name() string { return "kafka" }

func (KafkaDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	switch meta.RemotePort {
	case 9092, 19092, 29092, 9093:
		// OK - common Kafka ports
	default:
		return DetectionResult{}
	}

	if len(b) < 10 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolKafka,
			Category:   CategoryMessageBus,
			Confidence: ConfidenceMedium,
			Reason:     "kafka port, insufficient bytes to confirm",
		}
	}

	length := int32(binary.BigEndian.Uint32(b[0:4]))
	apiKey := int16(binary.BigEndian.Uint16(b[4:6]))
	apiVersion := int16(binary.BigEndian.Uint16(b[6:8]))

	if length <= 0 || length > 16*1024*1024 {
		return DetectionResult{}
	}
	if apiKey < 0 || apiKey > 100 {
		return DetectionResult{}
	}
	if apiVersion < -2 || apiVersion > 20 {
		return DetectionResult{}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolKafka,
		Category:   CategoryMessageBus,
		Confidence: ConfidenceHigh,
		Reason:     "kafka port with sane request frame",
	}
}

// AMQPDetector detects AMQP (including RabbitMQ) via the "AMQP" header.
type AMQPDetector struct{}

func (AMQPDetector) Name() string { return "amqp" }

func (AMQPDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 5672 && meta.RemotePort != 5671 {
		return DetectionResult{}
	}
	if len(b) < 8 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolAMQP,
			Category:   CategoryMessageBus,
			Confidence: ConfidenceMedium,
			Reason:     "amqp port, insufficient bytes to confirm",
		}
	}

	if bytes.Equal(b[:4], []byte("AMQP")) {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolAMQP,
			Category:   CategoryMessageBus,
			Confidence: ConfidenceHigh,
			Reason:     "AMQP protocol header",
		}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolAMQP,
		Category:   CategoryMessageBus,
		Confidence: ConfidenceMedium,
		Reason:     "amqp port, unknown payload",
	}
}

// NATSDetector detects NATS via the "INFO {" server banner.
type NATSDetector struct{}

func (NATSDetector) Name() string { return "nats" }

func (NATSDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 4222 && meta.RemotePort != 6222 {
		return DetectionResult{}
	}
	if len(b) == 0 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolNATS,
			Category:   CategoryMessageBus,
			Confidence: ConfidenceMedium,
			Reason:     "nats port, no payload yet",
		}
	}

	if bytes.HasPrefix(b, []byte("INFO {")) {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolNATS,
			Category:   CategoryMessageBus,
			Confidence: ConfidenceHigh,
			Reason:     "NATS INFO line",
		}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolNATS,
		Category:   CategoryMessageBus,
		Confidence: ConfidenceMedium,
		Reason:     "nats port, unknown payload",
	}
}

// =============================================================================
// Cache Detectors
// =============================================================================

var redisInlineCmd = regexp.MustCompile(`^[A-Z]{3,}( |\r|$)`)

// RedisDetector uses common Redis/Sentinel ports and RESP or inline commands.
type RedisDetector struct{}

func (RedisDetector) Name() string { return "redis" }

func (RedisDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 6379 && meta.RemotePort != 26379 {
		return DetectionResult{}
	}
	if len(b) == 0 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolRedis,
			Category:   CategoryCache,
			Confidence: ConfidenceMedium,
			Reason:     "redis port, no payload yet",
		}
	}

	switch b[0] {
	case '*':
		// RESP array.
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolRedis,
			Category:   CategoryCache,
			Confidence: ConfidenceHigh,
			Reason:     "redis RESP array",
		}
	default:
		// Inline command such as "PING\r\n" or "SET key val\r\n".
		lineEnd := bytes.Index(b, []byte("\r\n"))
		var line string
		if lineEnd > 0 {
			line = string(b[:lineEnd])
		} else {
			line = string(b)
		}
		line = strings.ToUpper(line)
		if redisInlineCmd.MatchString(line) {
			return DetectionResult{
				Matched:    true,
				Protocol:   ProtocolRedis,
				Category:   CategoryCache,
				Confidence: ConfidenceHigh,
				Reason:     "redis inline command",
			}
		}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolRedis,
		Category:   CategoryCache,
		Confidence: ConfidenceMedium,
		Reason:     "redis port, unknown payload",
	}
}

var memcacheTextCmd = regexp.MustCompile(`^(get|set|add|replace|append|prepend|delete|incr|decr)`)

// MemcacheDetector detects Memcached via port 11211 and protocol signatures.
type MemcacheDetector struct{}

func (MemcacheDetector) Name() string { return "memcached" }

func (MemcacheDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	if meta.RemotePort != 11211 {
		return DetectionResult{}
	}
	if len(b) == 0 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMemcached,
			Category:   CategoryCache,
			Confidence: ConfidenceMedium,
			Reason:     "memcached port, no payload yet",
		}
	}

	// Binary protocol magic: 0x80.
	if len(b) >= 2 && b[0] == 0x80 {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMemcached,
			Category:   CategoryCache,
			Confidence: ConfidenceHigh,
			Reason:     "memcached binary protocol magic",
		}
	}

	// Text protocol.
	lineEnd := bytes.Index(b, []byte("\r\n"))
	var line string
	if lineEnd > 0 {
		line = string(b[:lineEnd])
	} else {
		line = string(b)
	}
	line = strings.ToLower(line)
	if memcacheTextCmd.MatchString(line) {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolMemcached,
			Category:   CategoryCache,
			Confidence: ConfidenceHigh,
			Reason:     "memcached text command",
		}
	}

	return DetectionResult{
		Matched:    true,
		Protocol:   ProtocolMemcached,
		Category:   CategoryCache,
		Confidence: ConfidenceMedium,
		Reason:     "memcached port, unknown payload",
	}
}

// =============================================================================
// HTTP Detector
// =============================================================================

var httpMethods = []string{
	"GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "PATCH ", "OPTIONS ", "TRACE ",
}

// HTTPDetector detects HTTP/1.x and HTTP/2 based on request/response patterns.
type HTTPDetector struct{}

func (HTTPDetector) Name() string { return "http" }

func (HTTPDetector) Detect(meta ConnMeta, b []byte) DetectionResult {
	httpishPort := meta.RemotePort == 80 ||
		meta.RemotePort == 8080 ||
		meta.RemotePort == 8000 ||
		meta.RemotePort == 8001 ||
		meta.RemotePort == 443 ||
		meta.RemotePort == 8443

	if !httpishPort && !looksLikeHTTP1(b) && !looksLikeHTTP2(b) {
		return DetectionResult{}
	}

	if looksLikeHTTP2(b) {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolHTTP2,
			Category:   CategoryHTTP,
			Confidence: ConfidenceHigh,
			Reason:     "HTTP/2 client preface",
		}
	}
	if looksLikeHTTP1(b) {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolHTTP1,
			Category:   CategoryHTTP,
			Confidence: ConfidenceHigh,
			Reason:     "HTTP/1.x request/response line",
		}
	}

	if httpishPort {
		return DetectionResult{
			Matched:    true,
			Protocol:   ProtocolUnknown,
			Category:   CategoryHTTP,
			Confidence: ConfidenceMedium,
			Reason:     "http-like port, unknown payload (TLS or non-HTTP)",
		}
	}

	return DetectionResult{}
}

func looksLikeHTTP1(b []byte) bool {
	if len(b) < 4 {
		return false
	}
	// Request line: "METHOD /path HTTP/1.1"
	for _, m := range httpMethods {
		if bytes.HasPrefix(b, []byte(m)) {
			return true
		}
	}
	// Response line: "HTTP/1.1 200 OK"
	if bytes.HasPrefix(b, []byte("HTTP/1.")) {
		return true
	}
	return false
}

func looksLikeHTTP2(b []byte) bool {
	preface := []byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
	if len(b) < len(preface) {
		return false
	}
	return bytes.HasPrefix(b, preface)
}
