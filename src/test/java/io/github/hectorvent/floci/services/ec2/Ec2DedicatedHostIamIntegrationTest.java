package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(Ec2DedicatedHostIamIntegrationTest.Enforced.class)
class Ec2DedicatedHostIamIntegrationTest {
    public static class Enforced implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }

    private Response request(String key, String scope, String action, Map<String, ?> parameters) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + key + "/20260101/us-east-1/" + scope + "/aws4_request";
        return given().header("Authorization", authorization).formParam("Action", action)
                .formParams(parameters).when().post("/");
    }

    private String host(String purpose) {
        Map<String, Object> parameters = Map.of("InstanceType", "mac2-m2.metal", "AvailabilityZone", "us-east-1a",
                "Quantity", 1, "TagSpecification.1.ResourceType", "dedicated-host",
                "TagSpecification.1.Tag.1.Key", "purpose", "TagSpecification.1.Tag.1.Value", purpose);
        return request("test", "ec2", "AllocateHosts", parameters).then().statusCode(200)
                .extract().path("AllocateHostsResponse.hostIdSet.item");
    }

    private String userWithPolicy(String policy) {
        String name = "host-test-" + UUID.randomUUID();
        request("test", "iam", "CreateUser", Map.of("UserName", name)).then().statusCode(200);
        request("test", "iam", "PutUserPolicy", Map.of("UserName", name, "PolicyName", "host-policy",
                "PolicyDocument", policy)).then().statusCode(200);
        return request("test", "iam", "CreateAccessKey", Map.of("UserName", name)).then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private String releasePrincipal() {
        return userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ec2:ReleaseHosts",
                  "Resource":"arn:aws:ec2:us-east-1:000000000000:dedicated-host/*",
                  "Condition":{"StringEquals":{"ec2:ResourceTag/purpose":"builder","aws:RequestedRegion":"us-east-1"}}}]}
                """);
    }

    @Test
    void taggedHostPassesIamWhileUntaggedHostIsDenied() {
        String allowed = host("builder");
        String denied = host("unrelated");
        String key = releasePrincipal();
        // Passing IAM reaches the API's ordinary minimum-lease response, HTTP 200 with an unsuccessful item.
        request(key, "ec2", "ReleaseHosts", Map.of("HostId.1", allowed)).then().statusCode(200);
        request(key, "ec2", "ReleaseHosts", Map.of("HostId.1", denied)).then().statusCode(403);
    }

    @Test
    void everyHostInABatchMustPassItsOwnTagCondition() {
        String allowed = host("builder");
        String denied = host("unrelated");
        String key = releasePrincipal();
        request(key, "ec2", "ReleaseHosts", Map.of("HostId.1", allowed, "HostId.2", denied)).then().statusCode(403);
    }

    @Test
    void releasePrincipalCannotAllocateAnotherHost() {
        String key = releasePrincipal();
        request(key, "ec2", "AllocateHosts", Map.of("InstanceType", "mac2-m2.metal",
                "AvailabilityZone", "us-east-1a", "Quantity", 1)).then().statusCode(403);
    }

    @Test
    void queryActionCannotHideADifferentPostBodyAction() {
        String hostId = host("builder");
        String key = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ec2:DescribeHosts","Resource":"*"}]}
                """);
        String authorization = "AWS4-HMAC-SHA256 Credential=" + key + "/20260101/us-east-1/ec2/aws4_request";
        given().header("Authorization", authorization).queryParam("Action", "DescribeHosts")
                .formParam("Action", "ReleaseHosts").formParam("HostId.1", hostId)
                .when().post("/").then().statusCode(403);
    }
}
