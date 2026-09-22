package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector.SourceContext;
import com.acme.opsweave.integration.api.ZabbixHistoryPort;
import com.acme.opsweave.integration.domain.HistoryCursor;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.domain.HistoryReadException.Code;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.integration.domain.MappingRegistry;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Zabbix 7.0 protocol. Exactly one metadata request and one bounded history request; no retries. */
public final class ZabbixJsonRpcHistoryReader implements ZabbixHistoryPort {
    private final URI endpoint;
    private final ZabbixJsonRpcConnector.Transport transport;
    private final ZabbixJsonRpcConnector.SecretSource secrets;
    private final ZabbixItemMapper mapper;
    private final MappingRegistry mappings;

    public ZabbixJsonRpcHistoryReader(URI endpoint, ZabbixJsonRpcConnector.Transport transport,
                                    ZabbixJsonRpcConnector.SecretSource secrets, MappingRegistry mappings) {
        this.endpoint = endpoint;
        this.transport = transport;
        this.secrets = secrets;
        this.mapper = new ZabbixItemMapper(mappings);
        this.mappings = mappings;
    }

    @Override
    public HistoryPage read(SourceContext source, MetricBinding binding, HistoryWindow window) {
        if (!source.tenantId().equals(binding.tenantId()) || !source.sourceInstanceId().equals(binding.sourceInstanceId())
            || !"zabbix".equals(binding.sourceType()) || !binding.externalItemId().matches("[1-9][0-9]{0,19}")) {
            throw new HistoryReadException(Code.METADATA_CHANGED);
        }
        if (window.fetchFrom() > window.till()) return HistoryPage.select(window, List.of());
        try {
            String token = secrets.resolve(source.secretRef());
            String itemId = binding.externalItemId();
            var metadata = call("item.get", "\"output\":[\"itemid\",\"key_\",\"name\",\"value_type\",\"units\",\"hostid\"],"
                + "\"itemids\":[\"" + itemId + "\"],\"limit\":2", token);
            if (metadata.size() != 1 || !itemId.equals(text(metadata.getFirst(), "itemid"))) {
                throw new HistoryReadException(Code.METADATA_CHANGED);
            }
            Map<String, Object> item = metadata.getFirst();
            String type = text(item, "value_type");
            if (!"0".equals(type) && !"3".equals(type)) throw new HistoryReadException(Code.UNSUPPORTED_HISTORY);
            if (mapper.rejectReason(item).isPresent()) throw new HistoryReadException(Code.METADATA_CHANGED);
            MetricBinding current = mapper.map(source.tenantId(), source.sourceInstanceId(), item).binding();
            var mapping = mappings.find("zabbix", text(item, "key_")).orElseThrow();
            if (!current.entityId().equals(binding.entityId()) || !current.metricKey().equals(binding.metricKey())
                || !current.fixedDimensions().equals(binding.fixedDimensions()) || !current.sourceUnit().equals(binding.sourceUnit())
                || !current.valueTransform().equals(binding.valueTransform()) || current.mappingRevision() != binding.mappingRevision()) {
                throw new HistoryReadException(Code.METADATA_CHANGED);
            }
            var rows = call("history.get", "\"output\":[\"itemid\",\"clock\",\"ns\",\"value\"],"
                + "\"history\":" + type + ",\"itemids\":[\"" + itemId + "\"],"
                + "\"time_from\":" + window.fetchFrom() + ",\"time_till\":" + window.till() + ","
                + "\"sortfield\":[\"clock\",\"ns\"],\"sortorder\":[\"ASC\",\"ASC\"],\"limit\":" + HistoryPage.SOURCE_LIMIT, token);
            if (rows.size() > HistoryPage.SOURCE_LIMIT) throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
            List<MetricPoint> points = new ArrayList<>();
            for (var row : rows) {
                if (!itemId.equals(text(row, "itemid"))) throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
                var position = new HistoryCursor(Long.parseLong(text(row, "clock")), Integer.parseInt(text(row, "ns")));
                String rawValue = text(row, "value");
                if (rawValue.length() > 64 || rawValue.isBlank()) throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
                BigDecimal value = new BigDecimal(rawValue);
                if ("3".equals(type) && (!rawValue.matches("[0-9]{1,20}")
                    || value.compareTo(new BigDecimal("18446744073709551615")) > 0)) {
                    throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
                }
                points.add(mapping.normalize(position.instant(), value));
            }
            return HistoryPage.select(window, points);
        } catch (HistoryReadException known) {
            throw known;
        } catch (IllegalArgumentException malformed) {
            throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
        } catch (RuntimeException failed) {
            throw new HistoryReadException(Code.SOURCE_FETCH_FAILED);
        }
    }

    private List<Map<String, Object>> call(String method, String params, String token) {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{" + params + "},\"id\":1}";
        return transport.readHostArray(transport.exchange(endpoint, body, token));
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) throw new HistoryReadException(Code.INVALID_SOURCE_RESPONSE);
        return String.valueOf(value);
    }
}
