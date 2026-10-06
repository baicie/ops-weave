package com.acme.opsweave.catalog.domain;
import java.time.Instant;
import java.util.*;

public final class ModelReferences {
    private ModelReferences(){}
    public record Task(String kind,String state,long generation){public Task{if(!Set.of("HOST_SCAN","HOST_SCHEDULE").contains(kind)||!Set.of("RUNNING","STOPPED","FAILED").contains(state)||generation<1||generation>1000001)throw new IllegalArgumentException();}}
    public record Usage(String kind,String id,int revision,String digest,String label,String state,int editVersion,List<String> roles,List<String> fieldIds,List<Task> tasks){
        public Usage{roles=List.copyOf(roles);fieldIds=List.copyOf(fieldIds);tasks=List.copyOf(tasks);if(!Set.of("RELATION","WORKFLOW").contains(kind)||id==null||!(kind.equals("RELATION")?id.matches("(builtin|custom)\\.[a-z][a-z0-9_]{0,47}"):id.matches("[a-z][a-z0-9_-]{0,47}"))||revision<1||revision>(kind.equals("RELATION")?10000:1000000)||digest==null||!digest.matches("sha256:[a-f0-9]{64}")||label==null||label.isBlank()||label.length()>160||!Set.of("PUBLISHED","DRAFT").contains(state)||editVersion<0||editVersion>1000000||state.equals("PUBLISHED")&&editVersion!=0||state.equals("DRAFT")&&editVersion==0||roles.isEmpty()||roles.size()>2||roles.stream().distinct().count()!=roles.size()||!(kind.equals("WORKFLOW")?roles.equals(List.of("OUTPUT")):Set.of("FROM","TO").containsAll(roles))||fieldIds.size()>32||fieldIds.stream().distinct().count()!=fieldIds.size()||fieldIds.stream().anyMatch(f->!f.matches("[a-z][a-z0-9_]{0,47}"))||tasks.size()>2||tasks.stream().map(Task::kind).distinct().count()!=tasks.size()||kind.equals("RELATION")&&(!fieldIds.isEmpty()||!tasks.isEmpty()||!state.equals("PUBLISHED")))throw new IllegalArgumentException();}
    }
    public record Page(List<Usage> items,boolean truncated){public Page{items=List.copyOf(items);if(items.size()>50)throw new IllegalArgumentException();}}
    public record Report(ModelRevisionReview.Pin target,Instant inspectedAt,boolean workflowsAvailable,Page references){public Report{Objects.requireNonNull(target);Objects.requireNonNull(inspectedAt);Objects.requireNonNull(references);if(!workflowsAvailable&&references.items().stream().anyMatch(v->v.kind().equals("WORKFLOW")))throw new IllegalArgumentException();}}
}
