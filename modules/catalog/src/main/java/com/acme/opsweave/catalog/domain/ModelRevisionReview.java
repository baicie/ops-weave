package com.acme.opsweave.catalog.domain;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;

/** Field-level changes describe the same additive policy enforced again during publication. */
public final class ModelRevisionReview {
    private ModelRevisionReview(){}
    public record Pin(String id,int revision,String digest){public Pin{new ModelDefinition.Ref(id,revision);if(digest==null||!digest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException();}}
    public enum Property { LABEL,DESCRIPTION,KIND,REVISION,FIELD,TYPE,REQUIRED,MAX_LENGTH,MIN,MAX,CHOICES,FROM,TO,CARDINALITY }
    public record Change(String fieldId,Property property,String before,String after,boolean compatible){public Change{Objects.requireNonNull(property);if(fieldId!=null&&!fieldId.matches("[a-z][a-z0-9_]{0,47}")||Objects.equals(before,after)||before!=null&&before.length()>4096||after!=null&&after.length()>4096)throw new IllegalArgumentException();}}
    public record Review(Pin candidate,int editVersion,Pin base,Instant reviewedAt,boolean compatible,List<String> reasons,List<Change> changes){public Review{Objects.requireNonNull(candidate);Objects.requireNonNull(reviewedAt);reasons=List.copyOf(reasons);changes=List.copyOf(changes);if(editVersion<1||editVersion>1000000||changes.size()>300||reasons.size()>3||reasons.stream().distinct().count()!=reasons.size()||!Set.of("NON_CONSECUTIVE_REVISION","INCOMPATIBLE_CHANGE","UNKNOWN_ENTITY_TYPE").containsAll(reasons)||compatible!=reasons.isEmpty()||base!=null&&!base.id().equals(candidate.id()))throw new IllegalArgumentException();}}
    public static Review compare(ModelDefinition previous,ModelDefinition next,int edit,Instant time,boolean endpointsAvailable){
        var changes=new ArrayList<Change>();var reasons=new ArrayList<String>();
        if(previous==null){if(next.revision()!=1)reasons.add("NON_CONSECUTIVE_REVISION");for(var f:next.fields())add(changes,f.id(),Property.FIELD,null,summary(f),true);}
        else{
            if(!previous.id().equals(next.id()))throw new IllegalArgumentException();
            if(next.revision()!=previous.revision()+1)reasons.add("NON_CONSECUTIVE_REVISION");
            add(changes,null,Property.KIND,previous.kind().name(),next.kind().name(),false);add(changes,null,Property.LABEL,previous.label(),next.label(),true);add(changes,null,Property.DESCRIPTION,previous.description(),next.description(),true);
            var old=new TreeMap<String,ModelDefinition.Field>();var updated=new TreeMap<String,ModelDefinition.Field>();previous.fields().forEach(f->old.put(f.id(),f));next.fields().forEach(f->updated.put(f.id(),f));var ids=new TreeSet<>(old.keySet());ids.addAll(updated.keySet());
            for(var id:ids){var a=old.get(id);var b=updated.get(id);if(a==null||b==null){add(changes,id,Property.FIELD,a==null?null:summary(a),b==null?null:summary(b),a==null&&!b.required());continue;}
                add(changes,id,Property.LABEL,a.label(),b.label(),true);add(changes,id,Property.TYPE,a.type().name(),b.type().name(),false);add(changes,id,Property.REQUIRED,String.valueOf(a.required()),String.valueOf(b.required()),false);add(changes,id,Property.MAX_LENGTH,value(a.maxLength()),value(b.maxLength()),false);add(changes,id,Property.MIN,number(a.min()),number(b.min()),false);add(changes,id,Property.MAX,number(a.max()),number(b.max()),false);add(changes,id,Property.CHOICES,choices(a.choices()),choices(b.choices()),false);
            }
            var a=previous.endpoints();var b=next.endpoints();add(changes,null,Property.FROM,a==null?null:ref(a.from()),b==null?null:ref(b.from()),false);add(changes,null,Property.TO,a==null?null:ref(a.to()),b==null?null:ref(b.to()),false);add(changes,null,Property.CARDINALITY,a==null?null:a.cardinality().name(),b==null?null:b.cardinality().name(),false);
            if(changes.stream().anyMatch(c->!c.compatible()))reasons.add("INCOMPATIBLE_CHANGE");
        }
        if(!endpointsAvailable)reasons.add("UNKNOWN_ENTITY_TYPE");
        return new Review(pin(next),edit,previous==null?null:pin(previous),time,reasons.isEmpty(),reasons,changes);
    }
    public static Pin pin(ModelDefinition model){return new Pin(model.id(),model.revision(),model.digest());}
    private static String summary(ModelDefinition.Field f){return f.type().name()+" · "+(f.required()?"required":"optional");}
    private static String choices(List<String> values){return values.stream().map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining(" | "));}
    private static String ref(ModelDefinition.Ref ref){return ref.id()+"@"+ref.revision();}
    private static String value(Object v){return v==null?null:v.toString();}
    private static String number(BigDecimal v){return v==null?null:v.stripTrailingZeros().toPlainString();}
    private static void add(List<Change> changes,String field,Property property,String a,String b,boolean compatible){if(!Objects.equals(a,b))changes.add(new Change(field,property,a,b,compatible));}
}
