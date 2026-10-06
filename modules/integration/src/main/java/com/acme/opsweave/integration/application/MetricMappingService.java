package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.telemetry.api.MetricMappingStore;
import com.acme.opsweave.telemetry.domain.*;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.telemetry.domain.MetricMappingFailure.Code.*;

/** Explicit metadata-only maintenance. Submitted pins select trusted registered definitions, never rules. */
public final class MetricMappingService {
    public record Command(UUID requestId, long expectedBindingVersion, MetricMappingPin mappingPin) {
        public Command { Objects.requireNonNull(requestId); Objects.requireNonNull(mappingPin);
            if(expectedBindingVersion<1 || expectedBindingVersion>=1_000_000_000L)throw new IllegalArgumentException(); }
    }
    public record View(MetricBinding binding, boolean canConfigure, List<MappingDefinition> candidates) {}
    public record Page(List<View> items, boolean truncated) {}
    private final MetricMappingStore store; private final MappingRegistry registry; private final Clock clock;
    public MetricMappingService(MetricMappingStore store, MappingRegistry registry, Clock clock) {
        this.store=Objects.requireNonNull(store);this.registry=Objects.requireNonNull(registry);this.clock=Objects.requireNonNull(clock);
    }
    private static MetricMappingFailure failure(MetricMappingFailure.Code code) { return new MetricMappingFailure(code); }
    public static void identity(String source,String item) {
        if(source==null || !source.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || item==null || !item.matches("[1-9][0-9]{0,19}"))throw new IllegalArgumentException();
    }
    private static boolean allowed(Principal p,ResourceRef resource,Permission permission) {
        return new Authorizer().decide(p,resource,permission).allowed();
    }
    private static boolean visible(Principal p,MetricBinding b) {
        return b.sourceType().equals("zabbix") && b.sourceInstanceId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
            && b.externalItemId().matches("[1-9][0-9]{0,19}") && b.hostExternalId().matches("[1-9][0-9]{0,19}")
            && b.metricKey().length()<=128 && p.tenantId().equals(b.tenantId()) && allowed(p,ResourceRef.metric(p.tenantId(),b.metricKey()),Permission.METRIC_READ)
            && allowed(p,ResourceRef.entity(p.tenantId(),b.entityId()),Permission.ENTITY_READ)
            && (allowed(p,ResourceRef.source(p.tenantId(),b.sourceInstanceId()),Permission.SOURCE_SYNC)
                || allowed(p,ResourceRef.source(p.tenantId(),b.sourceInstanceId()),Permission.SOURCE_CONFIGURE));
    }
    private static boolean writable(Principal p,MetricBinding b) {
        return visible(p,b) && allowed(p,ResourceRef.source(p.tenantId(),b.sourceInstanceId()),Permission.SOURCE_CONFIGURE)
            && allowed(p,ResourceRef.source(p.tenantId(),b.sourceInstanceId()),Permission.SOURCE_SYNC);
    }
    private MetricBinding current(MetricMappingStore.Session s,Principal p,String source,String item) {
        var b=s.binding(source,item).orElseThrow(()->failure(NOT_FOUND));
        if(!visible(p,b))throw failure(FORBIDDEN); return b;
    }
    private static boolean compatible(MetricBinding b,MetricDefinition d,MappingDefinition m) {
        return d!=null && b.sourceType().equals(m.connector()) && b.metricKey().equals(m.metricKey())
            && d.unit().equals(m.unit()) && d.metricType()==m.metricType() && d.valueType()==m.valueType()
            && d.dimensionSchema().equals(m.dimensionSchema())
            && (b.mappingPin()==null ? b.mappingRevision()==m.mappingRevision() && b.valueTransform().equals(m.valueTransform())
                && b.fixedDimensions().equals(m.fixedDimensions()) : b.mappingPin().equals(m.pin())
                || b.mappingPin().id().equals(m.id()) && m.mappingRevision()>b.mappingRevision());
    }
    private View view(MetricMappingStore.Session s,Principal p,MetricBinding b) {
        var d=s.definition(b.metricKey()).orElse(null);
        return new View(b,writable(p,b),registry.definitions().stream().filter(m->compatible(b,d,m)).toList());
    }
    public Page list(Principal p) {
        if(!p.has(Permission.METRIC_READ)||!p.has(Permission.ENTITY_READ)||!p.has(Permission.SOURCE_SYNC)&&!p.has(Permission.SOURCE_CONFIGURE))throw failure(FORBIDDEN);
        return store.mappingTransaction(p.tenantId(),s->{var scanned=s.bindings(p.resourceScope());var rows=scanned.stream().filter(b->visible(p,b)).toList();
            return new Page(rows.stream().limit(20).map(b->view(s,p,b)).toList(),rows.size()>20 || scanned.size()==201);});
    }
    public View read(Principal p,String source,String item) { identity(source,item);return store.mappingTransaction(p.tenantId(),s->view(s,p,current(s,p,source,item))); }
    public static String digest(String source,String item,Command c) {
        identity(source,item);return WorkflowDefinition.hash(List.of("metric-mapping-maintenance-v1",source,item,c.requestId().toString(),
            Long.toString(c.expectedBindingVersion()),c.mappingPin().id(),Integer.toString(c.mappingPin().revision()),c.mappingPin().digest()));
    }
    public MetricMappingReceipt receipt(Principal p,String source,String item,UUID request) {
        identity(source,item);return store.mappingTransaction(p.tenantId(),s->{ current(s,p,source,item);
            var r=s.receipt(p.subjectId().value(),request).orElseThrow(()->failure(NOT_FOUND));
            if(!r.binding().sourceInstanceId().equals(source)||!r.binding().externalItemId().equals(item))throw failure(NOT_FOUND);
            if(!visible(p,r.binding()))throw failure(FORBIDDEN);return r;});
    }
    public MetricMappingReceipt write(Principal p,String source,String item,Command command) {
        String digest=digest(source,item,command);
        return store.mappingTransaction(p.tenantId(),s->{ var old=current(s,p,source,item);if(!writable(p,old))throw failure(FORBIDDEN);
            var known=s.receipt(p.subjectId().value(),command.requestId());
            if(known.isPresent()){var r=known.get();if(!r.commandDigest().equals(digest)||!r.binding().sourceInstanceId().equals(source)
                ||!r.binding().externalItemId().equals(item))throw failure(CONFLICT);if(!visible(p,r.binding()))throw failure(FORBIDDEN);return r;}
            if(s.receiptCount(p.subjectId().value())>=200)throw failure(CAPACITY);
            if(old.version()!=command.expectedBindingVersion()||old.lifecycle()!=MetricLifecycle.ACTIVE)throw failure(CONFLICT);
            var m=registry.find(command.mappingPin()).orElseThrow(()->failure(INCOMPATIBLE));
            if(!compatible(old,s.definition(old.metricKey()).orElse(null),m))throw failure(INCOMPATIBLE);
            boolean changed=!m.pin().equals(old.mappingPin());
            var next=new MetricBinding(old.tenantId(),old.sourceType(),old.sourceInstanceId(),old.externalItemId(),old.entityId(),old.hostExternalId(),
                old.metricKey(),m.fixedDimensions(),old.sourceUnit(),m.valueTransform(),m.mappingRevision(),old.lifecycle(),old.version()+(changed?1:0),m.pin());
            if(changed)s.replace(next,old.version());
            var result=new MetricMappingReceipt(command.requestId(),old.version(),digest,old.mappingPin(),next,
                clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            s.addReceipt(p.subjectId().value(),result);return result;});
    }
}
