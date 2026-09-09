package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwsQueryResourceArnBuilderTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private final ResourceArnBuilder builder = new ResourceArnBuilder();

    private ContainerRequestContext form(String body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/");
        when(context.getMethod()).thenReturn("POST");
        when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> {
            InputStream restored = invocation.getArgument(0);
            when(context.getEntityStream()).thenReturn(restored);
            return null;
        }).when(context).setEntityStream(any(InputStream.class));
        return context;
    }

    @Test
    void assumeRoleUsesDecodedTargetArnAndPreservesTheHandlerBody() throws Exception {
        String body = "Action=AssumeRole&RoleArn=arn%3Aaws%3Aiam%3A%3A222222222222%3Arole%2Fteam%2Fplanning";
        ContainerRequestContext context = form(body);
        assertEquals("arn:aws:iam::222222222222:role/team/planning", builder.build("sts", context, REGION, ACCOUNT),
                "STS must authorize the target role ARN rather than the caller account or wildcard");
        assertEquals(body, new String(context.getEntityStream().readAllBytes(), StandardCharsets.UTF_8),
                "Authorization must not consume the handler's form body");
    }

    @Test
    void duplicateRoleArnUsesTheSameFirstValueAsTheQueryHandler() {
        ContainerRequestContext context = form("Action=AssumeRole&RoleArn=first-role&RoleArn=second-role");
        assertEquals("first-role", builder.build("sts", context, REGION, ACCOUNT),
                "Authorization and handler must agree on duplicate parameter precedence");
    }

    @Test
    void allocateHostsUsesTheAccountAndRegionScopedDedicatedHostResource() {
        ContainerRequestContext context = form("Action=AllocateHosts&Quantity=1&InstanceType=mac2-m2.metal");
        assertEquals("arn:aws:ec2:us-east-1:111111111111:dedicated-host/*",
                builder.build("ec2", context, REGION, ACCOUNT),
                "Host creation must support the dedicated-host resource type rather than require all-resource grants");
    }

    @Test
    void blankRoleArnFallsBackToUnresolvedResource() {
        ContainerRequestContext context = form("Action=AssumeRole&RoleArn=");
        assertEquals("*", builder.build("sts", context, REGION, ACCOUNT), "Empty targets must not invent a role ARN");
    }

    @Test
    void missingRoleArnFallsBackToUnresolvedResource() {
        ContainerRequestContext context = form("Action=AssumeRole");
        assertEquals("*", builder.build("sts", context, REGION, ACCOUNT), "Missing targets must not invent a role ARN");
    }

    @Test
    void unrelatedStsActionDoesNotUseAnInjectedRoleArn() {
        ContainerRequestContext context = form("Action=GetSessionToken&RoleArn=unrelated");
        assertEquals("*", builder.build("sts", context, REGION, ACCOUNT), "Only AssumeRole uses its target role");
    }
}
