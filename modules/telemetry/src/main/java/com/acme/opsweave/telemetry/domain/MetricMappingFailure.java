package com.acme.opsweave.telemetry.domain;

public final class MetricMappingFailure extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, CONFLICT, INCOMPATIBLE, CAPACITY, UNAVAILABLE }
    private final Code code;
    public MetricMappingFailure(Code code) { super("METRIC_MAPPING_" + code); this.code = code; }
    public Code code() { return code; }
}
