package com.acme.opsweave.platform.persistence;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.platform.workflow.SourceSetupJson;
import com.acme.opsweave.integration.domain.SourceSetup;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

final class PostgresWorkflowStore implements WorkflowStore {
 private final DataSource source;
 PostgresWorkflowStore(DataSource source){this.source=source;}
 public <T>T transaction(TenantId tenant,Work<T> work){
  try(var c=source.getConnection()){c.setAutoCommit(false);try{
   try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setQueryTimeout(5);s.setString(1,"transform-workflow:"+tenant.value());s.execute();}
   T result=work.apply(new Jdbc(c,tenant.value()));c.commit();return result;
  }catch(RuntimeException|SQLException failure){c.rollback();throw failure;}}
  catch(SQLException failure){throw unavailable();}
 }
 private record Jdbc(Connection connection,String tenant) implements Session {
  public Optional<Entry> draft(String owner,String id,int revision){return find("SELECT entry,workflow_id,revision FROM integration.workflow_draft WHERE tenant_id=? AND owner_subject=? AND workflow_id=? AND revision=?","DRAFT",tenant,owner,id,revision);}
  public Optional<Entry> published(String id,int revision){return find("SELECT entry,workflow_id,revision FROM integration.workflow_version WHERE tenant_id=? AND workflow_id=? AND revision=?","PUBLISHED",tenant,id,revision);}
  private Optional<Entry> find(String sql,String state,Object...args){var entries=read(sql,state,args);return entries.stream().findFirst();}
  public List<Entry> drafts(String owner){return read("SELECT entry,workflow_id,revision FROM integration.workflow_draft d WHERE tenant_id=? AND owner_subject=? AND NOT EXISTS (SELECT 1 FROM integration.workflow_version v WHERE v.tenant_id=d.tenant_id AND v.workflow_id=d.workflow_id AND v.revision=d.revision) ORDER BY updated_at DESC,workflow_id,revision LIMIT 51","DRAFT",tenant,owner);}
  public List<Entry> published(){return read("SELECT entry,workflow_id,revision FROM integration.workflow_version WHERE tenant_id=? ORDER BY updated_at DESC,workflow_id,revision LIMIT 201","PUBLISHED",tenant);}
  private List<Entry> read(String sql,String state,Object...args){try(var s=prepare(sql,args);var r=s.executeQuery()){var list=new ArrayList<Entry>();while(r.next()){var e=WorkflowJson.decode(r.getString("entry"));if(!e.definition().id().equals(r.getString("workflow_id"))||e.definition().revision()!=r.getInt("revision")||!e.state().equals(state))throw unavailable();list.add(e);}return List.copyOf(list);}catch(SQLException|IllegalArgumentException failure){throw unavailable();}}
  public void saveDraft(String owner,Entry entry){
   update("INSERT INTO integration.workflow_draft (tenant_id,owner_subject,workflow_id,revision,entry,updated_at) VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT (tenant_id,owner_subject,workflow_id,revision) DO UPDATE SET entry=EXCLUDED.entry,updated_at=EXCLUDED.updated_at",tenant,owner,entry.definition().id(),entry.definition().revision(),WorkflowJson.encode(entry),Timestamp.from(entry.updatedAt()));
  }
  public void publish(Entry entry,String owner){update("INSERT INTO integration.workflow_version (tenant_id,workflow_id,revision,entry,published_by,updated_at) VALUES (?,?,?,?::jsonb,?,?)",tenant,entry.definition().id(),entry.definition().revision(),WorkflowJson.encode(entry),owner,Timestamp.from(entry.updatedAt()));}
  public void addRun(String owner,Run run){update("INSERT INTO integration.workflow_run (tenant_id,owner_subject,run_id,receipt,created_at) VALUES (?,?,?,?::jsonb,?)",tenant,owner,run.receipt().id(),CatalogJson.JSON.writeValueAsString(run),Timestamp.from(run.receipt().createdAt()));}
  public Optional<Run> run(String owner,UUID id){try(var s=prepare("SELECT receipt FROM integration.workflow_run WHERE tenant_id=? AND owner_subject=? AND run_id=?",tenant,owner,id);var r=s.executeQuery()){if(!r.next())return Optional.empty();var run=WorkflowJson.run(r.getString("receipt"));if(!run.receipt().id().equals(id))throw unavailable();return Optional.of(run);}catch(SQLException|IllegalArgumentException failure){throw unavailable();}}
  public List<Run> runs(String owner){try(var s=prepare("SELECT run_id,receipt FROM integration.workflow_run WHERE tenant_id=? AND owner_subject=? ORDER BY created_at DESC,run_id DESC LIMIT 201",tenant,owner);var r=s.executeQuery()){var result=new ArrayList<Run>();while(r.next()){var run=WorkflowJson.run(r.getString("receipt"));if(!run.receipt().id().equals(r.getObject("run_id",UUID.class)))throw unavailable();result.add(run);}return List.copyOf(result);}catch(SQLException|IllegalArgumentException failure){throw unavailable();}}
  public Optional<SourceSetup> setup(String owner,UUID id){return setupRows("SELECT setup_id,body FROM integration.source_setup WHERE tenant_id=? AND owner_subject=? AND setup_id=?",tenant,owner,id).stream().findFirst();}
  public List<SourceSetup> setups(String owner){return setupRows("SELECT setup_id,body FROM integration.source_setup WHERE tenant_id=? AND owner_subject=? ORDER BY created_at DESC,setup_id DESC LIMIT 201",tenant,owner);}
  private List<SourceSetup> setupRows(String sql,Object...args){try(var s=prepare(sql,args);var r=s.executeQuery()){var result=new ArrayList<SourceSetup>();while(r.next()){var setup=SourceSetupJson.decode(r.getString("body"));if(!setup.id().equals(r.getObject("setup_id",UUID.class)))throw unavailable();result.add(setup);}return List.copyOf(result);}catch(SQLException|IllegalArgumentException failure){throw unavailable();}}
  public void saveSetup(String owner,SourceSetup setup){update("INSERT INTO integration.source_setup (tenant_id,owner_subject,setup_id,body,created_at) VALUES (?,?,?,?::jsonb,?)",tenant,owner,setup.id(),CatalogJson.JSON.writeValueAsString(SourceSetupJson.wire(setup)),Timestamp.from(setup.createdAt()));}
  private void update(String sql,Object...args){try(var s=prepare(sql,args)){if(s.executeUpdate()!=1)throw unavailable();}catch(SQLException failure){throw unavailable();}}
  private PreparedStatement prepare(String sql,Object...args)throws SQLException{var s=connection.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
 }
 private static IllegalStateException unavailable(){return new IllegalStateException("Workflow store unavailable");}
}
