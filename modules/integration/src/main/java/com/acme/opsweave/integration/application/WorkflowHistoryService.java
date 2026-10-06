package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowHistory.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Reads an immutable selection under current authorization; no source, output or action port. */
public final class WorkflowHistoryService {
    private final WorkflowStore store;private final WorkflowHistoryCursors cursors;private final Clock clock;private final WorkflowDiagnosticService diagnostics;
    public WorkflowHistoryService(WorkflowStore store,WorkflowService workflows,WorkflowHistoryCursors cursors,Clock clock){this.store=store;this.cursors=cursors;this.clock=clock;diagnostics=new WorkflowDiagnosticService(store,workflows,clock);}
    public Page history(Principal principal,String id,int revision,String token){
        if(token!=null&&(token.length()>1024||!token.matches("h1\\.[A-Za-z0-9_-]+")))throw new IllegalArgumentException();
        return store.transaction(principal.tenantId(),s->{
            var reference=diagnostics.reference(principal,s,id,revision);cursors.requireAvailable();var owner=principal.subjectId().value();var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var cursor=token==null?null:cursors.open(principal.tenantId(),owner,reference,token);
            if(cursor!=null&&(cursor.snapshotAt().isAfter(now)||!cursor.snapshotAt().plusSeconds(WorkflowHistory.CURSOR_SECONDS).isAfter(now)))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            var snapshot=cursor==null?now:cursor.snapshotAt();var watermark=cursor==null?s.diagnosticHistoryWatermark(owner,reference):cursor.watermark();
            int total=s.diagnosticHistoryCount(owner,reference,snapshot,watermark),offset=cursor==null?0:cursor.seen();
            if(total<0||total>WorkflowHistory.CAPACITY)throw new IllegalStateException("History capacity is invalid");
            if(cursor!=null&&cursor.recordedCount()!=total)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            Row anchor=null;
            if(cursor!=null){anchor=s.diagnosticHistoryRow(owner,cursor.lastId()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.CONFLICT));var o=anchor.stored().observation();if(anchor.sequence()>watermark||!o.reference().equals(reference)||o.completedAt().isAfter(snapshot))throw new IllegalStateException("History anchor is invalid");WorkflowDiagnosticService.scope(principal,s,anchor.stored());}
            var rows=s.diagnosticHistoryRows(owner,reference,snapshot,watermark,anchor);
            if(rows.size()!=Math.min(WorkflowHistory.PAGE_SIZE+1,total-offset))throw new IllegalStateException("History selection is incomplete");
            for(var row:rows){if(row.sequence()>watermark)throw new IllegalStateException("History watermark is invalid");WorkflowDiagnosticService.scope(principal,s,row.stored());}
            var items=rows.stream().limit(WorkflowHistory.PAGE_SIZE).map(r->r.stored().observation()).toList();
            boolean more=offset+items.size()<total;String next=more?cursors.seal(principal.tenantId(),owner,reference,new Cursor("2.0",snapshot,watermark,total,offset+items.size(),items.getLast().id())):null;
            try{return new Page("2.0",now,snapshot,reference,"RECORDED_CHECKS",offset,total,items,next,more);}catch(IllegalArgumentException invalid){throw new IllegalStateException("History projection is invalid");}
        });
    }
}
