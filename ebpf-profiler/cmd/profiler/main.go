//go:build linux

package main

import (
	"context"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/colinjlacy/golang-ast-inspection/ebpf-profiler/pkg/profiler"
)

func main() {
	port := uint16(envAsInt("HTTP_PORT", 8080))
	output := envOrDefault("OUTPUT_PATH", "/var/log/ebpf_http_profiler.log")
	envOutput := envOrDefault("ENV_OUTPUT_PATH", "/var/log/ebpf_http_env.yaml")
	serviceMapOutput := envOrDefault("SERVICE_MAP_PATH", "")

	// Parse comma-separated prefix list
	var envPrefixes []string
	if prefixList := os.Getenv("ENV_PREFIX_LIST"); prefixList != "" {
		for _, prefix := range strings.Split(prefixList, ",") {
			trimmed := strings.TrimSpace(prefix)
			if trimmed != "" {
				envPrefixes = append(envPrefixes, trimmed)
			}
		}
	}

	// Parse comma-separated ADI_PROFILE allowed values
	var adiProfileAllowed []string
	if allowedList := os.Getenv("ADI_PROFILE_ALLOWED"); allowedList != "" {
		for _, allowed := range strings.Split(allowedList, ",") {
			trimmed := strings.TrimSpace(allowed)
			if trimmed != "" {
				adiProfileAllowed = append(adiProfileAllowed, trimmed)
			}
		}
	}

	// Container runtime configuration
	containerdSocket := envOrDefault("CONTAINERD_SOCKET", "")
	containerdNamespace := envOrDefault("CONTAINERD_NAMESPACE", "default")

	if err := profiler.NewRunner(port, output, envOutput, serviceMapOutput, envPrefixes, adiProfileAllowed, containerdSocket, containerdNamespace).Run(context.Background()); err != nil {
		log.Fatalf("profiler failed: %v", err)
	}
}

func envAsInt(key string, def int) int {
	if val := os.Getenv(key); val != "" {
		if n, err := strconv.Atoi(val); err == nil {
			return n
		}
	}
	return def
}

func envOrDefault(key, def string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return def
}
