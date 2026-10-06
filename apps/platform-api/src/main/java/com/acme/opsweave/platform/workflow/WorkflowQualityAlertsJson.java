package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowQualityAlerts.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowQualityAlertsJson {
    private WorkflowQualityAlertsJson() {}
    private static void shape(JsonNode n,String... keys){fields(n,Set.of(keys));if(n.size()!=keys.length)throw new IllegalArgumentException();}
    private static List<Rule> rules(JsonNode n,boolean canonical){if(n==null||!n.isArray()||n.size()>4)throw new IllegalArgumentException();var rules=new ArrayList<Rule>();for(var row:n){shape(row,"kind","threshold");rules.add(new Rule(Kind.valueOf(text(row,"kind")),integer(row,"threshold",null)));}var sorted=rules.stream().sorted(Comparator.comparing(Rule::kind)).toList();if(canonical&&!sorted.equals(rules))throw new IllegalArgumentException();return rules;}
    public static Command command(String json){try{var n=CatalogJson.JSON.readTree(json);shape(n,"requestId","id","revision","digest","expectedVersion","windowSeconds","rules");return new Command(WorkflowRecoveryJson.canonical(text(n,"requestId")),text(n,"id"),integer(n,"revision",null),text(n,"digest"),integer(n,"expectedVersion",null),integer(n,"windowSeconds",null),rules(n.get("rules"),false));}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid threshold command");}}
    private static Configuration configuration(JsonNode n){shape(n,"reference","editVersion","windowSeconds","rules","updatedAt");var ref=n.get("reference");shape(ref,"id","revision","digest");return new Configuration(new WorkflowQuality.Reference(text(ref,"id"),integer(ref,"revision",null),text(ref,"digest")),integer(n,"editVersion",null),integer(n,"windowSeconds",null),rules(n.get("rules"),true),WorkflowSampleRecoveryJson.instant(text(n,"updatedAt")));}
    public static Configuration configuration(String json){return configuration(CatalogJson.JSON.readTree(json));}
    public static Receipt receipt(String json){var n=CatalogJson.JSON.readTree(json);shape(n,"schemaVersion","requestId","commandDigest","acceptedAt","configuration");return new Receipt(text(n,"schemaVersion"),WorkflowRecoveryJson.canonical(text(n,"requestId")),text(n,"commandDigest"),WorkflowSampleRecoveryJson.instant(text(n,"acceptedAt")),configuration(n.get("configuration")));}
}
