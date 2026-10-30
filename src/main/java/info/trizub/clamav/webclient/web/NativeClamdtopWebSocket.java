package info.trizub.clamav.webclient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Real ncurses clamdtop over a dedicated PTY via util-linux script, NOT a STATS imitation.
 * Each authenticated connection owns its process; never accepts shell commands or arbitrary hosts. */
@Component
public final class NativeClamdtopWebSocket extends TextWebSocketHandler {
    private final EndpointService endpoints;
    private final ObjectMapper mapper;
    private final Map<String,Process> running = new ConcurrentHashMap<>();
    private final Map<String,Object> sendLocks = new ConcurrentHashMap<>();
    private static final int MAX_CONNECTIONS = 8;

    public NativeClamdtopWebSocket(EndpointService endpoints, ObjectMapper mapper) {
        this.endpoints=endpoints;this.mapper=mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (running.size() >= MAX_CONNECTIONS) {
            send(session,"error","Too many active clamdtop connections");session.close();return;
        }
        URI uri=session.getUri();
        Map<String,String> query=parameters(uri == null ? null : uri.getRawQuery());
        int cols=bounded(query.get("cols"),150,30,400);
        int rows=bounded(query.get("rows"),38,16,160);
        ClamdEndpoint ep;
        try {
            String id=query.get("endpointId");
            ep=id==null||id.isBlank()?endpoints.defaultEndpointOrEnsure():endpoints.get(Long.parseLong(id));
        }catch(Exception e){send(session,"error","Unknown clamd endpoint");session.close();return;}
        if(ep==null||!ep.isEnabled()) {send(session,"error","Endpoint is not enabled");session.close();return;}
        String spec;
        try { spec=connectionSpec(ep); }
        catch (IllegalArgumentException ex) {
            send(session,"error","Invalid configured clamd host or port");session.close();return;
        }
        // util-linux script allocates the actual PTY that the ncurses monitor requires.
        String command="stty cols "+cols+" rows "+rows+"; exec /usr/bin/clamdtop "+shellQuote(spec);
        ProcessBuilder pb=new ProcessBuilder("/usr/bin/script","-q","-c",command,"/dev/null");
        pb.redirectErrorStream(true);
        pb.environment().put("TERM","xterm-256color");
        pb.environment().put("COLUMNS",String.valueOf(cols));
        pb.environment().put("LINES",String.valueOf(rows));
        Process process;
        try {process=pb.start();}
        catch(Exception e) {send(session,"error","Could not start clamdtop. Check runtime packages: clamav-daemon, util-linux, ncurses-term");session.close();return;}
        running.put(session.getId(),process);
        sendLocks.put(session.getId(),new Object());
        send(session,"status","Connected to clamdtop ("+spec+")");
        Thread reader=new Thread(()->{
            try(InputStream input=process.getInputStream()){
                byte[] bytes=new byte[8192];int len;
                while((len=input.read(bytes))!=-1 && session.isOpen()) {
                    ObjectNode msg=mapper.createObjectNode();msg.put("type","data");
                    msg.put("base64",Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes,len)));
                    write(session,msg.toString());
                }
            }catch(Exception ignored) { /* Closing a session interrupts the PTY reader. */ }
            finally {cleanup(session);try{if(session.isOpen())session.close();}catch(Exception ignored){}}
        },"clamdtop-pty-"+session.getId());
        reader.setDaemon(true);reader.start();
    }

    static String connectionSpec(ClamdEndpoint endpoint) {
        String host=endpoint.getHost();
        if (host==null || host.isBlank() || host.length()>255 || host.chars().anyMatch(c -> c<32))
            throw new IllegalArgumentException("Invalid endpoint");
        if (host.startsWith("/")) return host;
        if (!host.matches("[A-Za-z0-9_:\\[][A-Za-z0-9_.:%\\-\\[\\]]{0,254}") || endpoint.getPort()<1 || endpoint.getPort()>65535)
            throw new IllegalArgumentException("Invalid endpoint");
        return (host.contains(":") && !host.startsWith("[") ? "["+host+"]" : host)+":"+endpoint.getPort();
    }

    static String shellQuote(String value) { return "'"+value.replace("'","'\"'\"'")+"'"; }

    @Override
    protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        Process process=running.get(session.getId());
        if(process==null || !process.isAlive() || message.getPayloadLength()>4096)return;
        JsonNode json;
        try{json=mapper.readTree(message.getPayload());}catch(Exception e){return;}
        if(!"input".equals(json.path("type").asText()))return;
        String input=json.path("data").asText("");
        if(input.length()>128)return;
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){cleanup(session);}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception){cleanup(session);try{session.close();}catch(Exception ignored){}}

    private void cleanup(WebSocketSession session) {
        Process process=running.remove(session.getId());
        sendLocks.remove(session.getId());
        if(process!=null){process.destroy();if(process.isAlive())process.destroyForcibly();}
    }
    private void write(WebSocketSession session,String value) throws Exception {
        Object lock=sendLocks.computeIfAbsent(session.getId(),id->new Object());
        synchronized(lock){if(session.isOpen())session.sendMessage(new TextMessage(value));}
    }
    private void send(WebSocketSession session,String type,String value) throws Exception {
        ObjectNode msg=mapper.createObjectNode();msg.put("type",type);msg.put("message",value);write(session,msg.toString());
    }
    private static int bounded(String raw,int fallback,int min,int max) {
        try{return Math.max(min,Math.min(max,Integer.parseInt(raw)));}catch(Exception e){return fallback;}
    }
    private static Map<String,String> parameters(String value) {
        Map<String,String> result=new java.util.HashMap<>();
        if(value==null)return result;
        for(String item:value.split("&")){
            String[] parts=item.split("=",2);
            if(parts.length==2)result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        return result;
    }
}
