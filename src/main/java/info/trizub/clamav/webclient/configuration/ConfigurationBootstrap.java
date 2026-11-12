package info.trizub.clamav.webclient.configuration;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** First-run/recovery listener is independent of DataSource, Flyway and application users. */
public final class ConfigurationBootstrap {
    private ConfigurationBootstrap() { }
    public static ConfigurableEnvironment environment(String[] arguments) {
        StandardEnvironment environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
        try {
            String json=environment.getProperty("spring.application.json",environment.getProperty("SPRING_APPLICATION_JSON",""));
            if(!json.isBlank()) {
                Map<String,Object> flattened=new LinkedHashMap<>();
                flatten("",new ObjectMapper().readValue(json,Map.class),flattened);
                environment.getPropertySources().addAfter("commandLineArgs",new MapPropertySource("spring.application.json",flattened));
            }
            // Use Boot's own resolver here as well: external YAML/properties,
            // profiles and config imports must have the same meaning in recovery.
            org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(environment);
            ConfigurationSource.install(environment);return environment;
        } catch(Exception e) { throw new IllegalStateException("Startup configuration unavailable; restore conf and its key or correct deployment settings",e); }
    }
    private static void flatten(String prefix,Map<?,?> values,Map<String,Object> out) {
        values.forEach((k,v)->{
            String name=prefix.isEmpty()?k.toString():prefix+"."+k;
            if(v instanceof Map<?,?> nested) flatten(name,nested,out);
            else if(v!=null) out.put(name,v);
        });
    }
    public static boolean needsSetup(ConfigurableEnvironment environment) {
        if(environment.getProperty("clamav.configuration.recovery",Boolean.class,
                environment.getProperty("CLAMAV_CONFIGURATION_RECOVERY",Boolean.class,false))) return true;
        var store=ConfigurationSource.store(environment).orElseThrow();
        if(store.requiresDatabaseChoice(environment.getProperty("spring.datasource.url",ConfigurationCatalog.get("spring.datasource.url").defaultValue()))) return true;
        if(store.snapshot().setupComplete()) return false;
        if(ConfigurationSource.override(environment,ConfigurationCatalog.get("spring.datasource.url")).isPresent()
                || ConfigurationSource.override(environment,ConfigurationCatalog.get("clamav.service.host")).isPresent()) return false;
        if(Files.exists(Path.of("data/clamav-web-client.mv.db"))) return false;
        // Explicit Spring config files are resolved by Boot before its DataSource.
        if(environment.containsProperty("spring.config.location") || environment.containsProperty("spring.config.additional-location")) return false;
        for(String name:List.of("application.properties","application.yaml","application.yml","config/application.properties","config/application.yaml","config/application.yml"))
            if(Files.exists(Path.of(name))) return false;
        return true;
    }
    public static void run(Class<?> applicationClass,String[] arguments) throws Exception {
        ConfigurableEnvironment initial=environment(arguments);
        if(needsSetup(initial)) serve(initial,"First-time setup");
        while(true) {
            AtomicReference<ConfigurableEnvironment> prepared=new AtomicReference<>(environment(arguments));
            SpringApplication application=new SpringApplication(applicationClass);
            application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>)event->{
                prepared.set(event.getEnvironment());
                ConfigurationSource.store(event.getEnvironment()).ifPresent(store->store.noteExternalDatabase(event.getEnvironment().getProperty("spring.datasource.url","")));
            });
            try { application.run(arguments);return; }
            catch(Exception failure) {
                // Boot closes its failed context. Do not replace the selected SQL engine
                // with H2, recreate users, alter Flyway history or print credentials.
                System.err.println("Application initialization failed. Protected recovery is available; the selected database is retained.");
                serve(prepared.get(),"Startup recovery");
            }
        }
    }
    private static void serve(ConfigurableEnvironment environment,String mode) throws Exception {
        ConfigurationStore store=ConfigurationSource.store(environment).orElseGet(()->ConfigurationSource.install(environment));
        String prefix=environment.getProperty("server.servlet.context-path","");
        int port=environment.getProperty("server.port",Integer.class,8080);
        String bind=environment.getProperty("server.address","0.0.0.0");
        var proxyHeaders=new RecoveryProxyHeaders(environment);
        HttpServer server=HttpServer.create(new InetSocketAddress(bind,port),16);
        CountDownLatch start=new CountDownLatch(1);
        var workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"configuration-setup-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        try(ConfigurationSetupProtocol protocol=new ConfigurationSetupProtocol(new ConfigurationEngine(environment,store,Map::of),true)) {
            server.createContext(prefix+"/setup",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath().substring((prefix+"/setup").length());
                    if(path.isEmpty()) { exchange.getResponseHeaders().set("Location",prefix+"/setup/");exchange.sendResponseHeaders(302,-1);exchange.close();return; }
                    Map<String,String> headers=new HashMap<>();exchange.getRequestHeaders().forEach((k,v)->{if(!v.isEmpty())headers.put(k.toLowerCase(Locale.ROOT),String.join(",",v));});
                    boolean secure=exchange instanceof com.sun.net.httpserver.HttpsExchange
                            || proxyHeaders.isSecure(exchange.getRemoteAddress().getAddress(),headers);
                    var response=protocol.handle(new ConfigurationSetupProtocol.Request(exchange.getRequestMethod(),path,headers,
                            exchange.getRequestBody().readNBytes(65537),exchange.getRemoteAddress().getAddress().getHostAddress(),secure));
                    exchange.getResponseHeaders().set("Content-Type",response.contentType());exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("X-Frame-Options","DENY");
                    exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
                    if(response.status()==429) exchange.getResponseHeaders().set("Retry-After","60");
                    exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");
                    exchange.sendResponseHeaders(response.status(),response.body().length);exchange.getResponseBody().write(response.body());exchange.close();
                    if(response.start()) start.countDown();
                } catch(Exception e) { exchange.close(); }
            });
            server.createContext(prefix.isBlank()?"/":prefix+"/",exchange->{
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                if(exchange.getRequestURI().getPath().equals(prefix+"/")) {
                    exchange.getResponseHeaders().set("Location",prefix+"/setup/");exchange.sendResponseHeaders(302,-1);
                } else {
                    byte[] body="{\"status\":\"DOWN\",\"message\":\"Application is waiting for protected setup or startup recovery. Open /setup/.\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.setExecutor(workers);server.start();
            System.err.println(mode+" listening on port "+port+prefix+"/setup/. Read the recovery credential from "+store.directory().resolve("configuration-recovery-token")+". No database is opened by this listener.");
            start.await();
        } finally { server.stop(1);workers.shutdownNow(); }
    }
}
