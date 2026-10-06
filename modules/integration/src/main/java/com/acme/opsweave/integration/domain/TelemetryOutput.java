package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelPreview.Issue;
import java.time.OffsetDateTime;
import java.util.*;

/** Fixed preview formats, independent of the entity model catalog. No storage writes. */
public final class TelemetryOutput {
    private TelemetryOutput() {}
    public record Result(Map<String,Object> values,List<Issue> issues){public Result{values=Collections.unmodifiableMap(new LinkedHashMap<>(values));issues=List.copyOf(issues);}public boolean valid(){return issues.isEmpty();}}
    public static Set<String> fields(String kind){return switch(kind){case "LOG"->Set.of("eventTime","body","severityText","serviceName","traceId","spanId");case "METRIC"->Set.of("timestamp","metricKey","value","unit","metricType");default->throw new IllegalArgumentException("Invalid output type");};}
    public static Set<String> fields(WorkflowDefinition.Target target){return target.mappingPin()==null?fields(target.kind()):Set.of("timestamp","value","sourceKey");}
    public static Result validate(String kind,Map<String,Object> sample){
        var allowed=fields(kind);var required=kind.equals("LOG")?Set.of("eventTime","body"):Set.of("timestamp","metricKey","value","metricType");
        var values=new LinkedHashMap<String,Object>();var issues=new ArrayList<Issue>();
        for(String key:sample.keySet())if(!allowed.contains(key))issues.add(new Issue(key,"UNKNOWN_FIELD"));
        for(String key:allowed){
            if(!sample.containsKey(key)){if(required.contains(key))issues.add(new Issue(key,"MISSING_REQUIRED"));continue;}
            Object raw=sample.get(key);if(raw==null){if(required.contains(key))issues.add(new Issue(key,"NULL_REQUIRED"));else values.put(key,null);continue;}
            try{
                Object value=raw;
                if(key.equals("value")){if(!(raw instanceof Number)&&!(raw instanceof String))throw new IllegalArgumentException();value=WorkflowDefinition.decimal(raw.toString());}
                else{
                    if(!(raw instanceof String text))throw new IllegalArgumentException();
                    if(required.contains(key)&&text.isBlank()){issues.add(new Issue(key,"EMPTY_REQUIRED"));continue;}
                    int max=key.equals("body")?2048:key.equals("eventTime")||key.equals("timestamp")?80:128;
                    if(text.length()>max){issues.add(new Issue(key,"TOO_LONG"));continue;}
                    if(key.equals("eventTime")||key.equals("timestamp"))value=OffsetDateTime.parse(text).toInstant().toString();
                    if(key.equals("metricKey")&&!text.matches("[A-Za-z][A-Za-z0-9_.:/-]{0,127}"))throw new IllegalArgumentException();
                    if(key.equals("metricType")&&!text.equals("GAUGE")){issues.add(new Issue(key,"ENUM_MISMATCH"));continue;}
                    if(key.equals("traceId")&&!text.matches("[a-f0-9]{32}")||key.equals("spanId")&&!text.matches("[a-f0-9]{16}"))throw new IllegalArgumentException();
                }
                values.put(key,value);
            }catch(IllegalArgumentException|java.time.DateTimeException invalid){issues.add(new Issue(key,"TYPE_MISMATCH"));}
        }
        return new Result(values,issues);
    }
}
