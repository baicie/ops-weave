package com.acme.opsweave.integration.domain;

import com.acme.opsweave.inventory.domain.SourceReview;
import java.util.Map;

/** Fixed import profile. Separate engine identity; published Zabbix digests remain unchanged. */
public final class CmdbImportPipeline {
    public static final String ENGINE = "cmdb-host-import-v1";
    private static final PipelineDefinition BASE = PipelineDefinition.zabbixHostV1();
    public static final PipelineDefinition DEFINITION = new PipelineDefinition("cmdb-host-import", 1, "cmdb-import", "host",
        BASE.nodes(), BASE.edges(), ErrorPolicy.FAIL_FAST);
    public static final String DIGEST = PipelineVersion.digestOf(DEFINITION, ENGINE);
    private CmdbImportPipeline() {}
    public static Map<String,String> map(Map<String,String> raw) { return SourceReview.fields(raw, false); }
}
