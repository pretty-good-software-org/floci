package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record DedicatedHost(String hostId, String ownerId, String region, Allocation allocation,
                            String clientToken, int batchIndex, Instant allocationTime,
                            Instant releaseTime, Instant scrubUntil, Map<String, String> tags) {
    public DedicatedHost {
        tags = Map.copyOf(tags);
    }

    @RegisterForReflection
    public record Allocation(String availabilityZone, String instanceType, int quantity,
                             Map<String, String> tags) {
        public Allocation {
            tags = Map.copyOf(tags);
        }
    }

    public DedicatedHost releasedAt(Instant instant) {
        return new DedicatedHost(hostId, ownerId, region, allocation, clientToken, batchIndex,
                allocationTime, instant, scrubUntil, tags);
    }

    public DedicatedHost scrubbingUntil(Instant instant) {
        return new DedicatedHost(hostId, ownerId, region, allocation, clientToken, batchIndex,
                allocationTime, releaseTime, instant, tags);
    }

    public DedicatedHost withTags(Map<String, String> replacement) {
        return new DedicatedHost(hostId, ownerId, region, allocation, clientToken, batchIndex,
                allocationTime, releaseTime, scrubUntil, replacement);
    }
}
