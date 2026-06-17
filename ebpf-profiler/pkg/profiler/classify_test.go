//go:build linux

package profiler

import (
	"encoding/binary"
	"net/netip"
	"testing"
)

// Helper to create ConnMeta with just remote port (most common case)
func metaWithPort(port uint16) ConnMeta {
	return ConnMeta{
		PID:        1234,
		LocalIP:    netip.MustParseAddr("10.0.0.1"),
		LocalPort:  45678,
		RemoteIP:   netip.MustParseAddr("10.0.0.2"),
		RemotePort: port,
	}
}

// =============================================================================
// PostgreSQL Tests
// =============================================================================

func TestPostgresDetector_StartupMessage(t *testing.T) {
	// PostgreSQL startup message: 4-byte length + 4-byte protocol version (3.0 = 0x00030000)
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)         // length
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000) // version 3.0

	result := PostgresDetector{}.Detect(metaWithPort(5432), payload)

	if !result.Matched {
		t.Fatal("expected PostgreSQL to match")
	}
	if result.Protocol != ProtocolPostgres {
		t.Errorf("expected protocol %s, got %s", ProtocolPostgres, result.Protocol)
	}
	if result.Category != CategoryDatabase {
		t.Errorf("expected category %s, got %s", CategoryDatabase, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestPostgresDetector_SSLRequest(t *testing.T) {
	// PostgreSQL SSLRequest: length=8, magic=0x04D2162F (80877103)
	payload := make([]byte, 8)
	binary.BigEndian.PutUint32(payload[0:4], 8)          // length
	binary.BigEndian.PutUint32(payload[4:8], 0x04D2162F) // SSL request magic

	result := PostgresDetector{}.Detect(metaWithPort(5432), payload)

	if !result.Matched {
		t.Fatal("expected PostgreSQL SSLRequest to match")
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence for SSLRequest, got %d", result.Confidence)
	}
}

func TestPostgresDetector_PortOnly(t *testing.T) {
	// Not enough bytes, but correct port
	result := PostgresDetector{}.Detect(metaWithPort(5432), []byte{0x00, 0x00})

	if !result.Matched {
		t.Fatal("expected PostgreSQL port-only match")
	}
	if result.Confidence != ConfidenceMedium {
		t.Errorf("expected medium confidence for port-only, got %d", result.Confidence)
	}
}

func TestPostgresDetector_WrongPort(t *testing.T) {
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000)

	result := PostgresDetector{}.Detect(metaWithPort(3306), payload) // MySQL port

	if result.Matched {
		t.Error("expected PostgreSQL not to match on wrong port")
	}
}

// =============================================================================
// MySQL Tests
// =============================================================================

func TestMySQLDetector_Handshake(t *testing.T) {
	// MySQL server handshake: 3-byte length (little-endian), 1-byte seq=0, 1-byte protocol=0x0A
	payload := []byte{
		0x4a, 0x00, 0x00, // length = 74 (little-endian)
		0x00,                          // sequence number = 0
		0x0a,                          // protocol version 10
		'8', '.', '0', '.', '0', 0x00, // version string
	}

	result := MySQLDetector{}.Detect(metaWithPort(3306), payload)

	if !result.Matched {
		t.Fatal("expected MySQL to match")
	}
	if result.Protocol != ProtocolMySQL {
		t.Errorf("expected protocol %s, got %s", ProtocolMySQL, result.Protocol)
	}
	if result.Category != CategoryDatabase {
		t.Errorf("expected category %s, got %s", CategoryDatabase, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestMySQLDetector_PortOnly(t *testing.T) {
	result := MySQLDetector{}.Detect(metaWithPort(3306), []byte{0x00, 0x00})

	if !result.Matched {
		t.Fatal("expected MySQL port-only match")
	}
	if result.Confidence != ConfidenceMedium {
		t.Errorf("expected medium confidence for port-only, got %d", result.Confidence)
	}
}

// =============================================================================
// MongoDB Tests
// =============================================================================

func TestMongoDetector_ValidFrame(t *testing.T) {
	// MongoDB wire protocol: 4-byte length (little-endian), then requestId, responseTo, opcode
	payload := make([]byte, 20)
	binary.LittleEndian.PutUint32(payload[0:4], 100) // reasonable message length

	result := MongoDetector{}.Detect(metaWithPort(27017), payload)

	if !result.Matched {
		t.Fatal("expected MongoDB to match")
	}
	if result.Protocol != ProtocolMongoDB {
		t.Errorf("expected protocol %s, got %s", ProtocolMongoDB, result.Protocol)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestMongoDetector_InvalidLength(t *testing.T) {
	// MongoDB with invalid (too large) length
	payload := make([]byte, 20)
	binary.LittleEndian.PutUint32(payload[0:4], 100*1024*1024) // 100MB - too large

	result := MongoDetector{}.Detect(metaWithPort(27017), payload)

	if result.Matched {
		t.Error("expected MongoDB not to match with invalid length")
	}
}

// =============================================================================
// MSSQL Tests
// =============================================================================

func TestMSSQLDetector_Prelogin(t *testing.T) {
	// TDS PRELOGIN packet: type=0x12
	payload := []byte{0x12, 0x01, 0x00, 0x2F, 0x00, 0x00, 0x01, 0x00}

	result := MSSQLDetector{}.Detect(metaWithPort(1433), payload)

	if !result.Matched {
		t.Fatal("expected MSSQL PRELOGIN to match")
	}
	if result.Protocol != ProtocolMSSQL {
		t.Errorf("expected protocol %s, got %s", ProtocolMSSQL, result.Protocol)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestMSSQLDetector_Login(t *testing.T) {
	// TDS LOGIN packet: type=0x10
	payload := []byte{0x10, 0x01, 0x00, 0x90, 0x00, 0x00, 0x01, 0x00}

	result := MSSQLDetector{}.Detect(metaWithPort(1433), payload)

	if !result.Matched {
		t.Fatal("expected MSSQL LOGIN to match")
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

// =============================================================================
// Kafka Tests
// =============================================================================

func TestKafkaDetector_ValidRequest(t *testing.T) {
	// Kafka request: 4-byte length, 2-byte API key, 2-byte API version, 4-byte correlation ID
	payload := make([]byte, 12)
	binary.BigEndian.PutUint32(payload[0:4], 100)  // length
	binary.BigEndian.PutUint16(payload[4:6], 0)    // API key 0 = Produce
	binary.BigEndian.PutUint16(payload[6:8], 9)    // API version 9
	binary.BigEndian.PutUint32(payload[8:12], 123) // correlation ID

	result := KafkaDetector{}.Detect(metaWithPort(9092), payload)

	if !result.Matched {
		t.Fatal("expected Kafka to match")
	}
	if result.Protocol != ProtocolKafka {
		t.Errorf("expected protocol %s, got %s", ProtocolKafka, result.Protocol)
	}
	if result.Category != CategoryMessageBus {
		t.Errorf("expected category %s, got %s", CategoryMessageBus, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestKafkaDetector_AlternatePorts(t *testing.T) {
	payload := make([]byte, 12)
	binary.BigEndian.PutUint32(payload[0:4], 100)
	binary.BigEndian.PutUint16(payload[4:6], 3) // API key 3 = Metadata
	binary.BigEndian.PutUint16(payload[6:8], 5)

	for _, port := range []uint16{9092, 19092, 29092, 9093} {
		result := KafkaDetector{}.Detect(metaWithPort(port), payload)
		if !result.Matched {
			t.Errorf("expected Kafka to match on port %d", port)
		}
	}
}

func TestKafkaDetector_InvalidAPIKey(t *testing.T) {
	payload := make([]byte, 12)
	binary.BigEndian.PutUint32(payload[0:4], 100)
	binary.BigEndian.PutUint16(payload[4:6], 200) // Invalid API key (>100)
	binary.BigEndian.PutUint16(payload[6:8], 5)

	result := KafkaDetector{}.Detect(metaWithPort(9092), payload)

	if result.Matched {
		t.Error("expected Kafka not to match with invalid API key")
	}
}

// =============================================================================
// AMQP Tests
// =============================================================================

func TestAMQPDetector_ProtocolHeader(t *testing.T) {
	// AMQP 0-9-1 protocol header
	payload := []byte{'A', 'M', 'Q', 'P', 0x00, 0x00, 0x09, 0x01}

	result := AMQPDetector{}.Detect(metaWithPort(5672), payload)

	if !result.Matched {
		t.Fatal("expected AMQP to match")
	}
	if result.Protocol != ProtocolAMQP {
		t.Errorf("expected protocol %s, got %s", ProtocolAMQP, result.Protocol)
	}
	if result.Category != CategoryMessageBus {
		t.Errorf("expected category %s, got %s", CategoryMessageBus, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestAMQPDetector_TLSPort(t *testing.T) {
	payload := []byte{'A', 'M', 'Q', 'P', 0x00, 0x00, 0x09, 0x01}

	result := AMQPDetector{}.Detect(metaWithPort(5671), payload)

	if !result.Matched {
		t.Fatal("expected AMQP to match on TLS port")
	}
}

// =============================================================================
// NATS Tests
// =============================================================================

func TestNATSDetector_InfoLine(t *testing.T) {
	payload := []byte(`INFO {"server_id":"abc","version":"2.9.0"}` + "\r\n")

	result := NATSDetector{}.Detect(metaWithPort(4222), payload)

	if !result.Matched {
		t.Fatal("expected NATS to match")
	}
	if result.Protocol != ProtocolNATS {
		t.Errorf("expected protocol %s, got %s", ProtocolNATS, result.Protocol)
	}
	if result.Category != CategoryMessageBus {
		t.Errorf("expected category %s, got %s", CategoryMessageBus, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestNATSDetector_PortOnly(t *testing.T) {
	result := NATSDetector{}.Detect(metaWithPort(4222), []byte{})

	if !result.Matched {
		t.Fatal("expected NATS port-only match")
	}
	if result.Confidence != ConfidenceMedium {
		t.Errorf("expected medium confidence, got %d", result.Confidence)
	}
}

// =============================================================================
// Redis Tests
// =============================================================================

func TestRedisDetector_RESPArray(t *testing.T) {
	// RESP array: *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n
	payload := []byte("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n")

	result := RedisDetector{}.Detect(metaWithPort(6379), payload)

	if !result.Matched {
		t.Fatal("expected Redis RESP to match")
	}
	if result.Protocol != ProtocolRedis {
		t.Errorf("expected protocol %s, got %s", ProtocolRedis, result.Protocol)
	}
	if result.Category != CategoryCache {
		t.Errorf("expected category %s, got %s", CategoryCache, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestRedisDetector_InlineCommand(t *testing.T) {
	payload := []byte("PING\r\n")

	result := RedisDetector{}.Detect(metaWithPort(6379), payload)

	if !result.Matched {
		t.Fatal("expected Redis inline to match")
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence for inline command, got %d", result.Confidence)
	}
}

func TestRedisDetector_SentinelPort(t *testing.T) {
	payload := []byte("*1\r\n$4\r\nPING\r\n")

	result := RedisDetector{}.Detect(metaWithPort(26379), payload)

	if !result.Matched {
		t.Fatal("expected Redis to match on Sentinel port")
	}
}

// =============================================================================
// Memcached Tests
// =============================================================================

func TestMemcacheDetector_TextProtocol(t *testing.T) {
	payload := []byte("get mykey\r\n")

	result := MemcacheDetector{}.Detect(metaWithPort(11211), payload)

	if !result.Matched {
		t.Fatal("expected Memcached text protocol to match")
	}
	if result.Protocol != ProtocolMemcached {
		t.Errorf("expected protocol %s, got %s", ProtocolMemcached, result.Protocol)
	}
	if result.Category != CategoryCache {
		t.Errorf("expected category %s, got %s", CategoryCache, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestMemcacheDetector_SetCommand(t *testing.T) {
	payload := []byte("set mykey 0 900 5\r\nvalue\r\n")

	result := MemcacheDetector{}.Detect(metaWithPort(11211), payload)

	if !result.Matched {
		t.Fatal("expected Memcached set command to match")
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestMemcacheDetector_BinaryProtocol(t *testing.T) {
	// Binary protocol: magic 0x80
	payload := []byte{0x80, 0x00, 0x00, 0x05}

	result := MemcacheDetector{}.Detect(metaWithPort(11211), payload)

	if !result.Matched {
		t.Fatal("expected Memcached binary protocol to match")
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence for binary protocol, got %d", result.Confidence)
	}
}

// =============================================================================
// HTTP Tests
// =============================================================================

func TestHTTPDetector_GetRequest(t *testing.T) {
	payload := []byte("GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n")

	result := HTTPDetector{}.Detect(metaWithPort(8080), payload)

	if !result.Matched {
		t.Fatal("expected HTTP to match")
	}
	if result.Protocol != ProtocolHTTP1 {
		t.Errorf("expected protocol %s, got %s", ProtocolHTTP1, result.Protocol)
	}
	if result.Category != CategoryHTTP {
		t.Errorf("expected category %s, got %s", CategoryHTTP, result.Category)
	}
	if result.Confidence != ConfidenceHigh {
		t.Errorf("expected high confidence, got %d", result.Confidence)
	}
}

func TestHTTPDetector_PostRequest(t *testing.T) {
	payload := []byte("POST /api/users HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\n\r\n{}")

	result := HTTPDetector{}.Detect(metaWithPort(80), payload)

	if !result.Matched {
		t.Fatal("expected HTTP POST to match")
	}
	if result.Protocol != ProtocolHTTP1 {
		t.Errorf("expected protocol %s, got %s", ProtocolHTTP1, result.Protocol)
	}
}

func TestHTTPDetector_Response(t *testing.T) {
	payload := []byte("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{}")

	result := HTTPDetector{}.Detect(metaWithPort(8080), payload)

	if !result.Matched {
		t.Fatal("expected HTTP response to match")
	}
	if result.Protocol != ProtocolHTTP1 {
		t.Errorf("expected protocol %s, got %s", ProtocolHTTP1, result.Protocol)
	}
}

func TestHTTPDetector_HTTP2Preface(t *testing.T) {
	payload := []byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")

	result := HTTPDetector{}.Detect(metaWithPort(443), payload)

	if !result.Matched {
		t.Fatal("expected HTTP/2 to match")
	}
	if result.Protocol != ProtocolHTTP2 {
		t.Errorf("expected protocol %s, got %s", ProtocolHTTP2, result.Protocol)
	}
}

func TestHTTPDetector_NonStandardPort(t *testing.T) {
	// HTTP on non-standard port should still be detected by payload
	payload := []byte("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")

	result := HTTPDetector{}.Detect(metaWithPort(3000), payload)

	if !result.Matched {
		t.Fatal("expected HTTP on non-standard port to match by payload")
	}
}

// =============================================================================
// DetectConnection Integration Tests
// =============================================================================

func TestDetectConnection_PostgresWins(t *testing.T) {
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000)

	result := DetectConnection(metaWithPort(5432), payload)

	if result.Protocol != ProtocolPostgres {
		t.Errorf("expected DetectConnection to return PostgreSQL, got %s", result.Protocol)
	}
}

func TestDetectConnection_UnknownProtocol(t *testing.T) {
	// Random port and payload that doesn't match anything
	payload := []byte{0xDE, 0xAD, 0xBE, 0xEF}

	result := DetectConnection(metaWithPort(12345), payload)

	if result.Matched {
		t.Errorf("expected no match for unknown protocol, got %s", result.Protocol)
	}
	if result.Protocol != ProtocolUnknown {
		t.Errorf("expected protocol unknown, got %s", result.Protocol)
	}
}

func TestDetectConnection_TLSReducesConfidence(t *testing.T) {
	payload := make([]byte, 16)
	binary.BigEndian.PutUint32(payload[0:4], 16)
	binary.BigEndian.PutUint32(payload[4:8], 0x00030000)

	meta := metaWithPort(5432)
	meta.IsTLS = true

	result := DetectConnection(meta, payload)

	if result.Confidence != ConfidenceMedium {
		t.Errorf("expected TLS to reduce confidence to medium, got %d", result.Confidence)
	}
	if result.Reason == "" || result.Reason == "port 5432, valid Postgres startup/SSLRequest header" {
		t.Error("expected reason to indicate TLS")
	}
}
