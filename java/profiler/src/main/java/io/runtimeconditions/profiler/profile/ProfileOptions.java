package io.runtimeconditions.profiler.profile;

import io.runtimeconditions.profiler.project.DiscoveryOptions;

public record ProfileOptions(String name, String workloadUri, String workloadVersion, DiscoveryOptions discoveryOptions) {
}
