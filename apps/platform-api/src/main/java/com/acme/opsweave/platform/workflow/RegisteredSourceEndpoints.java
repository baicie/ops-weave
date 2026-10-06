package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.api.SourceEndpointCatalog;
import com.acme.opsweave.integration.domain.SourceEndpoint;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Startup-only local deployment configuration, outside repositories. Empty configuration is closed. */
@Component
public final class RegisteredSourceEndpoints implements SourceEndpointCatalog {
    private record Entry(SourceEndpoint endpoint,Set<TenantId> tenants) {}
    private final Map<String,Entry> entries;
    public RegisteredSourceEndpoints(@Value("${opsweave.sources.endpoints-path:}") String file,OpsweaveProperties properties){
        if(file==null||file.isBlank()){entries=Map.of();return;}
        try {
            var configured=Path.of(file);if(!configured.isAbsolute())throw new IllegalArgumentException();var path=configured.toRealPath();
            for(var parent=path.getParent();parent!=null;parent=parent.getParent())if(Files.exists(parent.resolve(".git")))throw new IllegalArgumentException();
            if(!Files.isRegularFile(path))throw new IllegalArgumentException();byte[] bytes;
            try(var in=Files.newInputStream(path)){bytes=in.readNBytes(65537);}if(bytes.length==0||bytes.length>65536)throw new IllegalArgumentException();
            var n=CatalogJson.JSON.readTree(bytes);fields(n,Set.of("schemaVersion","endpoints"));if(!"2.0".equals(text(n,"schemaVersion")))throw new IllegalArgumentException();
            var rows=n.get("endpoints");if(rows==null||!rows.isArray()||rows.size()>32)throw new IllegalArgumentException();var parsed=new LinkedHashMap<String,Entry>();
            for(var row:rows){
                fields(row,Set.of("id","name","connectorKind","address","tenants"));if(!"ZABBIX_HOST".equals(text(row,"connectorKind")))throw new IllegalArgumentException();
                String address=text(row,"address");validateAddress(address,properties);var endpoint=SourceEndpoint.registered(text(row,"id"),text(row,"name"),address);
                var tenants=row.get("tenants");if(tenants==null||!tenants.isArray()||tenants.isEmpty()||tenants.size()>32)throw new IllegalArgumentException();var permitted=new HashSet<TenantId>();
                for(var tenant:tenants){if(!tenant.isString()||!permitted.add(new TenantId(tenant.asText())))throw new IllegalArgumentException();}
                if(parsed.putIfAbsent(endpoint.id(),new Entry(endpoint,Set.copyOf(permitted)))!=null)throw new IllegalArgumentException();
            }
            entries=Map.copyOf(parsed);
        }catch(IOException|RuntimeException invalid){throw new IllegalStateException("Source endpoint configuration is invalid");}
    }
    public List<SourceEndpoint> list(TenantId tenant){return entries.values().stream().filter(e->e.tenants().contains(tenant)).map(Entry::endpoint).sorted(Comparator.comparing(SourceEndpoint::id)).toList();}
    public Optional<SourceEndpoint> find(TenantId tenant,String id){var entry=entries.get(id);return entry!=null&&entry.tenants().contains(tenant)?Optional.of(entry.endpoint()):Optional.empty();}
    /** Numeric addresses only: no DNS resolution, scoped IPv6, proxy, redirect or URL normalization fallback. */
    public static URI validateAddress(String address,OpsweaveProperties properties){
        if(address==null||address.length()>1024||address.chars().anyMatch(c->c<33||c>126)||address.contains("\\")||address.contains("%"))throw new IllegalArgumentException("Invalid registered source address");
        var uri=URI.create(address);String scheme=uri.getScheme();if(!Set.of("http","https").contains(Objects.toString(scheme,""))||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535||!"/api_jsonrpc.php".equals(uri.getRawPath()))throw new IllegalArgumentException("Invalid registered source address");
        String host=uri.getHost();if(host==null||!uri.getRawAuthority().equals(host+(uri.getPort()<0?"":":"+uri.getPort())))throw new IllegalArgumentException("Invalid registered source address");InetAddress ip;
        try {
            if(host.startsWith("[")&&host.endsWith("]")){
                String literal=host.substring(1,host.length()-1);if(!literal.matches("[0-9A-Fa-f:]{2,39}")||!literal.contains(":"))throw new IllegalArgumentException();ip=InetAddress.getByName(literal);if(!(ip instanceof Inet6Address))throw new IllegalArgumentException();
            }else {
                String[] parts=host.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException();byte[] raw=new byte[4];for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();int value=Integer.parseInt(parts[i]);if(value>255)throw new IllegalArgumentException();raw[i]=(byte)value;}ip=InetAddress.getByAddress(raw);
            }
        }catch(UnknownHostException invalid){throw new IllegalArgumentException("Invalid registered source address");}
        boolean loopback=ip.isLoopbackAddress();var auth=properties==null?null:properties.auth();boolean localDev=auth!=null&&"dev".equals(auth.mode())&&auth.bindLoopbackOnly();
        byte[] raw=ip.getAddress();boolean invalidRange=ip.isAnyLocalAddress()||ip.isMulticastAddress()||ip.isLinkLocalAddress()||raw.length==4&&((raw[0]&255)==0||(raw[0]&255)>=224)||raw.length==16&&!loopback&&((raw[0]&254)!=0xfc&&(raw[0]&224)!=0x20);
        if(invalidRange||loopback&&!localDev||scheme.equals("http")&&(!localDev||!loopback))throw new IllegalArgumentException("Invalid registered source address");
        return uri;
    }
    @Override public String toString(){return "RegisteredSourceEndpoints[count="+entries.size()+"]";}
}
