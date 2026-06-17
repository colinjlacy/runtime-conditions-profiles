//go:build linux

package profiler

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
	"time"

	"gopkg.in/yaml.v3"
)

// EndpointInfo represents a unique destination+method+path combination with schema info
type EndpointInfo struct {
	Destination     string            `yaml:"destination"`                // destination service name
	DestinationType string            `yaml:"destination_type,omitempty"` // "container", "external", or "unknown"
	DestImage       string            `yaml:"-"`                          // destination image (not exported to YAML, used for workload creation)
	DestLabels      map[string]string `yaml:"-"`                          // destination labels (not exported to YAML, used for workload creation)
	Method          string            `yaml:"method"`
	Path            string            `yaml:"path"`
	RequestSchema   interface{}       `yaml:"request_schema"`  // null, "non-json", or JSON structure
	ResponseSchema  interface{}       `yaml:"response_schema"` // null, "non-json", or JSON structure
	FirstSeen       time.Time         `yaml:"first_seen"`
	LastSeen        time.Time         `yaml:"last_seen"`
	Count           int64             `yaml:"count"`
}

// ConnectionInfo represents a non-HTTP connection (database, cache, message bus)
type ConnectionInfo struct {
	Destination     string            `yaml:"destination"`                // destination service/host name
	DestinationType string            `yaml:"destination_type,omitempty"` // "container", "external", or "unknown"
	DestImage       string            `yaml:"-"`                          // destination image (not exported to YAML, used for workload creation)
	DestLabels      map[string]string `yaml:"-"`                          // destination labels (not exported to YAML, used for workload creation)
	Protocol        string            `yaml:"protocol"`                   // e.g., "postgres", "mysql", "redis"
	Category        string            `yaml:"category"`                   // e.g., "database", "cache", "message_bus"
	Port            uint16            `yaml:"port"`                       // Remote port
	Confidence      int               `yaml:"confidence"`                 // 0-100 confidence score
	Reason          string            `yaml:"reason,omitempty"`           // Detection reason
	FirstSeen       time.Time         `yaml:"first_seen"`
	LastSeen        time.Time         `yaml:"last_seen"`
	Count           int64             `yaml:"count"`
}

// ServiceProfile represents all outbound activity from a single service
type ServiceProfile struct {
	Name        string            `yaml:"name"`
	Image       string            `yaml:"image,omitempty"`
	Labels      map[string]string `yaml:"labels,omitempty"`      // container labels
	Endpoints   []*EndpointInfo   `yaml:"endpoints,omitempty"`   // HTTP endpoints called
	Connections []*ConnectionInfo `yaml:"connections,omitempty"` // Non-HTTP connections (databases, caches, etc.)
	FirstSeen   time.Time         `yaml:"first_seen"`
	LastSeen    time.Time         `yaml:"last_seen"`
}

// PendingRequest stores request info while waiting for response
type PendingRequest struct {
	SrcService  string
	SrcImage    string
	SrcLabels   map[string]string
	DstService  string
	DstImage    string
	DstLabels   map[string]string
	DstType     string
	Method      string
	Path        string
	RequestBody string
	Timestamp   time.Time
}

// HTTPEventInfo contains the relevant fields from an HTTP event for service mapping
type HTTPEventInfo struct {
	Direction  string // "send" or "recv"
	SourceIP   string
	SourcePort uint16
	DestIP     string
	DestPort   uint16
	PID        uint32
	Method     string
	URL        string
	StatusCode string
	Body       string
	SrcService string
	SrcImage   string
	SrcLabels  map[string]string // source container labels
	DstService string
	DstImage   string
	DstLabels  map[string]string // destination container labels
	DstType    string
}

// ConnectionEventInfo contains fields for non-HTTP connection events
type ConnectionEventInfo struct {
	Direction  string // "send" or "recv"
	SrcService string
	SrcImage   string
	SrcLabels  map[string]string // source container labels
	DstService string
	DstImage   string
	DstLabels  map[string]string // destination container labels
	DstType    string            // "container" or "external"
	Protocol   string            // e.g., "postgres", "mysql", "redis"
	Category   string            // e.g., "database", "cache", "message_bus"
	Port       uint16            // Remote port
	Confidence int               // 0-100
	Reason     string            // Detection reason
}

// ========================================================================
// ObservedBehaviors format structs (new canonical output format)
// ========================================================================

// ObservedBehaviors is the top-level structure for the new canonical output format
type ObservedBehaviors struct {
	APIVersion string                    `yaml:"apiVersion"`
	Kind       string                    `yaml:"kind"`
	Metadata   ObservedBehaviorsMetadata `yaml:"metadata"`
	Spec       ObservedBehaviorsSpec     `yaml:"spec"`
}

// ObservedBehaviorsMetadata contains metadata about the observation run
type ObservedBehaviorsMetadata struct {
	Name string `yaml:"name"`
}

// ObservedBehaviorsSpec contains the main observation data
type ObservedBehaviorsSpec struct {
	GeneratedAt        string                 `yaml:"generatedAt"`
	ObservationEngines []ObservationEngineRef `yaml:"observationEngines,omitempty"`
	Environment        EnvironmentInfo        `yaml:"environment"`
	Workloads          []WorkloadIdentity     `yaml:"workloads"`
	Behaviors          []ObservedBehavior     `yaml:"behaviors"`
}

// ObservationEngineRef references the observation engine used
type ObservationEngineRef struct {
	Ref string `yaml:"ref"`
}

// EnvironmentInfo describes the environment where observations were made
type EnvironmentInfo struct {
	Observed string `yaml:"observed"` // e.g., "local", "dev", "staging", "prod"
}

// WorkloadIdentity represents a stable identity for a workload
type WorkloadIdentity struct {
	ID          string            `yaml:"id"`
	DisplayName string            `yaml:"displayName"`
	Software    WorkloadSoftware  `yaml:"software,omitempty"`
	Labels      map[string]string `yaml:"labels,omitempty"`
	Evidence    WorkloadEvidence  `yaml:"evidence"`
}

// WorkloadSoftware contains software metadata for a workload
type WorkloadSoftware struct {
	Image string `yaml:"image,omitempty"`
}

// WorkloadEvidence contains evidence about when a workload was observed
type WorkloadEvidence struct {
	FirstSeen string                 `yaml:"firstSeen"`
	LastSeen  string                 `yaml:"lastSeen"`
	Sources   []ObservationSourceRef `yaml:"sources,omitempty"`
}

// ObservedBehavior represents a single observed interaction
type ObservedBehavior struct {
	ID          string              `yaml:"id"`
	SourceRef   string              `yaml:"sourceRef"`
	Destination BehaviorDestination `yaml:"destination"`
	Facets      BehaviorFacets      `yaml:"facets"`
	Evidence    BehaviorEvidence    `yaml:"evidence"`
}

// BehaviorDestination describes where the interaction was directed
type BehaviorDestination struct {
	WorkloadRef string `yaml:"workloadRef,omitempty"`
	// Future: Identity fields for non-container destinations
	// Identity *DestinationIdentity `yaml:"identity,omitempty"`
}

// BehaviorFacets contains protocol and interface information
type BehaviorFacets struct {
	Protocol  ProtocolFacet   `yaml:"protocol"`
	Interface *InterfaceFacet `yaml:"interface,omitempty"`
	Network   *NetworkFacet   `yaml:"network,omitempty"`
}

// ProtocolFacet describes the protocol used
type ProtocolFacet struct {
	Name                     string  `yaml:"name"`
	Category                 string  `yaml:"category,omitempty"`
	ClassificationConfidence float64 `yaml:"classificationConfidence,omitempty"`
	ClassificationReason     string  `yaml:"classificationReason,omitempty"`
}

// InterfaceFacet describes application-level interface details
type InterfaceFacet struct {
	HTTP *HTTPInterface `yaml:"http,omitempty"`
}

// HTTPInterface describes HTTP-specific details
type HTTPInterface struct {
	Method         string      `yaml:"method"`
	Path           string      `yaml:"path"`
	RequestSchema  interface{} `yaml:"requestSchema"`
	ResponseSchema interface{} `yaml:"responseSchema"`
}

// NetworkFacet describes network-level details
type NetworkFacet struct {
	Transport string `yaml:"transport"` // e.g., "tcp", "udp"
	Port      uint16 `yaml:"port"`
}

// BehaviorEvidence contains evidence about the observed behavior
type BehaviorEvidence struct {
	FirstSeen          string                 `yaml:"firstSeen"`
	LastSeen           string                 `yaml:"lastSeen"`
	Count              int64                  `yaml:"count"`
	ObserverConfidence float64                `yaml:"observerConfidence"`
	Sources            []ObservationSourceRef `yaml:"sources,omitempty"`
}

// ObservationSourceRef references the observation engine that captured this evidence
type ObservationSourceRef struct {
	EngineRef string `yaml:"engineRef"`
}

// ServiceMap tracks service profiles with debounced file writing
type ServiceMap struct {
	mu              sync.RWMutex
	services        map[string]*ServiceProfile // key: service name
	pendingRequests map[string]*PendingRequest // connection key -> pending request
	outputPath      string
	dirty           bool
	debounceTimer   *time.Timer
	debouncePeriod  time.Duration
}

// NewServiceMap creates a new service map with debounced file writing
func NewServiceMap(outputPath string, debouncePeriod time.Duration) *ServiceMap {
	return &ServiceMap{
		services:        make(map[string]*ServiceProfile),
		pendingRequests: make(map[string]*PendingRequest),
		outputPath:      outputPath,
		debouncePeriod:  debouncePeriod,
	}
}

// endpointKey generates a unique key for an endpoint within a service
func endpointKey(dst, method, path string) string {
	return fmt.Sprintf("%s|%s|%s", dst, method, path)
}

// connectionKey generates a key for request/response correlation
func connectionKey(pid uint32, srcIP string, srcPort uint16, dstIP string, dstPort uint16) string {
	return fmt.Sprintf("%d|%s:%d|%s:%d", pid, srcIP, srcPort, dstIP, dstPort)
}

// connInfoKey generates a key for connection deduplication
func connInfoKey(dst, protocol string, port uint16) string {
	return fmt.Sprintf("%s|%s|%d", dst, protocol, port)
}

// extractJSONSchema extracts the structure of a JSON object (keys only, no values)
func extractJSONSchema(body string) interface{} {
	if body == "" {
		return nil
	}

	var data interface{}
	if err := json.Unmarshal([]byte(body), &data); err != nil {
		return "non-json"
	}

	return extractSchema(data)
}

// extractSchema recursively extracts the schema structure from parsed JSON
func extractSchema(data interface{}) interface{} {
	switch v := data.(type) {
	case map[string]interface{}:
		result := make(map[string]interface{})
		for key, val := range v {
			result[key] = extractSchema(val)
		}
		return result
	case []interface{}:
		if len(v) == 0 {
			return []interface{}{}
		}
		return []interface{}{extractSchema(v[0])}
	case string:
		return "string"
	case float64:
		return "number"
	case bool:
		return "boolean"
	case nil:
		return "null"
	default:
		return "unknown"
	}
}

// schemasEqual compares two schema structures for equality
func schemasEqual(a, b interface{}) bool {
	aJSON, _ := json.Marshal(a)
	bJSON, _ := json.Marshal(b)
	return string(aJSON) == string(bJSON)
}

// RecordHTTPEvent records an HTTP event and handles request/response correlation
// Processes request sends and response recvs, skipping request recvs to avoid duplicates
func (sm *ServiceMap) RecordHTTPEvent(event HTTPEventInfo) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	// Skip request recv events (server-side) to avoid duplicate behaviors
	// BPF filters response sends (server → client), so we only see:
	// 1. Request sends (client → server) - KEEP for tracking
	// 2. Request recvs (client → server, server POV) - SKIP to avoid duplicates
	// 3. Response recvs (server → client, client POV) - KEEP for correlation
	if event.Direction == "recv" && event.Method != "" && event.StatusCode == "" {
		// This is a request recv (server receiving request) - skip it
		return
	}

	// Normalize service names
	srcService := event.SrcService
	dstService := event.DstService
	if srcService == "" {
		srcService = "unknown"
	}
	if dstService == "" {
		if event.DstType == "external" {
			dstService = "external"
		} else {
			dstService = "unknown"
		}
	}

	// Handle based on event type
	if event.Method != "" && event.StatusCode == "" {
		// This is a request send (client → server)
		sm.handleRequest(event, srcService, dstService)
	} else if event.StatusCode != "" {
		// This is a response recv (client receiving response)
		sm.handleResponse(event, srcService, dstService)
	}
}

// RecordConnectionEvent records a non-HTTP connection event (database, cache, message bus)
func (sm *ServiceMap) RecordConnectionEvent(event ConnectionEventInfo) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	// Normalize service names
	srcService := event.SrcService
	dstService := event.DstService
	if srcService == "" {
		srcService = "unknown"
	}
	if dstService == "" {
		dstService = event.Protocol // use protocol as destination name (e.g., "postgres", "redis")
	}

	now := time.Now()

	// Get or create service profile
	profile := sm.getOrCreateProfile(srcService, event.SrcImage, event.SrcLabels, now)
	profile.LastSeen = now

	// Find or create connection info
	var matchingConn *ConnectionInfo
	for _, conn := range profile.Connections {
		if conn.Destination == dstService && conn.Protocol == event.Protocol && conn.Port == event.Port {
			matchingConn = conn
			break
		}
	}

	if matchingConn != nil {
		// Update existing connection
		matchingConn.LastSeen = now
		matchingConn.Count++
		if event.Confidence > matchingConn.Confidence {
			matchingConn.Confidence = event.Confidence
			matchingConn.Reason = event.Reason
		}
	} else {
		// Create new connection entry
		newConn := &ConnectionInfo{
			Destination:     dstService,
			DestinationType: event.DstType,
			DestImage:       event.DstImage,
			DestLabels:      event.DstLabels,
			Protocol:        event.Protocol,
			Category:        event.Category,
			Port:            event.Port,
			Confidence:      event.Confidence,
			Reason:          event.Reason,
			FirstSeen:       now,
			LastSeen:        now,
			Count:           1,
		}
		profile.Connections = append(profile.Connections, newConn)
	}

	sm.dirty = true
	sm.scheduleDebouncedWrite()
}

// getOrCreateProfile gets or creates a service profile
func (sm *ServiceMap) getOrCreateProfile(name, image string, labels map[string]string, now time.Time) *ServiceProfile {
	profile, exists := sm.services[name]
	if !exists {
		profile = &ServiceProfile{
			Name:        name,
			Image:       image,
			Labels:      labels,
			Endpoints:   []*EndpointInfo{},
			Connections: []*ConnectionInfo{},
			FirstSeen:   now,
			LastSeen:    now,
		}
		sm.services[name] = profile
	} else {
		// Update labels if provided and profile already exists
		if labels != nil && len(labels) > 0 {
			profile.Labels = labels
		}
	}
	return profile
}

// handleRequest processes an outgoing HTTP request
func (sm *ServiceMap) handleRequest(event HTTPEventInfo, srcService, dstService string) {
	connKey := connectionKey(event.PID, event.SourceIP, event.SourcePort, event.DestIP, event.DestPort)

	sm.pendingRequests[connKey] = &PendingRequest{
		SrcService:  srcService,
		SrcImage:    event.SrcImage,
		SrcLabels:   event.SrcLabels,
		DstService:  dstService,
		DstImage:    event.DstImage,
		DstLabels:   event.DstLabels,
		DstType:     event.DstType,
		Method:      event.Method,
		Path:        event.URL,
		RequestBody: event.Body,
		Timestamp:   time.Now(),
	}

	sm.cleanupOldPendingRequests()
}

// handleResponse processes an HTTP response and correlates with request
func (sm *ServiceMap) handleResponse(event HTTPEventInfo, srcService, dstService string) {
	connKey := connectionKey(event.PID, event.SourceIP, event.SourcePort, event.DestIP, event.DestPort)

	pendingReq, found := sm.pendingRequests[connKey]
	if !found {
		return
	}

	delete(sm.pendingRequests, connKey)

	requestSchema := extractJSONSchema(pendingReq.RequestBody)
	responseSchema := extractJSONSchema(event.Body)

	sm.recordEndpoint(
		pendingReq.SrcService, pendingReq.SrcImage, pendingReq.SrcLabels,
		pendingReq.DstService, pendingReq.DstImage, pendingReq.DstLabels, pendingReq.DstType,
		pendingReq.Method, pendingReq.Path,
		requestSchema, responseSchema,
	)
}

// recordEndpoint records an HTTP endpoint call
func (sm *ServiceMap) recordEndpoint(
	srcService, srcImage string, srcLabels map[string]string,
	dstService, dstImage string, dstLabels map[string]string, dstType,
	method, path string,
	requestSchema, responseSchema interface{},
) {
	now := time.Now()

	// Get or create service profile
	profile := sm.getOrCreateProfile(srcService, srcImage, srcLabels, now)
	profile.LastSeen = now

	// Find or create endpoint with matching schemas
	var matchingEndpoint *EndpointInfo
	for _, ep := range profile.Endpoints {
		if ep.Destination == dstService && ep.Method == method && ep.Path == path &&
			schemasEqual(ep.RequestSchema, requestSchema) &&
			schemasEqual(ep.ResponseSchema, responseSchema) {
			matchingEndpoint = ep
			break
		}
	}

	if matchingEndpoint != nil {
		matchingEndpoint.LastSeen = now
		matchingEndpoint.Count++
	} else {
		newEndpoint := &EndpointInfo{
			Destination:     dstService,
			DestinationType: dstType,
			DestImage:       dstImage,
			DestLabels:      dstLabels,
			Method:          method,
			Path:            path,
			RequestSchema:   requestSchema,
			ResponseSchema:  responseSchema,
			FirstSeen:       now,
			LastSeen:        now,
			Count:           1,
		}
		profile.Endpoints = append(profile.Endpoints, newEndpoint)
	}

	sm.dirty = true
	sm.scheduleDebouncedWrite()
}

// cleanupOldPendingRequests removes pending requests older than 30 seconds
func (sm *ServiceMap) cleanupOldPendingRequests() {
	cutoff := time.Now().Add(-30 * time.Second)
	for key, req := range sm.pendingRequests {
		if req.Timestamp.Before(cutoff) {
			delete(sm.pendingRequests, key)
		}
	}
}

// scheduleDebouncedWrite schedules a debounced write to disk
func (sm *ServiceMap) scheduleDebouncedWrite() {
	// Cancel existing timer if any
	if sm.debounceTimer != nil {
		sm.debounceTimer.Stop()
	}

	sm.debounceTimer = time.AfterFunc(sm.debouncePeriod, func() {
		sm.mu.Lock()
		if sm.dirty {
			sm.writeToFileLocked()
			sm.dirty = false
		}
		sm.mu.Unlock()
	})
}

// ========================================================================
// Mapping functions: Convert internal structures to ObservedBehaviors format
// ========================================================================

// mapServiceToWorkload converts a ServiceProfile to a WorkloadIdentity
func mapServiceToWorkload(profile *ServiceProfile) WorkloadIdentity {
	workloadID := fmt.Sprintf("workload:container/%s", profile.Name)

	workload := WorkloadIdentity{
		ID:          workloadID,
		DisplayName: profile.Name,
		Labels:      profile.Labels,
		Evidence: WorkloadEvidence{
			FirstSeen: profile.FirstSeen.Format(time.RFC3339Nano),
			LastSeen:  profile.LastSeen.Format(time.RFC3339Nano),
			Sources: []ObservationSourceRef{
				{EngineRef: "observationengine/golang-http-profiler"},
			},
		},
	}

	if profile.Image != "" {
		workload.Software.Image = profile.Image
	}

	return workload
}

// mapEndpointToBehavior converts an EndpointInfo to an ObservedBehavior (HTTP interaction)
func mapEndpointToBehavior(sourceServiceName string, endpoint *EndpointInfo) ObservedBehavior {
	sourceRef := fmt.Sprintf("workload:container/%s", sourceServiceName)
	destRef := fmt.Sprintf("workload:container/%s", endpoint.Destination)

	// Generate stable, readable behavior ID
	behaviorID := fmt.Sprintf("behavior:%s:http:%s:%s:%s",
		sourceServiceName,
		endpoint.Destination,
		endpoint.Method,
		endpoint.Path)

	return ObservedBehavior{
		ID:        behaviorID,
		SourceRef: sourceRef,
		Destination: BehaviorDestination{
			WorkloadRef: destRef,
		},
		Facets: BehaviorFacets{
			Protocol: ProtocolFacet{
				Name: "http",
			},
			Interface: &InterfaceFacet{
				HTTP: &HTTPInterface{
					Method:         endpoint.Method,
					Path:           endpoint.Path,
					RequestSchema:  endpoint.RequestSchema,
					ResponseSchema: endpoint.ResponseSchema,
				},
			},
		},
		Evidence: BehaviorEvidence{
			FirstSeen:          endpoint.FirstSeen.Format(time.RFC3339Nano),
			LastSeen:           endpoint.LastSeen.Format(time.RFC3339Nano),
			Count:              endpoint.Count,
			ObserverConfidence: 1.0,
			Sources: []ObservationSourceRef{
				{EngineRef: "observationengine/golang-http-profiler"},
			},
		},
	}
}

// mapConnectionToBehavior converts a ConnectionInfo to an ObservedBehavior (non-HTTP protocol interaction)
func mapConnectionToBehavior(sourceServiceName string, conn *ConnectionInfo) ObservedBehavior {
	sourceRef := fmt.Sprintf("workload:container/%s", sourceServiceName)

	// Fix destination: if destination equals source, use protocol name instead
	// This handles cases where destination resolution failed
	destName := conn.Destination
	if destName == sourceServiceName {
		destName = conn.Protocol
	}

	destRef := fmt.Sprintf("workload:container/%s", destName)

	// Generate stable, readable behavior ID
	behaviorID := fmt.Sprintf("behavior:%s:tcp:%s:%d:%s",
		sourceServiceName,
		destName,
		conn.Port,
		conn.Protocol)

	return ObservedBehavior{
		ID:        behaviorID,
		SourceRef: sourceRef,
		Destination: BehaviorDestination{
			WorkloadRef: destRef,
		},
		Facets: BehaviorFacets{
			Network: &NetworkFacet{
				Transport: "tcp",
				Port:      conn.Port,
			},
			Protocol: ProtocolFacet{
				Name:                     conn.Protocol,
				Category:                 conn.Category,
				ClassificationConfidence: float64(conn.Confidence) / 100.0,
				ClassificationReason:     conn.Reason,
			},
		},
		Evidence: BehaviorEvidence{
			FirstSeen:          conn.FirstSeen.Format(time.RFC3339Nano),
			LastSeen:           conn.LastSeen.Format(time.RFC3339Nano),
			Count:              conn.Count,
			ObserverConfidence: 1.0,
			Sources: []ObservationSourceRef{
				{EngineRef: "observationengine/golang-http-profiler"},
			},
		},
	}
}

// createInferredWorkload creates a WorkloadIdentity for a destination that wasn't directly profiled
// (e.g., infrastructure services like postgres, redis, nats)
func createInferredWorkload(destName, destImage string, destLabels map[string]string, firstSeen, lastSeen time.Time) WorkloadIdentity {
	workloadID := fmt.Sprintf("workload:container/%s", destName)

	workload := WorkloadIdentity{
		ID:          workloadID,
		DisplayName: destName,
		Labels:      destLabels,
		Evidence: WorkloadEvidence{
			FirstSeen: firstSeen.Format(time.RFC3339Nano),
			LastSeen:  lastSeen.Format(time.RFC3339Nano),
			Sources: []ObservationSourceRef{
				{EngineRef: "observationengine/golang-http-profiler"},
			},
		},
	}

	if destImage != "" {
		workload.Software = WorkloadSoftware{
			Image: destImage,
		}
	}

	return workload
}

// ========================================================================
// Validation: Ensure transformation completeness
// ========================================================================

// TransformationStats tracks counts before and after transformation
type TransformationStats struct {
	SourceServices    int
	SourceEndpoints   int
	SourceConnections int
	OutputWorkloads   int
	OutputBehaviors   int
	InferredWorkloads int
}

// validateTransformation checks that the transformation is complete and logs any discrepancies
func validateTransformation(services map[string]*ServiceProfile, result *ObservedBehaviors) TransformationStats {
	stats := TransformationStats{}

	// Count source data
	stats.SourceServices = len(services)
	for _, profile := range services {
		stats.SourceEndpoints += len(profile.Endpoints)
		stats.SourceConnections += len(profile.Connections)
	}

	// Count output data
	stats.OutputWorkloads = len(result.Spec.Workloads)
	stats.OutputBehaviors = len(result.Spec.Behaviors)

	// Count inferred workloads (workloads not from profiled services)
	inferredCount := 0
	profiledWorkloadIDs := make(map[string]bool)
	for _, profile := range services {
		workloadID := fmt.Sprintf("workload:container/%s", profile.Name)
		profiledWorkloadIDs[workloadID] = true
	}
	for _, workload := range result.Spec.Workloads {
		if !profiledWorkloadIDs[workload.ID] {
			inferredCount++
		}
	}
	stats.InferredWorkloads = inferredCount

	// Validation checks
	expectedBehaviors := stats.SourceEndpoints + stats.SourceConnections
	if stats.OutputBehaviors != expectedBehaviors {
		log.Printf("validation warning: expected %d behaviors (endpoints+connections), got %d",
			expectedBehaviors, stats.OutputBehaviors)
	}

	expectedProfiledWorkloads := stats.SourceServices
	actualProfiledWorkloads := stats.OutputWorkloads - stats.InferredWorkloads
	if actualProfiledWorkloads != expectedProfiledWorkloads {
		log.Printf("validation warning: expected %d profiled workloads, got %d",
			expectedProfiledWorkloads, actualProfiledWorkloads)
	}

	// Log summary
	log.Printf("transformation complete: %d services → %d workloads (%d profiled, %d inferred), %d interactions → %d behaviors",
		stats.SourceServices,
		stats.OutputWorkloads,
		actualProfiledWorkloads,
		stats.InferredWorkloads,
		stats.SourceEndpoints+stats.SourceConnections,
		stats.OutputBehaviors)

	return stats
}

// transformToObservedBehaviors converts internal service map to ObservedBehaviors format
func (sm *ServiceMap) transformToObservedBehaviors(generatedAt time.Time) *ObservedBehaviors {
	// Generate a name based on timestamp
	nameSuffix := strings.ToLower(generatedAt.Format("2006-01-02t150405z"))

	// Phase 1: Map all profiled services to workloads
	workloads := []WorkloadIdentity{}
	workloadExists := make(map[string]bool)

	for _, profile := range sm.services {
		workload := mapServiceToWorkload(profile)
		workloadExists[workload.ID] = true
		workloads = append(workloads, workload)
	}

	// Phase 2: Map all interactions to behaviors
	behaviors := []ObservedBehavior{}

	for _, profile := range sm.services {
		// Map HTTP endpoints
		for _, endpoint := range profile.Endpoints {
			behavior := mapEndpointToBehavior(profile.Name, endpoint)
			behaviors = append(behaviors, behavior)

			// Phase 3: Infer workloads for unprofiled HTTP destinations
			if !workloadExists[behavior.Destination.WorkloadRef] {
				// Extract destination name from the workloadRef (strip "workload:container/" prefix)
				destName := strings.TrimPrefix(behavior.Destination.WorkloadRef, "workload:container/")
				inferredWorkload := createInferredWorkload(destName, endpoint.DestImage, endpoint.DestLabels, endpoint.FirstSeen, endpoint.LastSeen)

				workloadExists[inferredWorkload.ID] = true
				workloads = append(workloads, inferredWorkload)
			}
		}

		// Map non-HTTP connections
		for _, conn := range profile.Connections {
			behavior := mapConnectionToBehavior(profile.Name, conn)
			behaviors = append(behaviors, behavior)

			// Phase 3: Infer workloads for unprofiled connection destinations
			if !workloadExists[behavior.Destination.WorkloadRef] {
				// Extract destination name from the workloadRef (strip "workload:container/" prefix)
				destName := strings.TrimPrefix(behavior.Destination.WorkloadRef, "workload:container/")
				inferredWorkload := createInferredWorkload(destName, conn.DestImage, conn.DestLabels, conn.FirstSeen, conn.LastSeen)

				workloadExists[inferredWorkload.ID] = true
				workloads = append(workloads, inferredWorkload)
			}
		}
	}

	return &ObservedBehaviors{
		APIVersion: "adi.dev/v1alpha1",
		Kind:       "ObservedBehaviors",
		Metadata: ObservedBehaviorsMetadata{
			Name: fmt.Sprintf("http-profiler-local-%s", nameSuffix),
		},
		Spec: ObservedBehaviorsSpec{
			GeneratedAt: generatedAt.Format(time.RFC3339Nano),
			ObservationEngines: []ObservationEngineRef{
				{Ref: "observationengine/golang-http-profiler"},
			},
			Environment: EnvironmentInfo{
				Observed: "local",
			},
			Workloads: workloads,
			Behaviors: behaviors,
		},
	}
}

// writeToFileLocked writes the service map to disk in ObservedBehaviors format (must hold lock)
func (sm *ServiceMap) writeToFileLocked() error {
	generatedAt := time.Now()

	// Transform to ObservedBehaviors format
	observedBehaviors := sm.transformToObservedBehaviors(generatedAt)

	// Validate transformation completeness
	validateTransformation(sm.services, observedBehaviors)

	data, err := yaml.Marshal(observedBehaviors)
	if err != nil {
		return fmt.Errorf("marshal observed behaviors: %w", err)
	}

	tmpPath := sm.outputPath + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0o644); err != nil {
		return fmt.Errorf("write temp observed behaviors: %w", err)
	}

	if err := os.Rename(tmpPath, sm.outputPath); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("rename observed behaviors: %w", err)
	}

	return nil
}

// Flush writes the current state to disk immediately
func (sm *ServiceMap) Flush() error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if sm.debounceTimer != nil {
		sm.debounceTimer.Stop()
		sm.debounceTimer = nil
	}

	if len(sm.services) == 0 {
		return nil
	}

	err := sm.writeToFileLocked()
	sm.dirty = false
	return err
}

// Close stops the service map and performs final flush
func (sm *ServiceMap) Close() error {
	return sm.Flush()
}
