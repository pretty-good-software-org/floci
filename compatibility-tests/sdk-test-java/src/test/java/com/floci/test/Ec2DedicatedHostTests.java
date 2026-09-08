package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Requires a disposable emulator: host records retain their minimum lease until its state is discarded. */
class Ec2DedicatedHostTests {
    private static Ec2Client ec2;

    @BeforeAll
    static void setup() {
        Set<String> emulatorHosts = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "floci");
        assertThat(TestFixtures.isRealAws()).as("These tests must never allocate paid AWS hosts").isFalse();
        assertThat(emulatorHosts).as("Dedicated host tests require a local emulator endpoint")
                .contains(TestFixtures.endpoint().getHost());
        ec2 = TestFixtures.ec2Client();
    }

    @AfterAll
    static void closeClient() {
        if (ec2 != null) { ec2.close(); }
    }

    private AllocateHostsRequest allocation(String token, int quantity) {
        Tag tag = Tag.builder().key("test-run").value(token).build();
        TagSpecification tags = TagSpecification.builder().resourceType(ResourceType.DEDICATED_HOST).tags(tag).build();
        return AllocateHostsRequest.builder().instanceType("mac2-m2.metal").availabilityZone("us-east-1a")
                .quantity(quantity).autoPlacement(AutoPlacement.OFF).clientToken(token).tagSpecifications(tags).build();
    }

    @Test
    void sdkDecodesHostIdentityTagsAndPagination() {
        String token = UUID.randomUUID().toString();
        AllocateHostsRequest request = allocation(token, 6);
        List<String> ids = ec2.allocateHosts(request).hostIds();
        assertThat(ec2.allocateHosts(request).hostIds()).as("Retry preserves allocated IDs").isEqualTo(ids);
        Filter filter = Filter.builder().name("tag:test-run").values(token).build();
        DescribeHostsRequest firstRequest = DescribeHostsRequest.builder().filter(filter).maxResults(5).build();
        DescribeHostsResponse first = ec2.describeHosts(firstRequest);
        assertThat(first.hosts()).hasSize(5);
        assertThat(first.nextToken()).isNotBlank();
        Host host = first.hosts().get(0);
        assertThat(host.ownerId()).isEqualTo("000000000000");
        assertThat(host.allocationTime()).isNotNull();
        assertThat(host.hostProperties().instanceType()).isEqualTo("mac2-m2.metal");
        assertThat(host.availableCapacity().availableInstanceCapacity().get(0).totalCapacity()).isEqualTo(1);
        DescribeHostsRequest next = firstRequest.toBuilder().nextToken(first.nextToken()).build();
        DescribeHostsResponse second = ec2.describeHosts(next);
        assertThat(second.hosts()).hasSize(1);
        assertThat(second.nextToken()).isNull();
    }

    @Test
    void sdkDecodesPlacementOccupancyAndReleaseFailures() {
        AllocateHostsRequest allocation = allocation(UUID.randomUUID().toString(), 1);
        String hostId = ec2.allocateHosts(allocation).hostIds().get(0);
        Placement placement = Placement.builder().hostId(hostId).tenancy(Tenancy.HOST).build();
        RunInstancesRequest launch = RunInstancesRequest.builder().imageId("ami-ubuntu2404-arm64")
                .instanceType(InstanceType.MAC2_M2_METAL).minCount(1).maxCount(1).placement(placement).build();
        String instanceId = ec2.runInstances(launch).instances().get(0).instanceId();
        try {
            DescribeHostsRequest lookup = DescribeHostsRequest.builder().hostIds(hostId).build();
            Host host = ec2.describeHosts(lookup).hosts().get(0);
            assertThat(host.instances()).extracting(HostInstance::instanceId).containsExactly(instanceId);
            ReleaseHostsRequest release = ReleaseHostsRequest.builder().hostIds(hostId).build();
            ReleaseHostsResponse result = ec2.releaseHosts(release);
            assertThat(result.successful()).isEmpty();
            assertThat(result.unsuccessful()).hasSize(1);
            assertThat(result.unsuccessful().get(0).resourceId()).isEqualTo(hostId);
            assertThat(result.unsuccessful().get(0).error().code()).isNotBlank();
        } finally {
            TerminateInstancesRequest terminate = TerminateInstancesRequest.builder().instanceIds(instanceId).build();
            ec2.terminateInstances(terminate);
        }
    }
}
