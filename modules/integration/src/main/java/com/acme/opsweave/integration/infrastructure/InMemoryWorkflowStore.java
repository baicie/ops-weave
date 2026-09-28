package com.acme.opsweave.integration.infrastructure;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.SourceSetup;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;
/** Explicit development-only store; never selected as a database fallback. */
public final class InMemoryWorkflowStore implements WorkflowStore {
 private record Key(String owner,String id,int revision) {}
 private static final class Data { Map<Key,SourceSetup> setups=new HashMap<>(); Map<Key,Entry> drafts=new HashMap<>(); Map<Key,Entry> versions=new HashMap<>(); Map<String,List<Run>> runs=new HashMap<>(); Data copy() { var n=new Data(); n.setups.putAll(setups); n.drafts.putAll(drafts);n.versions.putAll(versions);runs.forEach((k,v)->n.runs.put(k,new ArrayList<>(v)));return n;} }
 private final Map<TenantId,Data> tenants=new HashMap<>();
 public synchronized <T> T transaction(TenantId tenant,Work<T> work) {
  var d=tenants.getOrDefault(tenant,new Data()).copy();
  T result=work.apply(new Session() {
   public Optional<Entry> draft(String owner,String id,int rev) {return Optional.ofNullable(d.drafts.get(new Key(owner,id,rev)));}
   public Optional<Entry> published(String id,int rev) {return Optional.ofNullable(d.versions.get(new Key("",id,rev)));}
   public List<Entry> drafts(String owner) {return d.drafts.entrySet().stream().filter(e->e.getKey().owner.equals(owner)&&!d.versions.containsKey(new Key("",e.getKey().id,e.getKey().revision))).map(Map.Entry::getValue).sorted(Comparator.comparing(Entry::updatedAt).reversed()).toList();}
   public List<Entry> published() {return d.versions.values().stream().sorted(Comparator.comparing(Entry::updatedAt).reversed()).toList();}
   public void saveDraft(String owner,Entry e) {d.drafts.put(new Key(owner,e.definition().id(),e.definition().revision()),e);}
   public void publish(Entry e,String owner) {d.versions.put(new Key("",e.definition().id(),e.definition().revision()),e);}
   public void addRun(String owner,Run run) {d.runs.computeIfAbsent(owner,k->new ArrayList<>()).addFirst(run);}
   public Optional<SourceSetup> setup(String owner,UUID id) {return Optional.ofNullable(d.setups.get(new Key(owner,id.toString(),1)));}
   public List<SourceSetup> setups(String owner) {return d.setups.entrySet().stream().filter(e->e.getKey().owner.equals(owner)).map(Map.Entry::getValue).sorted(Comparator.comparing(SourceSetup::createdAt).reversed()).toList();}
   public void saveSetup(String owner,SourceSetup setup) {if(d.setups.putIfAbsent(new Key(owner,setup.id().toString(),1),setup)!=null)throw new IllegalStateException("Setup exists");}
   public List<Run> runs(String owner) {return List.copyOf(d.runs.getOrDefault(owner,List.of()));}
  });tenants.put(tenant,d);return result;
 }
}
