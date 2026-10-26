package com.telamin.mongoose.plugin.svc.adminrest;
import com.telamin.mongoose.service.admin.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Independent HTTP probes: counts actual registry calls, not filter implementation state. */
class IndependentSecurityReleaseTest {
    @Test void crossOriginNullAndReboundRequestsCannotExecuteCommands() throws Exception { probe("127.0.0.1"); }
    @Test void loopbackDnsAliasCannotDisableRebindingProtection() throws Exception { probe("localhost."); }
    void probe(String bind) throws Exception {
        int port; try (ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {port=s.getLocalPort();}
        var service=new JavalinAdminCommandService(); service.setHost(bind); service.setListenPort(port);
        var calls=new AtomicInteger();
        var registry=(AdminCommandRegistry)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AdminCommandRegistry.class},(p,m,a)-> {
            if(m.getName().equals("commandList")) return List.of("help");
            if(m.getName().equals("processAdminCommandRequest")) {calls.incrementAndGet(); ((AdminCommandRequest)a[0]).getOutput().accept("ok");}
            return null;
        });
        service.adminRegistry(registry,"adminRegistry"); service.init(); 
        boolean protectedVersion=Boolean.parseBoolean(System.getProperty("security.probe.protected","true"));
        try {
            String local="127.0.0.1:"+port;
            String rebound="attacker.example:"+port;
            List<Integer> results=new ArrayList<>();
            results.add(post(port,"/admin",local,"Origin: http://attacker.example","Content-Type: text/plain"));
            results.add(post(port,"/admin",local,"Origin: null","Content-Type: text/plain"));
            results.add(post(port,"/api/help",rebound,"Origin: http://"+rebound,"Content-Type: application/json"));
            assertEquals(protectedVersion?List.of(403,403,403):List.of(200,200,200),results);
            assertEquals(protectedVersion?0:3,calls.get(),"forbidden requests must not reach the registry");
        } finally {service.tearDown();}
    }
    static int post(int port,String path,String host,String... headers) throws Exception {
        String body="{\"command\":\"help\"}";
        String request="POST "+path+" HTTP/1.1\r\nHost: "+host+"\r\n"+String.join("\r\n",headers)+"\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n"+body;
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000); socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            return Integer.parseInt(response.split(" ",3)[1]);
        }
    }
}
