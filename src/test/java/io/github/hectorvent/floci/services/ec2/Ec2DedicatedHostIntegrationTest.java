package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2DedicatedHostIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TYPE = "mac2-m2.metal";
    @InjectMock Clock clock;

    @BeforeEach
    void resetClock() {
        when(clock.instant()).thenReturn(START);
    }

    private Response requestAs(String account, String action, Map<String, ?> parameters) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/us-east-1/ec2/aws4_request";
        return given().header("Authorization", authorization).formParam("Action", action)
                .formParams(parameters).when().post("/");
    }

    private Response request(String action, Map<String, ?> parameters) {
        return requestAs(ACCOUNT, action, parameters);
    }

    private String allocate() {
        Map<String, Object> parameters = Map.of("InstanceType", TYPE, "AvailabilityZone", "us-east-1a",
                "Quantity", 1, "ClientToken", UUID.randomUUID().toString());
        return request("AllocateHosts", parameters).then().statusCode(200)
                .extract().path("AllocateHostsResponse.hostIdSet.item");
    }

    private String launch(String hostId) {
        Map<String, Object> parameters = Map.of("ImageId", "ami-ubuntu2404-arm64", "InstanceType", TYPE,
                "MinCount", 1, "MaxCount", 1, "Placement.HostId", hostId, "Placement.Tenancy", "host");
        return request("RunInstances", parameters).then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.placement.hostId", equalTo(hostId))
                .body("RunInstancesResponse.instancesSet.item.placement.tenancy", equalTo("host"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    void describeIncludesHostOwnershipAndHardware() {
        String hostId = allocate();
        request("DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.hostId", equalTo(hostId))
                .body("DescribeHostsResponse.hostSet.item.ownerId", equalTo(ACCOUNT))
                .body("DescribeHostsResponse.hostSet.item.hostProperties.instanceType", equalTo(TYPE))
                .body("DescribeHostsResponse.hostSet.item.state", equalTo("available"))
                .body("DescribeHostsResponse.hostSet.item.availableCapacity.availableInstanceCapacity.item.totalCapacity", equalTo("1"));
    }

    @Test
    void releaseReturnsAwsPerHostResults() {
        String hostId = allocate();
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        request("ReleaseHosts", Map.of("HostId.1", hostId, "HostId.2", "h-00000000000000000"))
                .then().statusCode(200)
                .body("ReleaseHostsResponse.successful.item", equalTo(hostId))
                .body("ReleaseHostsResponse.unsuccessful.item.resourceId", equalTo("h-00000000000000000"))
                .body("ReleaseHostsResponse.unsuccessful.item.error.code", not(emptyString()));
        request("DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.state", equalTo("released"));
    }

    @Test
    void terminationScrubsHostBeforeRelease() {
        String hostId = allocate();
        String instanceId = launch(hostId);
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        request("ReleaseHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("ReleaseHostsResponse.unsuccessful.item.error.code", equalTo("Client.InvalidHost.Occupied"));
        request("TerminateInstances", Map.of("InstanceId.1", instanceId)).then().statusCode(200);
        request("DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.state", equalTo("pending"))
                .body("DescribeHostsResponse.hostSet.item.instances.item.size()", equalTo(0));
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)).plusSeconds(1));
        request("ReleaseHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("ReleaseHostsResponse.successful.item", equalTo(hostId));
    }

    @Test
    void stoppedInstanceCannotRestartOnReleasedHost() {
        String hostId = allocate();
        String instanceId = launch(hostId);
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        request("StopInstances", Map.of("InstanceId.1", instanceId)).then().statusCode(200);
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)).plusSeconds(1));
        request("ReleaseHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("ReleaseHostsResponse.successful.item", equalTo(hostId));
        request("StartInstances", Map.of("InstanceId.1", instanceId)).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InsufficientCapacityOnHost"));
    }

    @Test
    void anotherAccountCannotReadOrReleaseHost() {
        String hostId = allocate();
        requestAs("222222222222", "DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(400);
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(24)));
        requestAs("222222222222", "ReleaseHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("ReleaseHostsResponse.successful.item.size()", equalTo(0));
        request("DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.state", equalTo("available"));
    }

    @Test
    void maxResultsCannotBeCombinedWithHostIds() {
        String hostId = allocate();
        request("DescribeHosts", Map.of("HostId.1", hostId, "MaxResults", 5)).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    void autoPlacementIsExplicitlyUnsupported() {
        Map<String, Object> parameters = Map.of("InstanceType", TYPE, "AvailabilityZone", "us-east-1a",
                "Quantity", 1, "AutoPlacement", "on");
        request("AllocateHosts", parameters).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
    }

    @Test
    void hostTagsParticipateInTheExistingTagApis() {
        String hostId = allocate();
        Map<String, Object> addition = Map.of("ResourceId.1", hostId, "Tag.1.Key", "purpose", "Tag.1.Value", "builder");
        request("CreateTags", addition).then().statusCode(200);
        Map<String, Object> filter = Map.of("Filter.1.Name", "resource-id", "Filter.1.Value.1", hostId);
        request("DescribeTags", filter).then().statusCode(200)
                .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("dedicated-host"))
                .body("DescribeTagsResponse.tagSet.item.key", equalTo("purpose"))
                .body("DescribeTagsResponse.tagSet.item.value", equalTo("builder"));
        request("DeleteTags", Map.of("ResourceId.1", hostId, "Tag.1.Key", "purpose")).then().statusCode(200);
        request("DescribeHosts", Map.of("HostId.1", hostId)).then().statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.tagSet.item.size()", equalTo(0));
    }

    @Test
    void unsupportedPlacementOptionsAreNotSilentlyIgnored() {
        String hostId = allocate();
        Map<String, Object> launch = Map.of("ImageId", "ami-ubuntu2404-arm64", "InstanceType", TYPE,
                "MinCount", 1, "MaxCount", 1, "Placement.HostId", hostId, "Placement.Affinity", "host");
        request("RunInstances", launch).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
    }

    @Test
    void launchTemplateCarriesHostPlacementIntoLaunch() {
        String hostId = allocate();
        Map<String, Object> template = Map.of("LaunchTemplateName", "mac-" + UUID.randomUUID(),
                "LaunchTemplateData.ImageId", "ami-ubuntu2404-arm64", "LaunchTemplateData.InstanceType", TYPE,
                "LaunchTemplateData.Placement.HostId", hostId, "LaunchTemplateData.Placement.Tenancy", "host");
        String templateId = request("CreateLaunchTemplate", template).then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        request("RunInstances", Map.of("LaunchTemplate.LaunchTemplateId", templateId, "MinCount", 1, "MaxCount", 1))
                .then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.placement.hostId", equalTo(hostId));
    }
}
