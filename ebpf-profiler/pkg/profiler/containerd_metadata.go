//go:build linux

package profiler

import (
	"context"
	"fmt"
	"log"
	"net/netip"
	"os"
	"strings"
	"sync"

	"github.com/containerd/containerd"
	"github.com/containerd/containerd/namespaces"
)

// ContainerMetadata holds enriched metadata about a container
type ContainerMetadata struct {
	ContainerID   string
	ContainerName string
	Image         string
	ImageTag      string
	Service       string            // from com.docker.compose.service label
	Project       string            // from com.docker.compose.project label
	Labels        map[string]string // all container labels
	IPAddresses   []netip.Addr
	PortMappings  []PortMapping
}

// PortMapping represents a port mapping from host to container
type PortMapping struct {
	HostIP        netip.Addr
	HostPort      uint16
	ContainerPort uint16
	Protocol      string
}

// ContainerResolver resolves destination IPs to container metadata
type ContainerResolver struct {
	client              *containerd.Client
	namespace           string
	mu                  sync.RWMutex
	ipToContainer       map[netip.Addr]*ContainerMetadata
	containerIDToMeta   map[string]*ContainerMetadata // containerID -> metadata
	hostPortToContainer map[string]*ContainerMetadata // "hostIP:hostPort" -> container
	ctx                 context.Context
	cancel              context.CancelFunc
}

// NewContainerResolver creates a new container resolver
func NewContainerResolver(socketPath, namespace string) (*ContainerResolver, error) {
	client, err := containerd.New(socketPath)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to containerd at %s: %w", socketPath, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	ctx = namespaces.WithNamespace(ctx, namespace)

	resolver := &ContainerResolver{
		client:              client,
		namespace:           namespace,
		ipToContainer:       make(map[netip.Addr]*ContainerMetadata),
		containerIDToMeta:   make(map[string]*ContainerMetadata),
		hostPortToContainer: make(map[string]*ContainerMetadata),
		ctx:                 ctx,
		cancel:              cancel,
	}

	// Initial sync of all containers
	if err := resolver.syncContainers(); err != nil {
		client.Close()
		cancel()
		return nil, fmt.Errorf("initial container sync failed: %w", err)
	}

	// Start background event listener
	go resolver.watchContainerEvents()

	return resolver, nil
}

// Close cleans up the resolver
func (r *ContainerResolver) Close() error {
	r.cancel()
	if r.client != nil {
		return r.client.Close()
	}
	return nil
}

// syncContainers refreshes the IP and port mappings for all running containers
func (r *ContainerResolver) syncContainers() error {
	containers, err := r.client.Containers(r.ctx)
	if err != nil {
		return fmt.Errorf("list containers: %w", err)
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	// Clear existing mappings
	r.ipToContainer = make(map[netip.Addr]*ContainerMetadata)
	r.containerIDToMeta = make(map[string]*ContainerMetadata)
	r.hostPortToContainer = make(map[string]*ContainerMetadata)

	for _, container := range containers {
		if err := r.indexContainer(container); err != nil {
			log.Printf("warning: failed to index container %s: %v", container.ID(), err)
			continue
		}
	}

	log.Printf("containerd resolver: indexed %d containers with %d IPs and %d port mappings",
		len(containers), len(r.ipToContainer), len(r.hostPortToContainer))

	return nil
}

// indexContainer extracts metadata and indexes a single container
// Must be called with r.mu held
func (r *ContainerResolver) indexContainer(container containerd.Container) error {
	// Get container info
	info, err := container.Info(r.ctx)
	if err != nil {
		return fmt.Errorf("get container info: %w", err)
	}

	// Check if container has a running task
	task, err := container.Task(r.ctx, nil)
	if err != nil {
		// Container might not be running, skip
		return nil
	}

	status, err := task.Status(r.ctx)
	if err != nil || status.Status != containerd.Running {
		// Not running, skip
		return nil
	}

	// Extract labels
	labels := info.Labels
	service := labels["com.docker.compose.service"]
	project := labels["com.docker.compose.project"]

	// Parse image name and tag
	image := info.Image
	imageName, imageTag := splitImageTag(image)

	meta := &ContainerMetadata{
		ContainerID:   container.ID(),
		ContainerName: labels["nerdctl/name"], // nerdctl stores container name here
		Image:         imageName,
		ImageTag:      imageTag,
		Service:       service,
		Project:       project,
		Labels:        labels, // store all labels
		IPAddresses:   []netip.Addr{},
		PortMappings:  []PortMapping{},
	}

	// If nerdctl/name is empty, try other label conventions
	if meta.ContainerName == "" {
		meta.ContainerName = labels["io.kubernetes.container.name"]
	}
	if meta.ContainerName == "" {
		meta.ContainerName = container.ID()[:12] // fallback to short ID
	}

	// Index by container ID for source resolution via cgroup
	r.containerIDToMeta[container.ID()] = meta

	// Get network information from spec
	spec, err := container.Spec(r.ctx)
	if err != nil {
		return fmt.Errorf("get container spec: %w", err)
	}

	// Try multiple approaches to get network information

	// Approach 1: Check nerdctl-specific labels
	if ipStr := labels["nerdctl/networks"]; ipStr != "" {
		r.parseNetworkIPs(ipStr, meta)
	}

	// Approach 2: Check CNI network info from labels
	if ipStr := labels["nerdctl/ip-address"]; ipStr != "" {
		if ip, err := netip.ParseAddr(ipStr); err == nil {
			meta.IPAddresses = append(meta.IPAddresses, ip)
			r.ipToContainer[ip] = meta
		}
	}

	// Extract port mappings from labels
	if portsStr := labels["nerdctl/ports"]; portsStr != "" {
		r.parsePortMappings(portsStr, meta)
	}

	// Index by IP addresses
	for _, ip := range meta.IPAddresses {
		r.ipToContainer[ip] = meta
	}

	// Index by host port mappings
	for _, pm := range meta.PortMappings {
		key := fmt.Sprintf("%s:%d", pm.HostIP.String(), pm.HostPort)
		r.hostPortToContainer[key] = meta
	}

	// Also check for spec annotations that might contain network info
	if spec.Annotations != nil {
		if ipsStr := spec.Annotations["io.kubernetes.cri.sandbox-ips"]; ipsStr != "" {
			r.parseNetworkIPs(ipsStr, meta)
		}
	}

	// Read IPs directly from the task's network namespace
	taskPID := task.Pid()
	if taskPID > 0 {
		r.readIPsFromTaskNetNS(taskPID, meta)
	}

	return nil
}

// readIPsFromTaskNetNS reads IP addresses from a task's network namespace via /proc
func (r *ContainerResolver) readIPsFromTaskNetNS(pid uint32, meta *ContainerMetadata) {
	// Read IPv4 addresses from /proc/<pid>/net/fib_trie
	fibPath := fmt.Sprintf("/proc/%d/net/fib_trie", pid)
	fibData, err := readFile(fibPath)
	if err != nil {
		return
	}

	lines := strings.Split(string(fibData), "\n")

	for i := 0; i < len(lines)-1; i++ {
		line := lines[i]
		nextLine := lines[i+1]

		// Look for lines with IP addresses like:  |-- 10.4.1.49
		// followed by a line like:                   /32 host LOCAL
		if strings.Contains(line, "|--") {
			fields := strings.Fields(line)
			for _, field := range fields {
				// Try to parse as IP address
				if ip, err := netip.ParseAddr(field); err == nil {
					// Check if next line indicates this is a host address
					if strings.Contains(nextLine, "/32") && (strings.Contains(nextLine, "host") || strings.Contains(nextLine, "LOCAL")) {
						// Skip loopback and only add private IPs
						if !ip.IsLoopback() && ip.Is4() {
							// Ensure we only add private IPs (10.x, 172.16-31.x, 192.168.x)
							octets := ip.As4()
							if octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) || (octets[0] == 192 && octets[1] == 168) {
								meta.IPAddresses = append(meta.IPAddresses, ip)
								r.ipToContainer[ip] = meta
							}
						}
					}
				}
			}
		}
	}
}

// parseNetworkIPs extracts IP addresses from various label formats
func (r *ContainerResolver) parseNetworkIPs(ipStr string, meta *ContainerMetadata) {
	// Try comma-separated
	parts := strings.Split(ipStr, ",")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		// Handle formats like "bridge:10.4.0.2" or just "10.4.0.2"
		if colonIdx := strings.LastIndex(part, ":"); colonIdx > 0 && !strings.Contains(part[colonIdx:], ".") {
			// This looks like "network:ip"
			part = part[colonIdx+1:]
		}
		if ip, err := netip.ParseAddr(part); err == nil {
			meta.IPAddresses = append(meta.IPAddresses, ip)
			r.ipToContainer[ip] = meta
		}
	}
}

// parsePortMappings extracts port mappings from label format
func (r *ContainerResolver) parsePortMappings(portsStr string, meta *ContainerMetadata) {
	// nerdctl format is typically: "0.0.0.0:8080:80/tcp"
	// Parse each port mapping
	parts := strings.Split(portsStr, ",")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if pm := parsePortMapping(part); pm != nil {
			meta.PortMappings = append(meta.PortMappings, *pm)
			key := fmt.Sprintf("%s:%d", pm.HostIP.String(), pm.HostPort)
			r.hostPortToContainer[key] = meta
		}
	}
}

// parsePortMapping parses a single port mapping string
// Format: "hostIP:hostPort:containerPort/protocol"
func parsePortMapping(s string) *PortMapping {
	// Split by protocol
	protoIdx := strings.LastIndex(s, "/")
	protocol := "tcp"
	if protoIdx > 0 {
		protocol = s[protoIdx+1:]
		s = s[:protoIdx]
	}

	// Split by colons
	parts := strings.Split(s, ":")
	if len(parts) != 3 {
		return nil
	}

	hostIP, err := netip.ParseAddr(parts[0])
	if err != nil {
		// Try 0.0.0.0 as default
		hostIP = netip.MustParseAddr("0.0.0.0")
	}

	var hostPort, containerPort uint16
	if _, err := fmt.Sscanf(parts[1], "%d", &hostPort); err != nil {
		return nil
	}
	if _, err := fmt.Sscanf(parts[2], "%d", &containerPort); err != nil {
		return nil
	}

	return &PortMapping{
		HostIP:        hostIP,
		HostPort:      hostPort,
		ContainerPort: containerPort,
		Protocol:      protocol,
	}
}

// splitImageTag splits an image reference into name and tag
func splitImageTag(image string) (name, tag string) {
	// Remove digest if present (e.g., @sha256:...)
	if idx := strings.Index(image, "@"); idx > 0 {
		image = image[:idx]
	}

	// Split by last colon for tag
	if idx := strings.LastIndex(image, ":"); idx > 0 {
		// Make sure it's not part of a registry port (e.g., localhost:5000/image)
		if !strings.Contains(image[idx:], "/") {
			return image[:idx], image[idx+1:]
		}
	}

	return image, "latest"
}

// ResolveDestination resolves a destination IP:port to container metadata
func (r *ContainerResolver) ResolveDestination(destIP netip.Addr, destPort uint16) *ContainerMetadata {
	r.mu.RLock()
	defer r.mu.RUnlock()

	// First, try direct IP lookup (container-to-container)
	if meta := r.ipToContainer[destIP]; meta != nil {
		return meta
	}

	// Next, try host port mapping (calls via published ports)
	key := fmt.Sprintf("%s:%d", destIP.String(), destPort)
	if meta := r.hostPortToContainer[key]; meta != nil {
		return meta
	}

	// Also check 0.0.0.0 bindings
	key = fmt.Sprintf("0.0.0.0:%d", destPort)
	if meta := r.hostPortToContainer[key]; meta != nil {
		return meta
	}

	// Also check 127.0.0.1 for localhost calls
	if destIP.IsLoopback() {
		key = fmt.Sprintf("127.0.0.1:%d", destPort)
		if meta := r.hostPortToContainer[key]; meta != nil {
			return meta
		}
	}

	return nil
}

// ResolvePIDToContainer resolves a PID to its container metadata by reading cgroup
func (r *ContainerResolver) ResolvePIDToContainer(pid uint32) *ContainerMetadata {
	cgroupPath := fmt.Sprintf("/proc/%d/cgroup", pid)
	data, err := readFile(cgroupPath)
	if err != nil {
		return nil
	}

	// Parse cgroup to extract container ID
	containerID := extractContainerIDFromCgroup(string(data))
	if containerID == "" {
		return nil
	}

	// Look up container by ID
	r.mu.RLock()
	defer r.mu.RUnlock()

	// Direct lookup by container ID
	if meta, ok := r.containerIDToMeta[containerID]; ok {
		return meta
	}

	// Try partial match (in case we only got a short ID)
	for fullID, meta := range r.containerIDToMeta {
		if strings.HasPrefix(fullID, containerID) {
			return meta
		}
	}

	return nil
}

// extractContainerIDFromCgroup extracts container ID from cgroup content
func extractContainerIDFromCgroup(cgroup string) string {
	// Look for container ID patterns in cgroup paths
	// Common formats:
	// - /docker/<containerID>
	// - /system.slice/docker-<containerID>.scope
	// - /system.slice/nerdctl-<containerID>.scope (rootless nerdctl)
	// - /user.slice/user-UID.slice/user@UID.service/user.slice/nerdctl-<containerID>.scope (rootless nerdctl)
	// - /kubepods/.../pod<podID>/<containerID>
	lines := strings.Split(cgroup, "\n")
	for _, line := range lines {
		// Look for typical container ID patterns (64-char hex)
		parts := strings.Split(line, "/")
		for _, part := range parts {
			// Remove common prefixes and suffixes
			part = strings.TrimPrefix(part, "docker-")
			part = strings.TrimPrefix(part, "nerdctl-")
			part = strings.TrimSuffix(part, ".scope")

			// Check if it looks like a container ID (at least 12 chars, all hex)
			if len(part) >= 12 && isHexString(part) {
				return part
			}
		}
	}
	return ""
}

// isHexString checks if a string contains only hex characters
func isHexString(s string) bool {
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// watchContainerEvents listens for container lifecycle events and updates indexes
func (r *ContainerResolver) watchContainerEvents() {
	// Subscribe to containerd events
	eventCh, errCh := r.client.Subscribe(r.ctx, `topic=="/tasks/start"`, `topic=="/tasks/exit"`)

	log.Printf("containerd resolver: watching for container events")

	for {
		select {
		case <-r.ctx.Done():
			log.Printf("containerd resolver: event watcher stopping")
			return
		case err := <-errCh:
			if err != nil {
				log.Printf("containerd resolver: event error: %v", err)
			}
		case <-eventCh:
			// On any container event, resync all containers
			// This is a simple approach; a more efficient version would handle specific events
			if err := r.syncContainers(); err != nil {
				log.Printf("containerd resolver: resync failed: %v", err)
			}
		}
	}
}

// Helper function to read file (wraps os.ReadFile for testing)
var readFile = func(path string) ([]byte, error) {
	return os.ReadFile(path)
}
