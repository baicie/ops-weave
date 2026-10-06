package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JacksonRegisteredTransportTest {
 static final OpsweaveProperties DEV=new OpsweaveProperties(new OpsweaveProperties.Auth("dev",true,null),null,null);
 static final String VERSION="{\"jsonrpc\":\"2.0\",\"method\":\"apiinfo.version\",\"params\":{},\"id\":1}";
 static HttpServer server()throws IOException{return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
 static URI uri(HttpServer server){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api_jsonrpc.php");}
 @Test void registeredRequestBypassesSystemProxyAndPreservesBoundedJsonRpcParser()throws Exception{
  var hits=new AtomicInteger();var proxyHits=new AtomicInteger();var server=server();server.createContext("/api_jsonrpc.php",e->{hits.incrementAndGet();assertNull(e.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"7.0.0\"}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});server.start();var old=ProxySelector.getDefault();
  try{ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){proxyHits.incrementAndGet();return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}public void connectFailed(URI uri,SocketAddress address,IOException e){}});var transport=new JacksonZabbixTransport().registered(uri(server),DEV);assertEquals("7.0.0",transport.readText(transport.exchange(uri(server),VERSION,null)));assertEquals(1,hits.get());assertEquals(0,proxyHits.get());assertThrows(IllegalStateException.class,()->transport.readText("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"7.0.0\"}"));}finally{ProxySelector.setDefault(old);server.stop(0);}
 }
 @Test void redirectIsRejectedWithoutSendingBearerToAnotherDestination()throws Exception{
  var redirected=new AtomicInteger();var source=server();var target=server();target.createContext("/api_jsonrpc.php",e->{redirected.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});target.start();source.createContext("/api_jsonrpc.php",e->{assertEquals("Bearer fixture-transport-token",e.getRequestHeaders().getFirst("Authorization"));e.getResponseHeaders().add("Location",uri(target).toString());e.sendResponseHeaders(307,-1);e.close();});source.start();
  try{var transport=new JacksonZabbixTransport().registered(uri(source),DEV);var e=assertThrows(IllegalStateException.class,()->transport.exchange(uri(source),VERSION,"fixture-transport-token"));assertEquals("Zabbix HTTP status 307",e.getMessage());assertNull(e.getCause());assertEquals(0,redirected.get());assertFalse(e.getMessage().contains("fixture-transport-token"));}finally{source.stop(0);target.stop(0);}
 }
 @Test void boundUriAndAnonymousMethodAreCheckedBeforeDispatch()throws Exception{
  var hits=new AtomicInteger();var server=server();server.createContext("/api_jsonrpc.php",e->{hits.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});server.start();
  try{var expected=uri(server);var transport=new JacksonZabbixTransport().registered(expected,DEV);assertThrows(IllegalStateException.class,()->transport.exchange(URI.create("http://127.0.0.2:"+server.getAddress().getPort()+"/api_jsonrpc.php"),VERSION,"fixture-token"));assertThrows(IllegalStateException.class,()->transport.exchange(expected,"{\"method\":\"host.get\"}",null));assertEquals(0,hits.get());assertThrows(IllegalArgumentException.class,()->new JacksonZabbixTransport().registered(URI.create("http://fixture.invalid/api_jsonrpc.php"),DEV));}finally{server.stop(0);}
 }
 @Test void oversizedReceiptIsCancelledAndNeverReturnedAsAPartialSuccessfulBody()throws Exception{
  var server=server();byte[] body=new byte[JacksonZabbixTransport.MAX_RESPONSE_BYTES+1];Arrays.fill(body,(byte)'x');server.createContext("/api_jsonrpc.php",e->{e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}catch(IOException cancelled){}});server.start();
  try{var transport=new JacksonZabbixTransport().registered(uri(server),DEV);var e=assertThrows(IllegalStateException.class,()->transport.exchange(uri(server),VERSION,null));assertEquals("Zabbix JSON-RPC transport failed",e.getMessage());assertNull(e.getCause());}finally{server.stop(0);}
 }
}
