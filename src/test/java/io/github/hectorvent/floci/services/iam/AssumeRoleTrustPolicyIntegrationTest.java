package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies that, with {@code iam.enforcement-enabled=true}, STS AssumeRole honors the target role's
 * trust policy: a caller the trust policy permits succeeds; one it does not is denied; and a role
 * Floci does not know about stays permissive (backward-compatible).
 */
@QuarkusTest
@TestProfile(AssumeRoleTrustPolicyIntegrationTest.EnforcementProfile.class)
class AssumeRoleTrustPolicyIntegrationTest {

    public static class EnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }

    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";
    private static final String ACCOUNT_C = "333333333333";

    private static final String TRUST_ALLOW_A = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
            + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + ACCOUNT_A + ":root\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static void createRoleInB(String roleName) {
        createTrustedRoleInB(roleName, TRUST_ALLOW_A);
    }

    private static void createTrustedRoleInB(String roleName, String trustPolicy) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("AssumeRolePolicyDocument", trustPolicy)
            .header("Authorization", auth(ACCOUNT_B, "iam"))
        .when().post("/")
        .then().statusCode(200);
    }

    private record ScopedIdentity(String key, String arn) {}

    private static ScopedIdentity scopedUser(String roleArn) {
        String user = "scoped-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", auth(ACCOUNT_A, "iam"))
                .formParam("Action", "CreateUser").formParam("UserName", user)
                .when().post("/").then().statusCode(200);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"sts:AssumeRole\",\"Resource\":\"" + roleArn + "\"}]}";
        given().header("Authorization", auth(ACCOUNT_A, "iam"))
                .formParam("Action", "PutUserPolicy").formParam("UserName", user)
                .formParam("PolicyName", "one-role").formParam("PolicyDocument", policy)
                .when().post("/").then().statusCode(200);
        String key = given().header("Authorization", auth(ACCOUNT_A, "iam"))
                .formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .when().post("/").then().statusCode(200).extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        return new ScopedIdentity(key, "arn:aws:iam::" + ACCOUNT_A + ":user/" + user);
    }

    private static void trustIdentity(String role, ScopedIdentity identity) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"" + identity.arn() + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        createTrustedRoleInB(role, trust);
    }

    @Test
    void exactRoleIdentityGrantAllowsItsTrustedTarget() {
        String role = "scoped-ok-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + ACCOUNT_B + ":role/" + role;
        ScopedIdentity identity = scopedUser(roleArn);
        trustIdentity(role, identity);
        given().header("Authorization", auth(identity.key(), "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "scoped")
                .when().post("/").then().statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void exactRoleIdentityGrantDeniesAnotherRoleEvenWhenTrustAllowsTheCaller() {
        String role = "scoped-deny-" + UUID.randomUUID().toString().substring(0, 8);
        ScopedIdentity identity = scopedUser("arn:aws:iam::" + ACCOUNT_B + ":role/some-other-role");
        trustIdentity(role, identity);
        given().header("Authorization", auth(identity.key(), "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/" + role)
                .formParam("RoleSessionName", "scoped")
                .when().post("/").then().statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void registeredUserCanAssumeRoleThroughItsAccountDelegation() {
        String role = "account-delegation-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);
        String roleArn = "arn:aws:iam::" + ACCOUNT_B + ":role/" + role;
        ScopedIdentity identity = scopedUser(roleArn);
        given().header("Authorization", auth(identity.key(), "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "delegation")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void registeredUserDoesNotInheritTheDefaultAccountsDelegation() {
        String role = "wrong-delegation-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = TRUST_ALLOW_A.replace(ACCOUNT_A, "000000000000");
        createTrustedRoleInB(role, trust);
        String roleArn = "arn:aws:iam::" + ACCOUNT_B + ":role/" + role;
        ScopedIdentity identity = scopedUser(roleArn);
        given().header("Authorization", auth(identity.key(), "sts"))
                .formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "delegation")
                .when().post("/").then().statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void permittedCallerCanAssumeRole() {
        String role = "trust-ok-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_A, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }

    @Test
    void unauthorizedCallerIsDenied() {
        String role = "trust-deny-" + UUID.randomUUID().toString().substring(0, 8);
        createRoleInB(role);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_C, "sts"))
        .when().post("/")
        .then().statusCode(403)
            .body(containsString("AccessDenied"))
            // AWS prefixes the denial with the caller and names the action and resource.
            .body(containsString("User: "))
            .body(containsString("is not authorized to perform: sts:AssumeRole on resource: "
                    + "arn:aws:iam::" + ACCOUNT_B + ":role/" + role));
    }

    @Test
    void unknownRoleStaysPermissive() {
        // No role created — enforcement must not block roles Floci has never seen.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/never-created-"
                    + UUID.randomUUID().toString().substring(0, 8))
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(ACCOUNT_C, "sts"))
        .when().post("/")
        .then().statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"));
    }
}
