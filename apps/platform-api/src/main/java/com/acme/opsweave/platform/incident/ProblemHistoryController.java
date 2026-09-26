package com.acme.opsweave.platform.incident;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.incident.application.ProblemHistoryService;
import com.acme.opsweave.incident.domain.ProblemHistoryQuery;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/problem-observations")
public final class ProblemHistoryController {
    private final PrincipalContext principal;
    private final ProblemHistoryService service;
    private final String storage;
    public ProblemHistoryController(PrincipalContext principal,ProblemHistoryService service,InventoryWiring wiring){this.principal=principal;this.service=service;this.storage=wiring.label();}
    @GetMapping
    public Object page(@PathVariable String incidentId,@RequestParam long version,@RequestParam long from,@RequestParam long till,
            @RequestParam(required=false)String asOf,@RequestParam(defaultValue="")String source,@RequestParam(defaultValue="")String eventId,
            @RequestParam(required=false)String after,@RequestParam(defaultValue="25")int limit,HttpServletRequest request) {
        if(!Set.of("version","from","till","asOf","source","eventId","after","limit").containsAll(request.getParameterMap().keySet())
                ||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw new IllegalArgumentException();
        var id=IncidentController.uuid(incidentId);ProblemHistoryQuery query;
        try{query=new ProblemHistoryQuery(version,from,till,asOf==null?Instant.now():Instant.parse(asOf),source,eventId,after==null?null:IncidentController.uuid(after),limit);}
        catch(DateTimeException invalid){throw new IllegalArgumentException("Invalid time window");}
        var page=service.read(principal.requirePrincipal(),id,query);
        var echo=new LinkedHashMap<String,Object>();echo.put("version",version);echo.put("from",from);echo.put("till",till);echo.put("asOf",query.asOf().toString());
        echo.put("source",source);echo.put("eventId",eventId);echo.put("after",query.after()==null?null:query.after().toString());echo.put("limit",limit);
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","1.0");result.put("storage",storage);result.put("incidentId",id.toString());result.put("query",echo);
        result.put("coverage","retained-normalized-current-ownership");result.put("gaps",List.of("PRE_RETENTION_HISTORY_UNAVAILABLE","VENDOR_RAW_PAYLOAD_NOT_RETAINED"));
        result.put("items",page.items().stream().map(ProblemHistoryJson::body).toList());result.put("nextCursor",page.nextCursor()==null?null:page.nextCursor().toString());return result;
    }
}
