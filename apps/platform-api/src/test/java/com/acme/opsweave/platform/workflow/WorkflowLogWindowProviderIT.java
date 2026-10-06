package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Scope;
import com.acme.opsweave.integration.infrastructure.ZabbixWorkflowLogReader;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.platform.telemetry.ClickHouseWorkflowLogWindowSink;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Real local provider/storage, with an explicitly owned Synthetic Fixture. No public task is implied. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_LOG_WINDOW_FROM",matches=".+")
class WorkflowLogWindowProviderIT {
    @Test void completeDenseWindowTransformsAndConfirmsOneActualBulkBatch()throws Exception {
        var endpoint=URI.create(System.getenv("OPSWEAVE_TEST_LOG_SOURCE_URL"));var from=Instant.ofEpochSecond(Long.parseLong(System.getenv("OPSWEAVE_TEST_LOG_WINDOW_FROM")));var till=from.plusSeconds(60);
        var item=new SourceMetricDiscovery.Item(System.getenv("OPSWEAVE_TEST_LOG_ITEM"),System.getenv("OPSWEAVE_TEST_LOG_HOST"),"log[/var/log/opsweave-window-fixture.log]","Synthetic Fixture","","LOG","NO_MAPPING",null);
        var id=UUID.randomUUID();var source=new WorkflowDefinition.Source("ZABBIX_LOG",id.toString(),new WorkflowDefinition.ConfigurationPin(id,1,"sha256:"+"a".repeat(64)),null,WorkflowLogSourcePin.from(UUID.randomUUID(),item));
        var auth=new OpsweaveProperties.Auth("dev",true,new OpsweaveProperties.Auth.Dev("","","fixture","",""));var properties=new OpsweaveProperties(auth,null,null);
        var transport=new JacksonZabbixTransport().registered(endpoint,properties);var reader=new ZabbixWorkflowLogReader();
        var rows=reader.readWindow(endpoint,transport,System.getenv("OPSWEAVE_TEST_LOG_SOURCE_TOKEN"),source.log(),from,till,Instant.now());assertEquals(1000,rows.size());
        for(int i=0;i<rows.size();i++){assertEquals(from.plusNanos(i).toString(),rows.get(i).get("timestamp"));assertEquals("  Synthetic Fixture window "+i+" emoji 🧵\n<script>  ",rows.get(i).get("body"));assertNull(rows.get(i).get("eventSource"));assertNull(rows.get(i).get("logEventTime"));}
        var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("mapping",WorkflowDefinition.Type.MAP,"1",Map.of("timestamp","eventTime","body","body")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
        var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition("log-window-fixture",1,"Synthetic Fixture",source,new WorkflowDefinition.Target(null,1,null,"LOG"),nodes,List.of(new WorkflowDefinition.Edge("source","mapping"),new WorkflowDefinition.Edge("mapping","validate"),new WorkflowDefinition.Edge("validate","output"))));
        var scope=new Scope("log-window-fixture-"+UUID.randomUUID(),WorkflowDefinition.hash(List.of("fixture-owner")).substring(7),UUID.randomUUID(),d.id(),1,d.digest());
        var batch=WorkflowLogWindow.prepare(scope,WorkflowOperators.builtIn().compile(d,null),rows,from,till,Instant.now());var sink=new ClickHouseWorkflowLogWindowSink(URI.create(System.getenv("OPSWEAVE_TEST_LOGS_URL")),System.getenv("OPSWEAVE_TEST_LOGS_USER"),System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));
        assertTrue(sink.ready());sink.write(batch);var found=sink.read(scope);assertEquals(batch.records(),found);assertEquals(batch.digest(),WorkflowLogWindow.recordsDigest(scope,from,till,1000,0,found));
        // The same stable source positions are readable again, with no second storage write.
        var reread=reader.readWindow(endpoint,transport,System.getenv("OPSWEAVE_TEST_LOG_SOURCE_TOKEN"),source.log(),from,till,Instant.now());assertEquals(rows,reread);assertEquals(found,sink.read(scope));
        var other=new Scope(scope.tenant(),"b".repeat(64),scope.requestId(),scope.workflowId(),1,scope.digest());assertTrue(sink.read(other).isEmpty());
        var wire=CatalogJson.JSON.createObjectNode();wire.put("requestId",scope.requestId().toString());wire.put("workflowId",scope.workflowId());wire.put("revision",1);wire.put("digest",scope.digest());wire.put("from",from.toString());wire.put("till",till.toString());wire.put("inputCount",1000);wire.put("filtered",0);wire.put("batchDigest",batch.digest());wire.set("records",CatalogJson.JSON.valueToTree(found));
        String proof=System.getenv("OPSWEAVE_TEST_LOG_WINDOW_PROOF");if(proof!=null){var path=Path.of(proof);assertTrue(path.isAbsolute());Files.writeString(path,CatalogJson.JSON.writeValueAsString(wire)+"\n");}
    }
}
