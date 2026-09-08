package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.ec2.model.DedicatedHost;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2DedicatedHostServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TYPE = "mac2-m2.metal";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private AccountAwareStorageBackend<DedicatedHost> hosts;
    private AccountAwareStorageBackend<Instance> instances;
    private Clock clock;
    private Ec2DedicatedHostService service;

    @BeforeEach
    void setUp() {
        hosts = AccountAwareStorageBackend.inMemory(ACCOUNT);
        instances = AccountAwareStorageBackend.inMemory(ACCOUNT);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        service = new Ec2DedicatedHostService(hosts, instances, clock, Duration.ofSeconds(1));
    }

    private DedicatedHost.Allocation request(int quantity) {
        Map<String, String> tags = Map.of("purpose", "builder");
        return new DedicatedHost.Allocation("us-east-1a", TYPE, quantity, tags);
    }

    private String allocate() {
        return service.allocate(REGION, request(1), "lease").getFirst();
    }

    private Ec2DedicatedHostService.Page describe() {
        return service.describe(REGION, List.of(), Map.of(), 100, null);
    }

    private Instance occupiedHost(String hostId) {
        Placement placement = new Placement("us-east-1a");
        placement.setHostId(hostId);
        placement.setTenancy("host");
        Instance instance = new Instance();
        instance.setInstanceId("i-0123456789abcdef0");
        instance.setInstanceType(TYPE);
        instance.setRegion(REGION);
        instance.setPlacement(placement);
        instance.setState(InstanceState.running());
        instances.put(REGION + "::" + instance.getInstanceId(), instance);
        return instance;
    }

    @Test
    void emptyInventory() {
        assertTrue(describe().hosts().isEmpty(), "New store must contain no hosts");
    }

    @Test
    void retryReturnsSameHostsWithoutChangingAllocationTime() {
        List<String> original = service.allocate(REGION, request(2), "retry");
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        List<String> retried = service.allocate(REGION, request(2), "retry");
        assertEquals(original, retried, "Idempotency must preserve IDs and order");
        assertTrue(describe().hosts().stream().allMatch(h -> h.host().allocationTime().equals(START)),
                "Retry must not reset the lease clock");
    }

    @Test
    void retryRejectsChangedQuantity() {
        service.allocate(REGION, request(2), "retry");
        AwsException error = assertThrows(AwsException.class, () -> service.allocate(REGION, request(1), "retry"));
        assertTrue(error.getMessage().contains("client token parameters differ"), "Changed request must be rejected");
        assertEquals(2, describe().hosts().size(), "Mismatch cannot change inventory");
    }

    @Test
    void changedTagsDoNotChangeAllocationIdempotency() {
        String id = allocate();
        service.replaceTags(REGION, id, Map.of("purpose", "changed"));
        assertEquals(List.of(id), service.allocate(REGION, request(1), "lease"),
                "Idempotency compares original allocation inputs, not current tags");
    }

    @Test
    void releaseBeforeMinimumFails() {
        String id = allocate();
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)).minusMillis(1));
        var result = service.release(REGION, List.of(id));
        assertTrue(result.successful().isEmpty(), "Lease cannot be released early");
        assertEquals(id, result.unsuccessful().getFirst().resourceId(), "Failure must identify the host");
    }

    @Test
    void eligibleReleasePersistsItsState() {
        String id = allocate();
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        var result = service.release(REGION, List.of(id));
        assertEquals(List.of(id), result.successful(), "Eligible host must release");
        assertEquals("released", describe().hosts().getFirst().state(), "Release must remain describable");
    }

    @Test
    void occupiedHostCannotRelease() {
        String id = allocate();
        occupiedHost(id);
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        var result = service.release(REGION, List.of(id));
        assertEquals("Client.InvalidHost.Occupied", result.unsuccessful().getFirst().code(),
                "Must return AWS's documented occupied-host result");
    }

    @Test
    void stopStartsScrubbingAndReleaseWaitsForIt() {
        String id = allocate();
        Instance instance = occupiedHost(id);
        instance.setState(InstanceState.stopped());
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        service.instanceStopped(REGION, instance);
        assertEquals("pending", describe().hosts().getFirst().state(), "Stopped Mac host must scrub");
        assertTrue(service.release(REGION, List.of(id)).successful().isEmpty(), "Pending host cannot release");
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)).plusSeconds(1));
        assertEquals(List.of(id), service.release(REGION, List.of(id)).successful(), "Scrubbed host can release");
    }

    @Test
    void mixedReleaseResultsDoNotHideFailure() {
        String id = allocate();
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        var result = service.release(REGION, List.of("h-missing", id));
        assertEquals(List.of(id), result.successful(), "Valid host must release despite another host's failure");
        assertEquals("h-missing", result.unsuccessful().getFirst().resourceId(), "Missing host remains a failure");
    }

    @Test
    void releasedHostCannotLaunchAgain() {
        String id = allocate();
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        service.release(REGION, List.of(id));
        assertThrows(AwsException.class, () -> service.launch(REGION, id, TYPE, h -> fail("Must not launch")));
    }

    @Test
    void wrongTypeCannotLaunch() {
        String id = allocate();
        assertThrows(AwsException.class, () -> service.launch(REGION, id, "mac-m4.metal", h -> fail("Must not launch")));
    }

    @Test
    void failedLaunchDoesNotConsumeCapacity() {
        String id = allocate();
        assertThrows(IllegalStateException.class, () -> service.launch(REGION, id, TYPE, h -> {
            throw new IllegalStateException("RunInstances: validation failed");
        }));
        String result = service.launch(REGION, id, TYPE, DedicatedHost::hostId);
        assertEquals(id, result, "Failed launch must not leave a ghost reservation");
    }

    @Test
    void concurrentLaunchesCannotClaimTheSameHost() throws Exception {
        String id = allocate();
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> launch = () -> {
                try {
                    service.launch(REGION, id, TYPE, h -> occupiedHost(id));
                    return true;
                } catch (AwsException expected) {
                    return false;
                }
            };
            var results = executor.invokeAll(List.of(launch, launch));
            int succeeded = (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0);
            assertEquals(1, succeeded, "Exactly one concurrent launch may occupy a single-instance host");
        }
    }

    @Test
    void paginationRemainsScopedToItsQuery() {
        service.allocate(REGION, request(6), "pages");
        var page = service.describe(REGION, List.of(), Map.of(), 5, null);
        assertEquals(5, page.hosts().size(), "First page must respect MaxResults");
        assertEquals(1, service.describe(REGION, List.of(), Map.of(), 5, page.nextToken()).hosts().size(),
                "Second page must contain the remaining host");
        Map<String, List<String>> filters = Map.of("state", List.of("released"));
        assertThrows(AwsException.class, () -> service.describe(REGION, List.of(), filters, 5, page.nextToken()),
                "A token cannot be reused with a different query");
    }

    @Test
    void paginationCannotConfuseOneCommaValueWithTwoValues() {
        DedicatedHost.Allocation allocation = new DedicatedHost.Allocation("us-east-1a", TYPE, 6, Map.of("purpose", "a, b"));
        service.allocate(REGION, allocation, "comma-pages");
        Map<String, List<String>> firstFilter = Map.of("tag:purpose", List.of("a, b"));
        String token = service.describe(REGION, List.of(), firstFilter, 5, null).nextToken();
        Map<String, List<String>> differentFilter = Map.of("tag:purpose", List.of("a", "b"));
        assertThrows(AwsException.class, () -> service.describe(REGION, List.of(), differentFilter, 5, token),
                "Pagination identity must preserve value boundaries, not List.toString rendering");
    }

    @Test
    void regionIsolationAppliesToReadsAndRelease() {
        String id = allocate();
        assertTrue(service.describe("eu-west-1", List.of(), Map.of(), 100, null).hosts().isEmpty(),
                "Other region must not expose this host");
        assertTrue(service.release("eu-west-1", List.of(id)).successful().isEmpty(), "Other region cannot release it");
    }

    @Test
    void filtersCombineWithAndAcrossNames() {
        allocate();
        Map<String, List<String>> filters = Map.of("tag:purpose", List.of("builder"), "state", List.of("released"));
        assertTrue(service.describe(REGION, List.of(), filters, 100, null).hosts().isEmpty(),
                "Every filter name must match, not just one");
    }

    @Test
    void missingInstanceTagValueIsAnEmptyStringForIam() {
        Instance instance = occupiedHost(allocate());
        io.github.hectorvent.floci.services.ec2.model.Tag tag = new io.github.hectorvent.floci.services.ec2.model.Tag("purpose", null);
        instance.setTags(List.of(tag));
        assertEquals(Map.of("purpose", ""), service.resourceTags(REGION, instance.getInstanceId()),
                "An omitted tag value must not break authorization context construction");
    }

    @Test
    void aStoppedInstanceWithChangedTypeCannotRestartOnTheOldHost() {
        String id = allocate();
        Instance instance = occupiedHost(id);
        instance.setState(InstanceState.stopped());
        instance.setInstanceType("mac-m4.metal");
        assertThrows(AwsException.class, () -> service.startInstances(REGION, List.of(instance.getInstanceId()),
                () -> fail("A mismatched instance cannot restart")));
    }

    @Test
    void returnedTagsCannotMutateStoredOwnership() {
        String id = allocate();
        assertThrows(UnsupportedOperationException.class, () -> service.tags(REGION, id).put("purpose", "other"));
    }

    @Test
    void reconstructedServiceRetainsLeaseState() {
        String id = allocate();
        var restarted = new Ec2DedicatedHostService(hosts, instances, clock, Duration.ofSeconds(1));
        assertEquals(List.of(id), restarted.allocate(REGION, request(1), "lease"), "State must not live in service fields");
    }
}
