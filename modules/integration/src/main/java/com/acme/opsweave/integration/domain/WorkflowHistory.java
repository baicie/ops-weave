package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Immutable recorded-check history; no business payload or inference of unrecorded runs. */
public final class WorkflowHistory {
    private WorkflowHistory() {}
    public static final int PAGE_SIZE=20, CAPACITY=200, CURSOR_SECONDS=600;
    public record Row(long sequence,WorkflowDiagnostics.Stored stored) {
        public Row {if(sequence<1)throw new IllegalArgumentException();Objects.requireNonNull(stored);}
    }
    /** Private selection metadata. Only a scope-bound sealed token may carry this across requests. */
    public record Cursor(String schemaVersion,Instant snapshotAt,long watermark,int recordedCount,int seen,UUID lastId) {
        public Cursor {Objects.requireNonNull(snapshotAt);Objects.requireNonNull(lastId);if(!"2.0".equals(schemaVersion)||watermark<1||recordedCount<1||recordedCount>CAPACITY||seen<1||seen>=recordedCount||seen%PAGE_SIZE!=0)throw new IllegalArgumentException();}
    }
    public static final Comparator<WorkflowDiagnostics.Observation> ORDER=Comparator.comparing(WorkflowDiagnostics.Observation::completedAt).reversed().thenComparing(o->o.id().toString());
    public record Page(String schemaVersion,Instant asOf,Instant snapshotAt,WorkflowQuality.Reference reference,
                       String coverage,int offset,int recordedCount,List<WorkflowDiagnostics.Observation> items,
                       String nextCursor,boolean hasMore) {
        public Page {
            Objects.requireNonNull(asOf);Objects.requireNonNull(snapshotAt);Objects.requireNonNull(reference);items=List.copyOf(items);
            if(!"2.0".equals(schemaVersion)||!"RECORDED_CHECKS".equals(coverage)||snapshotAt.isAfter(asOf)||!snapshotAt.plusSeconds(CURSOR_SECONDS).isAfter(asOf)
                ||recordedCount<0||recordedCount>CAPACITY||offset<0||offset>recordedCount||recordedCount>0&&offset>=recordedCount||offset%PAGE_SIZE!=0||items.size()!=Math.min(PAGE_SIZE,recordedCount-offset)
                ||hasMore!=(offset+items.size()<recordedCount)||hasMore!=(nextCursor!=null)||nextCursor!=null&&(nextCursor.length()>1024||!nextCursor.matches("h1\\.[A-Za-z0-9_-]+"))
                ||items.stream().map(WorkflowDiagnostics.Observation::id).distinct().count()!=items.size())throw new IllegalArgumentException();
            for(int i=0;i<items.size();i++){var o=items.get(i);if(!o.reference().equals(reference)||o.completedAt().isAfter(snapshotAt)||i>0&&ORDER.compare(items.get(i-1),o)>=0)throw new IllegalArgumentException();}
        }
    }
}
