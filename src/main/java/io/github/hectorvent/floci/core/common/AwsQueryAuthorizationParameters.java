package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves first-value semantics for AWS Query POST handlers and the EC2 GET handler. */
public final class AwsQueryAuthorizationParameters {
    private static final String CACHE_KEY = "floci.awsQueryAuthorizationParameters";

    private AwsQueryAuthorizationParameters() {}

    public static String action(Map<String, String> parameters) {
        return parameters.getOrDefault("Action", parameters.getOrDefault("Operation", ""));
    }

    public static Map<String, String> read(ContainerRequestContext context) {
        Object cached = context.getProperty(CACHE_KEY);
        if (cached instanceof Parameters parameters) { return parameters.values(); }
        Map<String, String> values = new LinkedHashMap<>();
        if ("GET".equalsIgnoreCase(context.getMethod())) {
            context.getUriInfo().getQueryParameters().forEach((key, items) -> {
                if (!items.isEmpty()) { values.put(key, items.getFirst()); }
            });
        } else {
            byte[] body;
            try {
                body = context.getEntityStream().readAllBytes();
            } catch (IOException error) {
                throw new AwsException("InvalidParameterValue", "Authorize AWS query request: cannot read form body", 400);
            }
            context.setEntityStream(new ByteArrayInputStream(body));
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
                if (pair.isEmpty()) { continue; }
                String[] parts = pair.split("=", 2);
                try {
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    values.putIfAbsent(key, value);
                } catch (IllegalArgumentException error) {
                    throw new AwsException("InvalidParameterValue", "Authorize AWS query request: malformed form encoding", 400);
                }
            }
        }
        Map<String, String> result = Map.copyOf(values);
        context.setProperty(CACHE_KEY, new Parameters(result));
        return result;
    }

    private record Parameters(Map<String, String> values) {}
}
