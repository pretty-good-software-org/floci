package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ec2.model.DedicatedHost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class Ec2DedicatedHostQueryHandler {
    private static final String HOST_RESOURCE_TYPE = "dedicated-host";
    private static final String OFF = "off";
    private static final Set<String> UNSUPPORTED_PARAMETERS = Set.of("InstanceFamily", "OutpostArn", "AvailabilityZoneId");
    private final Ec2DedicatedHostService service;

    @Inject
    public Ec2DedicatedHostQueryHandler(Ec2DedicatedHostService service) {
        this.service = service;
    }

    public Response allocate(MultivaluedMap<String, String> parameters, String region) {
        for (String name : parameters.keySet()) {
            if (UNSUPPORTED_PARAMETERS.contains(name) || name.startsWith("CpuOptions.") || name.startsWith("AssetId.")) {
                throw new AwsException("UnsupportedOperation", "AllocateHosts: unsupported parameter " + name, 400);
            }
        }
        for (String option : List.of("AutoPlacement", "HostRecovery", "HostMaintenance")) {
            if (parameters.containsKey(option) && !OFF.equals(parameters.getFirst(option))) {
                throw new AwsException("UnsupportedOperation", "AllocateHosts: only " + option + "=off is supported", 400);
            }
        }
        Map<String, String> tags = parseTags(parameters);
        DedicatedHost.Allocation allocation = new DedicatedHost.Allocation(parameters.getFirst("AvailabilityZone"),
                parameters.getFirst("InstanceType"), integer(parameters, "Quantity", 0), tags);
        List<String> ids = service.allocate(region, allocation, parameters.getFirst("ClientToken"));
        XmlBuilder xml = response("AllocateHostsResponse");
        xml.start("hostIdSet");
        for (String id : ids) { xml.elem("item", id); }
        xml.end("hostIdSet").end("AllocateHostsResponse");
        return response(xml);
    }

    public Response describe(MultivaluedMap<String, String> parameters, String region) {
        List<String> ids = Ec2QueryHandler.getList(parameters, "HostId");
        if (!ids.isEmpty() && parameters.containsKey("MaxResults")) {
            throw new AwsException("InvalidParameterCombination", "DescribeHosts: HostId and MaxResults cannot be combined", 400);
        }
        Map<String, List<String>> filters = Ec2QueryHandler.getFilters(parameters);
        var page = service.describe(region, ids, filters, integer(parameters, "MaxResults", 500), parameters.getFirst("NextToken"));
        XmlBuilder xml = response("DescribeHostsResponse");
        xml.start("hostSet");
        for (var host : page.hosts()) { writeHost(xml, host); }
        xml.end("hostSet");
        if (page.nextToken() != null) { xml.elem("nextToken", page.nextToken()); }
        xml.end("DescribeHostsResponse");
        return response(xml);
    }

    public Response release(MultivaluedMap<String, String> parameters, String region) {
        List<String> ids = Ec2QueryHandler.getList(parameters, "HostId");
        var result = service.release(region, ids);
        XmlBuilder xml = response("ReleaseHostsResponse");
        xml.start("successful");
        for (String id : result.successful()) { xml.elem("item", id); }
        xml.end("successful").start("unsuccessful");
        for (var failure : result.unsuccessful()) {
            xml.start("item").elem("resourceId", failure.resourceId()).start("error")
                    .elem("code", failure.code()).elem("message", failure.message()).end("error").end("item");
        }
        xml.end("unsuccessful").end("ReleaseHostsResponse");
        return response(xml);
    }

    private void writeHost(XmlBuilder xml, Ec2DedicatedHostService.View view) {
        DedicatedHost host = view.host();
        xml.start("item").elem("hostId", host.hostId()).elem("ownerId", host.ownerId())
                .elem("availabilityZone", host.allocation().availabilityZone())
                .elem("allocationTime", host.allocationTime().toString()).elem("state", view.state())
                .elem("autoPlacement", OFF).elem("hostRecovery", OFF).elem("hostMaintenance", OFF)
                .elem("clientToken", host.clientToken()).start("hostProperties")
                .elem("instanceType", host.allocation().instanceType()).end("hostProperties");
        if (host.releaseTime() != null) { xml.elem("releaseTime", host.releaseTime().toString()); }
        xml.start("availableCapacity").start("availableInstanceCapacity").start("item")
                .elem("instanceType", host.allocation().instanceType()).elem("totalCapacity", 1)
                .elem("availableCapacity", view.state().equals("available") && view.instances().isEmpty() ? 1 : 0)
                .end("item").end("availableInstanceCapacity").end("availableCapacity");
        xml.start("instances");
        for (var instance : view.instances()) {
            xml.start("item").elem("instanceId", instance.instanceId()).elem("instanceType", instance.instanceType())
                    .elem("ownerId", instance.ownerId()).end("item");
        }
        xml.end("instances").start("tagSet");
        host.tags().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(tag ->
                xml.start("item").elem("key", tag.getKey()).elem("value", tag.getValue()).end("item"));
        xml.end("tagSet").end("item");
    }

    private Map<String, String> parseTags(MultivaluedMap<String, String> parameters) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int index = 1; parameters.containsKey("TagSpecification." + index + ".ResourceType"); index++) {
            String prefix = "TagSpecification." + index;
            if (!HOST_RESOURCE_TYPE.equals(parameters.getFirst(prefix + ".ResourceType"))) {
                throw new AwsException("InvalidParameterValue", "AllocateHosts: tags must target dedicated-host", 400);
            }
            for (int tagIndex = 1; parameters.containsKey(prefix + ".Tag." + tagIndex + ".Key"); tagIndex++) {
                String tagPrefix = prefix + ".Tag." + tagIndex;
                String key = parameters.getFirst(tagPrefix + ".Key");
                String value = parameters.getFirst(tagPrefix + ".Value");
                if (key == null || key.isBlank() || tags.containsKey(key)) {
                    throw new AwsException("InvalidParameterValue", "AllocateHosts: tag keys must be nonempty and unique", 400);
                }
                tags.put(key, value == null ? "" : value);
            }
        }
        return Map.copyOf(tags);
    }

    private int integer(MultivaluedMap<String, String> parameters, String name, int defaultValue) {
        String value = parameters.getFirst(name);
        if (value == null) { return defaultValue; }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new AwsException("InvalidParameterValue", "Parse dedicated host request: invalid " + name, 400);
        }
    }

    private XmlBuilder response(String name) {
        return new XmlBuilder().start(name, AwsNamespaces.EC2).elem("requestId", UUID.randomUUID().toString());
    }

    private Response response(XmlBuilder xml) {
        return Response.ok(xml.build(), MediaType.APPLICATION_XML).build();
    }
}
