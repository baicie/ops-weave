package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelPreview.Issue;
import com.acme.opsweave.telemetry.domain.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** A trusted, resolved metric definition used by VALIDATE, never a decorative reference. */
public final class WorkflowMetricPlan {
    private final MappingDefinition mapping;
    private final Map<String,Object> pin;
    public WorkflowMetricPlan(MetricMappingPin expected,MappingDefinition mapping){
        if(mapping==null||!mapping.pin().equals(expected)||mapping.metricType()!=MetricType.GAUGE
            ||!Set.of(MetricValueType.DOUBLE,MetricValueType.INTEGER).contains(mapping.valueType())
            ||!mapping.metricKey().matches("[A-Za-z][A-Za-z0-9_.:/-]{0,127}")
            ||!new HashSet<>(mapping.dimensionSchema()).equals(mapping.fixedDimensions().keySet()))
            throw new WorkflowFailure(WorkflowFailure.Code.MAPPING_CHANGED);
        this.mapping=mapping;pin=Map.of("id",expected.id(),"revision",expected.revision(),"digest",expected.digest());
        try{MetricPoint.normalize(java.time.Instant.EPOCH,BigDecimal.ZERO,mapping.valueTransform());
            if(bytes(Map.of("timestamp","0".repeat(80),"metricKey",mapping.metricKey(),"value","0".repeat(64),"unit",mapping.unit(),"metricType","GAUGE","dimensions",mapping.fixedDimensions(),"mappingPin",pin))>4096)throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new WorkflowFailure(WorkflowFailure.Code.MAPPING_CHANGED);}
    }
    public MappingDefinition mapping(){return mapping;}
    public TelemetryOutput.Result evaluate(Map<String,Object> input){
        var issues=new ArrayList<Issue>();var fields=Set.of("timestamp","value","sourceKey");
        input.keySet().stream().filter(k->!fields.contains(k)).forEach(k->issues.add(new Issue(k,"UNKNOWN_FIELD")));
        fields.stream().filter(k->!input.containsKey(k)).forEach(k->issues.add(new Issue(k,"MISSING_REQUIRED")));
        fields.stream().filter(k->input.containsKey(k)&&input.get(k)==null).forEach(k->issues.add(new Issue(k,"NULL_REQUIRED")));
        if(!issues.isEmpty())return new TelemetryOutput.Result(Map.of(),issues);
        if(!mapping.itemKeyExact().equals(input.get("sourceKey")))issues.add(new Issue("sourceKey","TYPE_MISMATCH"));
        java.time.Instant timestamp=null;
        try{if(!(input.get("timestamp") instanceof String t)||t.length()>80)throw new IllegalArgumentException();timestamp=OffsetDateTime.parse(t).toInstant();}
        catch(IllegalArgumentException|java.time.DateTimeException invalid){issues.add(new Issue("timestamp","TYPE_MISMATCH"));}
        BigDecimal value=null;
        try{Object raw=input.get("value");if(!(raw instanceof String)&&!(raw instanceof Number)||raw.toString().length()>64)throw new IllegalArgumentException();value=new BigDecimal(raw.toString());
            if(mapping.valueType()==MetricValueType.INTEGER&&value.stripTrailingZeros().scale()>0)throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){issues.add(new Issue("value","TYPE_MISMATCH"));}
        if(!issues.isEmpty())return new TelemetryOutput.Result(Map.of(),issues);
        MetricPoint normalized;
        try{normalized=mapping.normalize(timestamp,value);}catch(IllegalArgumentException invalid){return new TelemetryOutput.Result(Map.of(),List.of(new Issue("value","OUT_OF_RANGE")));}
        var values=new LinkedHashMap<String,Object>();values.put("timestamp",normalized.timestamp().toString());values.put("metricKey",mapping.metricKey());
        values.put("value",normalized.value().stripTrailingZeros().toPlainString());values.put("unit",mapping.unit());values.put("metricType","GAUGE");
        values.put("dimensions",mapping.fixedDimensions());values.put("mappingPin",pin);
        try{requireNormalized(values);}catch(IllegalArgumentException invalid){return new TelemetryOutput.Result(Map.of(),List.of(new Issue("value","OUT_OF_RANGE")));}
        return new TelemetryOutput.Result(values,List.of());
    }
    public void requireNormalized(Map<String,Object> values){
        if(!values.keySet().equals(Set.of("timestamp","metricKey","value","unit","metricType","dimensions","mappingPin"))
            ||!mapping.metricKey().equals(values.get("metricKey"))||!mapping.unit().equals(values.get("unit"))||!"GAUGE".equals(values.get("metricType"))
            ||!mapping.fixedDimensions().equals(values.get("dimensions"))||!pin.equals(values.get("mappingPin"))
            ||!(values.get("timestamp") instanceof String time)||time.length()>80||!(values.get("value") instanceof String text)||text.length()>64||!text.matches("-?[0-9]+(\\.[0-9]+)?")||bytes(values)>4096)throw new IllegalArgumentException("Invalid fixed metric output");
        try{var point=new MetricPoint(java.time.Instant.parse((String)values.get("timestamp")),new BigDecimal((String)values.get("value")));
            if(mapping.minimum()!=null&&point.value().compareTo(mapping.minimum())<0||mapping.maximum()!=null&&point.value().compareTo(mapping.maximum())>0)throw new IllegalArgumentException("Metric value outside fixed range");
        }catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid fixed metric timestamp");}
    }
    private static int bytes(Object v){if(v instanceof Map<?,?> map){int size=2;for(var e:map.entrySet())size+=bytes(e.getKey().toString())+bytes(e.getValue())+2;return size;}
        if(v instanceof String text){int size=2+text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c<32)size+=5;else if(c=='"'||c=='\\')size++;}return size;}
        return Objects.toString(v,"null").length();}
}
