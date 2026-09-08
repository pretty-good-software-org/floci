package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.DedicatedHost;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2DedicatedHostPersistenceTest {
    private static final String REGION = "us-east-1";
    private static final Instant ALLOCATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void allocationIdentityAndReleaseSurviveRestart(String mode, @TempDir Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        when(config.services().ec2().dedicatedHostScrubDuration()).thenReturn(Duration.ofSeconds(1));
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ec2")).thenReturn(mode);
        when(access.storageFlushInterval("ec2")).thenReturn(60000L);
        Map<String, String> tags = Map.of("purpose", "builder");
        DedicatedHost.Allocation allocation = new DedicatedHost.Allocation("us-east-1a", "mac2-m2.metal", 1, tags);
        Clock allocationClock = Clock.fixed(ALLOCATED_AT, ZoneOffset.UTC);
        String id;
        StorageFactory first = new StorageFactory(config, access);
        try {
            Ec2DedicatedHostService service = new Ec2DedicatedHostService(first, allocationClock, config);
            id = service.allocate(REGION, allocation, "persisted-lease").getFirst();
        } finally {
            first.shutdownAll();
        }
        Clock releaseClock = Clock.fixed(ALLOCATED_AT.plus(Duration.ofHours(24)), ZoneOffset.UTC);
        StorageFactory second = new StorageFactory(config, access);
        try {
            Ec2DedicatedHostService restarted = new Ec2DedicatedHostService(second, releaseClock, config);
            assertEquals(List.of(id), restarted.allocate(REGION, allocation, "persisted-lease"),
                    "Retry after restart must not create another host");
            assertEquals(List.of(id), restarted.release(REGION, List.of(id)).successful(),
                    "Allocation timestamp must survive restart so the original lease can expire");
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = new StorageFactory(config, access);
        try {
            Ec2DedicatedHostService restarted = new Ec2DedicatedHostService(third, releaseClock, config);
            var page = restarted.describe(REGION, List.of(id), Map.of(), 100, null);
            assertEquals("released", page.hosts().getFirst().state(), "Released state must survive restart");
            assertEquals(tags, page.hosts().getFirst().host().tags(), "Ownership tags must survive serialization");
            third.clearAll();
            assertTrue(restarted.describe(REGION, List.of(), Map.of(), 100, null).hosts().isEmpty(),
                    "Standard emulator reset must clear the host store");
        } finally {
            third.shutdownAll();
        }
    }
}
