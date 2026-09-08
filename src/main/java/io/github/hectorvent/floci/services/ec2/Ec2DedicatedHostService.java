package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.DedicatedHost;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Mac Dedicated Host control-plane state. No physical Mac or billing is emulated. */
@ApplicationScoped
public class Ec2DedicatedHostService {
    private static final String STORE_FILE = "ec2-dedicated-hosts.json";
    private static final Duration MINIMUM_ALLOCATION = Duration.ofHours(24);
    private static final int MAX_BATCH = 100;
    private static final int MAX_CLIENT_TOKEN_LENGTH = 64;
    private static final String AVAILABLE = "available";
    private static final String PENDING = "pending";
    private static final String RELEASED = "released";
    private static final Set<String> MAC_TYPES = Set.of("mac1.metal", "mac2.metal", "mac2-m2.metal",
            "mac2-m2pro.metal", "mac2-m1ultra.metal", "mac-m4.metal", "mac-m4pro.metal",
            "mac-m4max.metal", "mac-m3ultra.metal");
    private static final Set<String> INACTIVE_STATES = Set.of("stopped", "terminated");
    private static final Set<String> FILTER_NAMES = Set.of("host-id", "state", "availability-zone",
            "instance-type", "tag-key");

    private final AccountAwareStorageBackend<DedicatedHost> hosts;
    private final AccountAwareStorageBackend<Instance> instances;
    private final Clock clock;
    private final Duration scrubDuration;

    @Inject
    public Ec2DedicatedHostService(StorageFactory factory, Clock clock, EmulatorConfig config) {
        this(factory.create("ec2", STORE_FILE, new TypeReference<Map<String, DedicatedHost>>() {}),
                factory.create("ec2", Ec2Service.INSTANCE_STORE_FILE,
                        new TypeReference<Map<String, Instance>>() {}),
                clock, config.services().ec2().dedicatedHostScrubDuration());
    }

    Ec2DedicatedHostService(AccountAwareStorageBackend<DedicatedHost> hosts,
                            AccountAwareStorageBackend<Instance> instances,
                            Clock clock, Duration scrubDuration) {
        if (scrubDuration.isNegative()) {
            throw new IllegalArgumentException("Configure dedicated hosts: scrub duration cannot be negative");
        }
        this.hosts = hosts;
        this.instances = instances;
        this.clock = clock;
        this.scrubDuration = scrubDuration;
    }

    public record Occupant(String instanceId, String instanceType, String ownerId) {}
    public record View(DedicatedHost host, String state, List<Occupant> instances) {
        public View { instances = List.copyOf(instances); }
    }
    public record Page(List<View> hosts, String nextToken) {
        public Page { hosts = List.copyOf(hosts); }
    }
    public record ReleaseFailure(String resourceId, String code, String message) {}
    public record ReleaseResult(List<String> successful, List<ReleaseFailure> unsuccessful) {
        public ReleaseResult {
            successful = List.copyOf(successful);
            unsuccessful = List.copyOf(unsuccessful);
        }
    }

    public synchronized List<String> allocate(String region, DedicatedHost.Allocation request, String clientToken) {
        validateAllocation(region, request, clientToken);
        String token = clientToken == null ? UUID.randomUUID().toString() : clientToken;
        String namespace = hosts.accountId() + ":" + region + ":" + token;
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < request.quantity(); index++) {
            String hostId = "h-" + digest(namespace + ":" + index).substring(0, 17);
            ids.add(hostId);
            hosts.get(key(region, hostId)).ifPresent(existing -> {
                if (!existing.allocation().equals(request)) {
                    throw new AwsException("IdempotentParameterMismatch", "AllocateHosts: client token parameters differ", 400);
                }
            });
        }
        // One token has one namespace even when a retry changes Quantity and would otherwise visit fewer IDs.
        for (DedicatedHost existing : hosts.scan(k -> k.startsWith(region + "::"))) {
            if (token.equals(existing.clientToken()) && !existing.allocation().equals(request)) {
                throw new AwsException("IdempotentParameterMismatch", "AllocateHosts: client token parameters differ", 400);
            }
        }
        Instant allocatedAt = clock.instant();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            if (hosts.get(key(region, id)).isEmpty()) {
                DedicatedHost host = new DedicatedHost(id, hosts.accountId(), region, request, token, index,
                        allocatedAt, null, null, request.tags());
                hosts.put(key(region, id), host);
            }
        }
        return List.copyOf(ids);
    }

    private void validateAllocation(String region, DedicatedHost.Allocation request, String token) {
        if (request.availabilityZone() == null || !request.availabilityZone().matches(java.util.regex.Pattern.quote(region) + "[a-z]")) {
            throw new AwsException("InvalidParameterValue", "AllocateHosts: AvailabilityZone must belong to the request region", 400);
        }
        if (request.instanceType() == null || !MAC_TYPES.contains(request.instanceType())) {
            throw new AwsException("UnsupportedOperation", "AllocateHosts: only Mac instance types are supported", 400);
        }
        if (request.quantity() < 1 || request.quantity() > MAX_BATCH) {
            throw new AwsException("InvalidParameterValue", "AllocateHosts: Quantity must be between 1 and " + MAX_BATCH, 400);
        }
        if (token != null && (token.isBlank() || token.length() > MAX_CLIENT_TOKEN_LENGTH)) {
            throw new AwsException("InvalidParameterValue", "AllocateHosts: invalid ClientToken", 400);
        }
    }

    public synchronized Page describe(String region, List<String> ids, Map<String, List<String>> filters,
                                      int maxResults, String nextToken) {
        if (maxResults < 5 || maxResults > 500) {
            throw new AwsException("InvalidParameterValue", "DescribeHosts: MaxResults must be between 5 and 500", 400);
        }
        for (var filter : filters.entrySet()) {
            if ((!FILTER_NAMES.contains(filter.getKey()) && !filter.getKey().startsWith("tag:")) || filter.getValue().isEmpty()) {
                throw new AwsException("InvalidFilter", "DescribeHosts: unsupported or empty filter " + filter.getKey(), 400);
            }
        }
        for (String id : ids) { requireHost(region, id); }
        String queryIdentity = digest(hosts.accountId() + ":" + region + ":" + ids.stream().sorted().toList()
                + ":" + new TreeMap<>(filters));
        String cursor = decodeCursor(nextToken, queryIdentity);
        Map<String, List<Occupant>> occupancy = occupancy(region);
        List<View> matching = hosts.scan(k -> k.startsWith(region + "::")).stream()
                .filter(h -> ids.isEmpty() || ids.contains(h.hostId()))
                .map(h -> new View(h, state(h), occupancy.getOrDefault(h.hostId(), List.of())))
                .filter(v -> matches(v, filters))
                .filter(v -> v.host().hostId().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(v -> v.host().hostId())).toList();
        List<View> page = matching.stream().limit(maxResults).toList();
        String continuation = null;
        if (matching.size() > maxResults) {
            String text = queryIdentity + ":" + page.getLast().host().hostId();
            continuation = Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(page, continuation);
    }

    private String decodeCursor(String token, String queryIdentity) {
        if (token == null) { return ""; }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new AwsException("InvalidPaginationToken", "DescribeHosts: invalid NextToken encoding", 400);
        }
        String prefix = queryIdentity + ":";
        if (!decoded.startsWith(prefix) || !decoded.substring(prefix.length()).matches("h-[0-9a-f]{17}")) {
            throw new AwsException("InvalidPaginationToken", "DescribeHosts: NextToken does not match the request", 400);
        }
        return decoded.substring(prefix.length());
    }

    private boolean matches(View view, Map<String, List<String>> filters) {
        DedicatedHost host = view.host();
        for (var filter : filters.entrySet()) {
            String name = filter.getKey();
            if (name.equals("tag-key")) {
                if (filter.getValue().stream().noneMatch(host.tags()::containsKey)) { return false; }
                continue;
            }
            String value = switch (name) {
                case "host-id" -> host.hostId();
                case "state" -> view.state();
                case "availability-zone" -> host.allocation().availabilityZone();
                case "instance-type" -> host.allocation().instanceType();
                default -> host.tags().get(name.substring("tag:".length()));
            };
            if (value == null || !filter.getValue().contains(value)) { return false; }
        }
        return true;
    }

    public synchronized ReleaseResult release(String region, List<String> ids) {
        if (ids.isEmpty() || ids.size() > MAX_BATCH) {
            throw new AwsException("InvalidParameterValue", "ReleaseHosts: provide between 1 and " + MAX_BATCH + " host IDs", 400);
        }
        Map<String, List<Occupant>> occupancy = occupancy(region);
        List<String> successful = new ArrayList<>();
        List<ReleaseFailure> failures = new ArrayList<>();
        for (String id : ids) {
            DedicatedHost host = hosts.get(key(region, id)).orElse(null);
            if (host == null) {
                failures.add(new ReleaseFailure(id, "Client.InvalidHostID.NotFound", "ReleaseHosts: host not found"));
            } else if (!occupancy.getOrDefault(id, List.of()).isEmpty()) {
                failures.add(new ReleaseFailure(id, "Client.InvalidHost.Occupied", "ReleaseHosts: host is occupied"));
            } else if (!state(host).equals(AVAILABLE) || clock.instant().isBefore(host.allocationTime().plus(MINIMUM_ALLOCATION))) {
                failures.add(new ReleaseFailure(id, "Client.OperationNotPermitted", "ReleaseHosts: host is not eligible for release"));
            } else {
                hosts.put(key(region, id), host.releasedAt(clock.instant()));
                successful.add(id);
            }
        }
        return new ReleaseResult(successful, failures);
    }

    /** Shares the monitor with release, so an empty-host check cannot race a launch. */
    public synchronized <T> T launch(String region, String hostId, String instanceType,
                                     Function<DedicatedHost, T> createInstance) {
        DedicatedHost host = requireHost(region, hostId);
        if (!state(host).equals(AVAILABLE) || !occupancy(region).getOrDefault(hostId, List.of()).isEmpty()) {
            throw new AwsException("InsufficientCapacityOnHost", "RunInstances: dedicated host is not available", 400);
        }
        if (!host.allocation().instanceType().equals(instanceType)) {
            throw new AwsException("InvalidParameterValue", "RunInstances: instance type does not match dedicated host", 400);
        }
        return createInstance.apply(host);
    }

    public synchronized void instanceStopped(String region, Instance instance) {
        String id = instanceHostId(instance);
        if (id == null) { return; }
        DedicatedHost host = requireHost(region, id);
        if (host.releaseTime() != null) { return; }
        if (state(host).equals(PENDING)) { return; }
        hosts.put(key(region, id), host.scrubbingUntil(clock.instant().plus(scrubDuration)));
    }

    public synchronized Map<String, String> tags(String region, String hostId) {
        return requireHost(region, hostId).tags();
    }

    public synchronized void replaceTags(String region, String hostId, Map<String, String> tags) {
        DedicatedHost host = requireHost(region, hostId);
        hosts.put(key(region, hostId), host.withTags(tags));
    }

    private DedicatedHost requireHost(String region, String hostId) {
        return hosts.get(key(region, hostId)).orElseThrow(() ->
                new AwsException("InvalidHostID.NotFound", "Look up dedicated host: host not found", 400));
    }

    private String state(DedicatedHost host) {
        if (host.releaseTime() != null) { return RELEASED; }
        if (host.scrubUntil() != null && clock.instant().isBefore(host.scrubUntil())) { return PENDING; }
        return AVAILABLE;
    }

    private Map<String, List<Occupant>> occupancy(String region) {
        Map<String, List<Occupant>> result = new LinkedHashMap<>();
        for (Instance instance : instances.scan(k -> k.startsWith(region + "::"))) {
            String hostId = instanceHostId(instance);
            if (hostId == null || (instance.getState() != null && INACTIVE_STATES.contains(instance.getState().getName()))) {
                continue;
            }
            Occupant occupant = new Occupant(instance.getInstanceId(), instance.getInstanceType(), hosts.accountId());
            result.computeIfAbsent(hostId, ignored -> new ArrayList<>()).add(occupant);
        }
        return result;
    }

    static String instanceHostId(Instance instance) {
        return instance.getPlacement() == null ? null : instance.getPlacement().getHostId();
    }

    private static String key(String region, String id) { return region + "::" + id; }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Hash dedicated host request: SHA-256 unavailable", error);
        }
    }
}
