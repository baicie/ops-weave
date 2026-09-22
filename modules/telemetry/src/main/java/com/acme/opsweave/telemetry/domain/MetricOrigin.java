package com.acme.opsweave.telemetry.domain;

public enum MetricOrigin {
    SOURCE;

    public String wireValue() {
        return "source";
    }
}
