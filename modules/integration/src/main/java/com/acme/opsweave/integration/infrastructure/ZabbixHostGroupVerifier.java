package com.acme.opsweave.integration.infrastructure;

import java.net.URI;
import java.util.*;

/** Independently verifies upstream host-group filtering for a bounded set of hosts. */
final class ZabbixHostGroupVerifier {
    private ZabbixHostGroupVerifier() {}

    static void requireMembership(URI endpoint, ZabbixJsonRpcConnector.Transport transport, String token,
                                  Collection<String> hostIds, List<String> hostGroupIds) {
        var groups = com.acme.opsweave.integration.domain.SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        if (groups.isEmpty() || hostIds.isEmpty()) return;

        var expected = new TreeSet<String>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (String hostId : hostIds) {
            if (hostId == null || !hostId.matches("[1-9][0-9]{0,18}") || !expected.add(hostId))
                throw new IllegalStateException("Invalid host membership set");
        }
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{" 
            + "\"output\":[\"hostid\"],\"selectGroups\":[\"groupid\"],\"hostids\":[\""
            + String.join("\",\"", expected) + "\"]"
            + ZabbixJsonRpcConnector.groupIdsParameter(groups)
            + ",\"sortfield\":\"hostid\",\"sortorder\":\"ASC\",\"limit\":" + expected.size()
            + "},\"id\":1}";
        var rows = transport.readHostArray(transport.exchange(endpoint, request, token));
        if (rows.size() != expected.size()) throw new IllegalStateException("Host group membership mismatch");

        var actual = new TreeSet<String>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (var row : rows) {
            String hostId = String.valueOf(row.get("hostid"));
            if (!actual.add(hostId))
                throw new IllegalStateException("Host group membership unavailable");
            requireHostMembership(row, groups);
        }
        if (!expected.equals(actual)) throw new IllegalStateException("Host group membership mismatch");
    }

    static void requireHostMembership(Map<String, Object> host, List<String> hostGroupIds) {
        if (hostGroupIds.isEmpty()) return;
        if (!(host.get("groups") instanceof List<?> memberships))
            throw new IllegalStateException("Host group membership unavailable");
        boolean inScope = memberships.stream().anyMatch(group -> group instanceof Map<?, ?> value
            && hostGroupIds.contains(String.valueOf(value.get("groupid"))));
        if (!inScope) throw new IllegalStateException("Host group membership mismatch");
    }
}
