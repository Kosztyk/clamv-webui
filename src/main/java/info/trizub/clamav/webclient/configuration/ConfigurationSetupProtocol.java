package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Transport-independent, cookie-free recovery API. No database or Spring context needed. */
public final class ConfigurationSetupProtocol implements AutoCloseable {
    public record Request(String method,String path,Map<String,String> headers,byte[] body,String remote,boolean secure) { }
    public record Response(int status,String contentType,byte[] body,boolean start) { }
    private final ConfigurationEngine engine;
    private final ConfigurationConnectionTester tests=new ConfigurationConnectionTester();
    private final ObjectMapper json=new ObjectMapper();
    private final boolean canStart;
    private final String token;
    private final Map<String,ConfigurationEngine.Review> reviews=new ConcurrentHashMap<>();
    private final Map<String,Attempts> attempts=new LinkedHashMap<>();
    private record Attempts(long since,int count) { }
    public ConfigurationSetupProtocol(ConfigurationEngine engine,boolean canStart) {
        this.engine=engine;this.canStart=canStart;this.token=engine.store().recoveryToken();
    }
    public Response handle(Request request) {
        try {
            String path=request.path();
            if(request.method().equals("GET") && (path.equals("") || path.equals("/") || path.equals("/setup")))
                return asset("/configuration-center/setup.html","text/html; charset=utf-8");
            if(request.method().equals("GET") && path.startsWith("/assets/")) {
                return switch(path) {
                    case "/assets/configuration-center.js" -> asset("/static/js/configuration-center.js","text/javascript; charset=utf-8");
                    case "/assets/configuration-center.css" -> asset("/static/css/configuration-center.css","text/css; charset=utf-8");
                    default -> response(404,Map.of("message","Not found"),false);
                };
            }
            if(!request.method().equals("POST") || !path.startsWith("/api/")) return response(405,Map.of("message","Method not allowed"),false);
            if(request.body().length>65536 || !request.headers().getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json"))
                return response(400,Map.of("message","Use a bounded JSON request"),false);
            if(!sameOrigin(request)) return response(403,Map.of("message","Cross-origin configuration requests are denied"),false);
            @SuppressWarnings("unchecked") Map<String,Object> body=json.readValue(request.body(),Map.class);
            String provided=request.headers().getOrDefault("authorization","");
            if(provided.startsWith("Bearer ")) provided=provided.substring(7);
            else provided=path.equals("/api/login")?Objects.toString(body.get("token"),""):"";
            int authentication=authenticate(provided,request.remote());
            if(authentication!=200) return response(authentication,Map.of("message",authentication==429
                    ?"Too many failed recovery attempts. Wait one minute before retrying."
                    :"Recovery credential does not match this application instance. Read the current credential from this container's configuration volume."),false);
            if(path.equals("/api/login") || path.equals("/api/view")) return response(200,view(),false);
            if(path.equals("/api/start")) {
                if(!canStart) throw new IllegalArgumentException("Restart or recreate the Web UI container to activate startup settings");
                if(!engine.store().snapshot().setupComplete()) throw new IllegalArgumentException("Review and save configuration before starting the application");
                if(engine.store().requiresDatabaseChoice(engine.values(true).get("spring.datasource.url"))) throw new IllegalArgumentException("The previous external database configuration is missing. Explicitly review and confirm a database selection before starting");
                engine.store().audit("recovery","APPLICATION_START_REQUESTED",Set.of(),"REQUESTED");
                return response(200,Map.of("ok",true,"message","Starting the application. The login page becomes available after initialization."),true);
            }
            expireReviews();
            if(path.equals("/api/apply")) {
                var review=reviews.remove(Objects.toString(body.get("reviewId"),""));
                if(review==null) throw new IllegalArgumentException("Review expired; review the changes again");
                if(review.sensitive()) ConfigurationTransport.requireConfirmation(request.secure(),Boolean.TRUE.equals(body.get("acknowledgeHttp")));
                engine.apply(review,Boolean.TRUE.equals(body.get("acknowledgeDatabase")),"recovery");
                return response(200,Map.of("ok",true,"message",canStart?"Configuration saved. Start the application when ready.":"Configuration saved. Restart the Web UI container to activate startup settings.","view",view()),false);
            }
            Map<String,String> changes=stringMap(body.get("changes"));Set<String> clear=stringSet(body.get("clearSecrets"));
            if(changes.entrySet().stream().anyMatch(e->ConfigurationCatalog.get(e.getKey()).secret() && !e.getValue().isBlank()))
                ConfigurationTransport.requireConfirmation(request.secure(),Boolean.TRUE.equals(body.get("acknowledgeHttp")));
            var review=engine.review(Objects.toString(body.get("version"),""),changes,clear);
            if(path.equals("/api/review")) {
                if(reviews.size()>=16) throw new IllegalArgumentException("Too many pending reviews; wait or reload");
                reviews.put(review.id(),review);
                return response(200,Map.of("reviewId",review.id(),"changes",review.summary(),"sensitive",false,
                        "databaseChange",review.databaseChange(),"expires",review.expires().toString()),false);
            }
            if(path.equals("/api/test")) {
                return response(200,tests.test(Objects.toString(body.get("section"),""),engine.draftValues(review)),false);
            }
            return response(404,Map.of("message","Not found"),false);
        } catch(Exception e) {
            boolean safe=e instanceof IllegalArgumentException || e instanceof ConcurrentModificationException;
            String message=safe?Objects.toString(e.getMessage(),"Invalid configuration request"):"Configuration operation failed. Restore the configuration/key backup or check storage and network availability.";
            return response(e instanceof ConcurrentModificationException?409:400,Map.of("ok",false,"message",message),false);
        }
    }
    private Map<String,Object> view() {
        Map<String,Object> view=new LinkedHashMap<>(engine.view());view.put("setup",true);view.put("canStart",canStart);
        view.put("setupComplete",engine.store().snapshot().setupComplete());
        view.put("recovery",!canStart || engine.store().snapshot().setupComplete());return view;
    }
    private synchronized int authenticate(String supplied,String remote) {
        long now=System.currentTimeMillis();
        attempts.entrySet().removeIf(e->now-e.getValue().since()>60_000);
        var previous=attempts.getOrDefault(remote,new Attempts(now,0));
        if(previous.count()>=8) return 429;
        boolean valid=supplied.length()<=256 && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));
        if(valid) attempts.remove(remote);
        else {
            if(attempts.size()>=256) attempts.remove(attempts.keySet().iterator().next());
            attempts.put(remote,new Attempts(previous.since(),previous.count()+1));
        }
        return valid?200:401;
    }
    private static boolean sameOrigin(Request request) {
        String origin=request.headers().get("origin");
        if(origin==null) return true; // Non-browser callers still require the explicit secret.
        try {
            URI source=URI.create(origin),target=URI.create((request.secure()?"https://":"http://")+request.headers().getOrDefault("host",""));
            return Objects.equals(source.getScheme(),target.getScheme()) && Objects.equals(source.getHost(),target.getHost()) && port(source)==port(target);
        } catch(Exception e) { return false; }
    }
    private static int port(URI uri) { return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort(); }
    private void expireReviews() { reviews.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now())); }
    private Response asset(String path,String type) throws Exception {
        try(var input=ConfigurationSetupProtocol.class.getResourceAsStream(path)) {
            if(input==null) return response(404,Map.of("message","Not found"),false);
            return new Response(200,type,input.readAllBytes(),false);
        }
    }
    private Response response(int status,Object value,boolean start) {
        try { return new Response(status,"application/json; charset=utf-8",json.writeValueAsBytes(value),start); }
        catch(Exception e) { return new Response(500,"application/json","{\"message\":\"Configuration response failed\"}".getBytes(StandardCharsets.UTF_8),false); }
    }
    private static Map<String,String> stringMap(Object input) {
        if(input==null) return Map.of();
        if(!(input instanceof Map<?,?> map) || map.size()>150) throw new IllegalArgumentException("Invalid settings object");
        Map<String,String> values=new LinkedHashMap<>();
        map.forEach((k,v)->{if(!(k instanceof String) || !(v instanceof String)) throw new IllegalArgumentException("Use string setting values");values.put((String)k,(String)v);});
        return values;
    }
    private static Set<String> stringSet(Object input) {
        if(input==null) return Set.of();
        if(!(input instanceof List<?> list) || list.size()>150) throw new IllegalArgumentException("Invalid secret removal list");
        Set<String> values=new LinkedHashSet<>();
        for(Object entry:list) { if(!(entry instanceof String value)) throw new IllegalArgumentException("Invalid secret removal entry");values.add(value); }
        return values;
    }
    @Override public void close() { tests.close();reviews.clear(); }
}
