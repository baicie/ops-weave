package com.acme.opsweave.integration.domain;

import java.util.*;

/** Bounded metadata comparison. Revision, layout and preview are not processing changes. */
public final class WorkflowComparison {
    private WorkflowComparison() {}
    public enum Section { NAME, SOURCE, TARGET, NODE, OPERATOR, MAPPING, PARAMETER, EDGE, ORDER }
    public record Change(Section section, String nodeId, String key, String before, String after) {
        public Change {
            Objects.requireNonNull(section); Objects.requireNonNull(key);
            boolean nodeSection=Set.of(Section.NODE,Section.OPERATOR,Section.MAPPING,Section.PARAMETER).contains(section);
            if(nodeSection ? nodeId==null||!nodeId.matches("[a-z][a-z0-9_-]{0,31}") : nodeId!=null)throw new IllegalArgumentException();
            if(key.isEmpty()||key.length()>96||Objects.equals(before,after)||before!=null&&before.length()>4096||after!=null&&after.length()>4096)throw new IllegalArgumentException();
        }
    }
    public record Reference(String id,int revision,String state,int editVersion,String digest) {
        public Reference { WorkflowDefinition.ref(id,revision); WorkflowDefinition.checkDigest(digest); if(!Set.of("DRAFT","PUBLISHED").contains(state)||editVersion<0||editVersion>1000000||state.equals("DRAFT")&&editVersion==0||state.equals("PUBLISHED")&&editVersion!=0)throw new IllegalArgumentException(); }
    }
    public static List<Change> compare(WorkflowDefinition before,WorkflowDefinition after) {
        Objects.requireNonNull(before);Objects.requireNonNull(after);if(!before.id().equals(after.id()))throw new IllegalArgumentException("Workflow identity differs");
        var changes=new ArrayList<Change>();
        add(changes,Section.NAME,null,"name",before.name(),after.name());
        var a=before.source();var b=after.source();
        add(changes,Section.SOURCE,null,"kind",a.kind(),b.kind());add(changes,Section.SOURCE,null,"instanceId",a.instanceId(),b.instanceId());
        add(changes,Section.SOURCE,null,"sourceId",a.configuration()==null?null:a.configuration().sourceId().toString(),b.configuration()==null?null:b.configuration().sourceId().toString());
        add(changes,Section.SOURCE,null,"configurationRevision",a.configuration()==null?null:String.valueOf(a.configuration().revision()),b.configuration()==null?null:String.valueOf(b.configuration().revision()));
        add(changes,Section.SOURCE,null,"connectionDigest",a.configuration()==null?null:a.configuration().digest(),b.configuration()==null?null:b.configuration().digest());
        add(changes,Section.SOURCE,null,"metricInspectionId",a.metric()==null?null:a.metric().inspectionId().toString(),b.metric()==null?null:b.metric().inspectionId().toString());
        add(changes,Section.SOURCE,null,"itemId",a.metric()==null?null:a.metric().itemId(),b.metric()==null?null:b.metric().itemId());
        add(changes,Section.SOURCE,null,"hostId",a.metric()==null?null:a.metric().hostId(),b.metric()==null?null:b.metric().hostId());
        add(changes,Section.SOURCE,null,"sourceKey",a.metric()==null?null:a.metric().sourceKey(),b.metric()==null?null:b.metric().sourceKey());
        add(changes,Section.SOURCE,null,"sourceUnit",a.metric()==null?null:a.metric().sourceUnit(),b.metric()==null?null:b.metric().sourceUnit());
        add(changes,Section.SOURCE,null,"sourceValueType",a.metric()==null?null:a.metric().sourceValueType(),b.metric()==null?null:b.metric().sourceValueType());
        add(changes,Section.SOURCE,null,"metricSourceDigest",a.metric()==null?null:a.metric().digest(),b.metric()==null?null:b.metric().digest());
        add(changes,Section.SOURCE,null,"logInspectionId",a.log()==null?null:a.log().inspectionId().toString(),b.log()==null?null:b.log().inspectionId().toString());
        add(changes,Section.SOURCE,null,"logItemId",a.log()==null?null:a.log().itemId(),b.log()==null?null:b.log().itemId());
        add(changes,Section.SOURCE,null,"logHostId",a.log()==null?null:a.log().hostId(),b.log()==null?null:b.log().hostId());
        add(changes,Section.SOURCE,null,"logSourceKey",a.log()==null?null:a.log().sourceKey(),b.log()==null?null:b.log().sourceKey());
        add(changes,Section.SOURCE,null,"logSourceUnit",a.log()==null?null:a.log().sourceUnit(),b.log()==null?null:b.log().sourceUnit());
        add(changes,Section.SOURCE,null,"logSourceValueType",a.log()==null?null:a.log().sourceValueType(),b.log()==null?null:b.log().sourceValueType());
        add(changes,Section.SOURCE,null,"logSourceDigest",a.log()==null?null:a.log().digest(),b.log()==null?null:b.log().digest());
        var x=before.target();var y=after.target();
        add(changes,Section.TARGET,null,"kind",x.kind(),y.kind());add(changes,Section.TARGET,null,"id",x.id(),y.id());add(changes,Section.TARGET,null,"revision",String.valueOf(x.revision()),String.valueOf(y.revision()));add(changes,Section.TARGET,null,"digest",x.digest(),y.digest());
        add(changes,Section.TARGET,null,"metricKey",x.metricKey(),y.metricKey());
        add(changes,Section.TARGET,null,"mappingId",x.mappingPin()==null?null:x.mappingPin().id(),y.mappingPin()==null?null:y.mappingPin().id());
        add(changes,Section.TARGET,null,"mappingRevision",x.mappingPin()==null?null:String.valueOf(x.mappingPin().revision()),y.mappingPin()==null?null:String.valueOf(y.mappingPin().revision()));
        add(changes,Section.TARGET,null,"mappingDigest",x.mappingPin()==null?null:x.mappingPin().digest(),y.mappingPin()==null?null:y.mappingPin().digest());
        var old=new HashMap<String,WorkflowDefinition.Node>();var next=new HashMap<String,WorkflowDefinition.Node>();before.nodes().forEach(n->old.put(n.id(),n));after.nodes().forEach(n->next.put(n.id(),n));
        var ids=new TreeSet<String>(old.keySet());ids.addAll(next.keySet());
        for(var id:ids) {
            var l=old.get(id);var r=next.get(id);
            add(changes,Section.NODE,id,"type",l==null?null:l.type().name(),r==null?null:r.type().name());
            add(changes,Section.OPERATOR,id,"version",l==null?null:l.version(),r==null?null:r.version());add(changes,Section.OPERATOR,id,"operatorDigest",l==null?null:l.operatorDigest(),r==null?null:r.operatorDigest());
            var left=l==null?Map.<String,String>of():l.config();var right=r==null?Map.<String,String>of():r.config();var keys=new TreeSet<String>(left.keySet());keys.addAll(right.keySet());
            for(var key:keys)add(changes,l!=null&&l.type()==WorkflowDefinition.Type.MAP||r!=null&&r.type()==WorkflowDefinition.Type.MAP?Section.MAPPING:Section.PARAMETER,id,key,left.get(key),right.get(key));
        }
        var oldEdges=new HashSet<>(before.edges());var newEdges=new HashSet<>(after.edges());
        var edgeOrder=Comparator.comparing(WorkflowDefinition.Edge::from).thenComparing(WorkflowDefinition.Edge::to);var edges=new TreeSet<>(edgeOrder);edges.addAll(oldEdges);edges.addAll(newEdges);
        for(var e:edges)add(changes,Section.EDGE,null,e.from()+" → "+e.to(),oldEdges.contains(e)?"present":null,newEdges.contains(e)?"present":null);
        add(changes,Section.ORDER,null,"nodes",String.join(" → ",before.nodes().stream().map(WorkflowDefinition.Node::id).toList()),String.join(" → ",after.nodes().stream().map(WorkflowDefinition.Node::id).toList()));
        if(oldEdges.equals(newEdges))add(changes,Section.ORDER,null,"edges",edgeSequence(before.edges()),edgeSequence(after.edges()));
        if(changes.size()>1200)throw new IllegalArgumentException("Comparison exceeds budget");return List.copyOf(changes);
    }
    private static String edgeSequence(List<WorkflowDefinition.Edge> edges){return String.join(", ",edges.stream().map(e->e.from()+" → "+e.to()).toList());}
    private static void add(List<Change> changes,Section section,String nodeId,String key,String before,String after){if(!Objects.equals(before,after))changes.add(new Change(section,nodeId,key,before,after));}
}
