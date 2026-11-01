package info.trizub.clamav.webclient.configuration;

import info.trizub.clamav.webclient.identity.AuthenticationProviderProber;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Small bounded test pool; cancelled OS/DNS/JDBC calls cannot exhaust request or scan workers. */
@Component
public final class ConfigurationConnectionTester implements AutoCloseable {
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"configuration-test");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public Map<String,Object> test(String section,Map<String,String> values) {
        Future<String> task;
        long deadline="database".equals(section)?databaseConnectMillis(values)+5000:7000;
        try { task=executor.submit(()->probe(section,values)); }
        catch(RejectedExecutionException e) { throw new IllegalArgumentException("Connection tests are busy; try again shortly"); }
        try { return Map.of("ok",true,"status","succeeded","message",task.get(deadline,TimeUnit.MILLISECONDS)); }
        catch(TimeoutException e) { task.cancel(true);throw new IllegalArgumentException("Connection test exceeded its "+(deadline/1000)+"-second deadline. No settings were saved."); }
        catch(InterruptedException e) { task.cancel(true);Thread.currentThread().interrupt();throw new IllegalArgumentException("Connection test interrupted"); }
        catch(ExecutionException e) {
            if("database".equals(section)) {
                Throwable failure=e.getCause();
                if(values.getOrDefault("spring.datasource.url","").startsWith("jdbc:h2:") && sqlFailure(failure,"90146"))
                    return Map.of("ok",false,"status","not_initialized","message",
                        "The H2 database has not been created yet. Connection and credentials are not verified. For a new database, review and save a JDBC URL that allows creation, then select Start application; test again afterward. This test creates no database files and saves no settings.");
                throw new IllegalArgumentException(databaseFailure(failure));
            }
            throw new IllegalArgumentException("Connection test failed. Check the address, credentials, certificate trust and network. No settings were saved.");
        }
    }
    static long databaseConnectMillis(Map<String,String> values) {
        long configured=Long.parseLong(values.getOrDefault("spring.datasource.hikari.connection-timeout","10000"));
        return Math.max(1000,Math.min(30000,configured));
    }
    static Properties databaseOptions(String url,Map<String,String> values) {
        Properties options=new Properties();
        options.setProperty("user",values.get("spring.datasource.username"));options.setProperty("password",values.get("spring.datasource.password"));
        long millis=databaseConnectMillis(values);
        boolean postgres=url.startsWith("jdbc:postgresql:");
        // PostgreSQL uses seconds; MySQL and MariaDB use milliseconds.
        String timeout=Long.toString(postgres?(millis+999)/1000:millis);
        options.setProperty("connectTimeout",timeout);options.setProperty("socketTimeout",timeout);
        if(postgres) options.setProperty("loginTimeout",timeout);
        return options;
    }
    private static boolean sqlFailure(Throwable error,String state) {
        for(int depth=0;error!=null && depth<12;depth++,error=error.getCause())
            if(error instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }
    static String databaseFailure(Throwable error) {
        // JDBC messages can contain hostnames, URLs and credentials. Use only
        // exception types and standardized SQL states, never their raw text.
        for(int depth=0;error!=null && depth<12;depth++,error=error.getCause()) {
            if(error instanceof ClassNotFoundException || error instanceof LinkageError)
                return "The selected database driver is unavailable. Use the complete application image and a driver matching the JDBC URL. No settings were saved.";
            if(error instanceof javax.net.ssl.SSLException)
                return "Database TLS verification failed. Check the server certificate, truststore and JDBC TLS options. No settings were saved.";
            if(error instanceof SocketTimeoutException || error instanceof SQLTimeoutException)
                return "Database connection or validation timed out. Check database reachability and server load. No settings were saved.";
            if(error instanceof UnknownHostException)
                return "Database hostname could not be resolved from the application container. Check its DNS and network. No settings were saved.";
            if(error instanceof ConnectException)
                return "The database server could not be reached. Check its host, port, network routing and firewall. No settings were saved.";
            if(error instanceof SQLException sql) {
                String state=Objects.toString(sql.getSQLState(),"");
                if(state.startsWith("28")) return "Database authentication failed. Check the username, password and server authentication rules. No settings were saved.";
                if(state.equals("3D000") || state.equals("42000") && sql.getErrorCode()==1049)
                    return "The selected database does not exist. Create it on the database server before testing. No settings were saved.";
                if(state.equals("42501") || state.equals("42000") && Set.of(1044,1142).contains(sql.getErrorCode()))
                    return "The database user lacks permission to connect or run the validation query. Check server grants. No settings were saved.";
                if(state.equals("57014")) return "The database validation query was cancelled or exceeded its timeout. Check server load. No settings were saved.";
                if(state.startsWith("90") && !state.equals("90146"))
                    return "The H2 database could not be opened. Check its file permissions, storage, options and whether another process holds it. No settings were saved.";
            }
        }
        return "Database connection test failed. Check database reachability, JDBC options and server logs. No settings were saved.";
    }
    private String probe(String section,Map<String,String> values) throws Exception {
        return switch(section) {
            case "clamd" -> {
                String host=values.get("clamav.service.host");int port=Integer.parseInt(values.get("clamav.service.port"));
                byte[] pong=new byte[64];int count;
                if(host.startsWith("/")) {
                    try(SocketChannel socket=SocketChannel.open(StandardProtocolFamily.UNIX)) {
                        socket.connect(UnixDomainSocketAddress.of(host));socket.write(ByteBuffer.wrap("zPING\0".getBytes(StandardCharsets.US_ASCII)));
                        ByteBuffer buffer=ByteBuffer.wrap(pong);count=socket.read(buffer);
                    }
                } else {
                    try(Socket socket=new Socket()) {
                        socket.connect(new InetSocketAddress(host,port),3000);socket.setSoTimeout(3000);
                        socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));count=socket.getInputStream().read(pong);
                    }
                }
                if(count<4 || !new String(pong,0,count,StandardCharsets.US_ASCII).startsWith("PONG")) throw new IllegalArgumentException("Invalid daemon reply");
                yield "ClamAV replied PONG. Settings have not been saved.";
            }
            case "database" -> {
                String url=values.get("spring.datasource.url");ConfigurationCatalog.checkJdbc(url);
                String expected=ConfigurationCatalog.jdbcDriver(url);
                String selected=values.getOrDefault("spring.datasource.driver-class-name","");
                if(!selected.isBlank() && !expected.equals(selected)) throw new IllegalArgumentException("Database driver mismatch");
                Class.forName(expected,true,ConfigurationConnectionTester.class.getClassLoader());
                Properties options=databaseOptions(url,values);
                // Tests do not create a new H2 file or run application migrations.
                if(url.startsWith("jdbc:h2:")) url=h2ExistingDatabaseUrl(url);
                try(Connection connection=DriverManager.getConnection(url,options);Statement query=connection.createStatement()) {
                    query.setQueryTimeout(2);query.execute("SELECT 1");
                }
                yield "Database connection and SELECT 1 succeeded. No schema migration or data copy was performed.";
            }
            case "rest" -> {
                String base=values.get("CLAMAV_REST_URL").replaceAll("/+$","");
                ConfigurationCatalog.validate(ConfigurationCatalog.get("CLAMAV_REST_URL"),base);
                String path=values.get("CLAMAV_REST_STATS_PATH");
                if(!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("?") || path.contains("#")) throw new IllegalArgumentException("Invalid REST statistics path");
                HttpURLConnection connection=(HttpURLConnection)URI.create(base+path).toURL().openConnection();
                connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
                String secret=values.getOrDefault("CLAMAV_REST_API_KEY","");
                if(!secret.isBlank()) connection.setRequestProperty("bearer".equals(values.get("CLAMAV_REST_AUTH_MODE"))?"Authorization":"X-API-Key","bearer".equals(values.get("CLAMAV_REST_AUTH_MODE"))?"Bearer "+secret:secret);
                try { int code=connection.getResponseCode();if(code<200 || code>=300) throw new IllegalArgumentException("REST request failed"); }
                finally { connection.disconnect(); }
                yield "REST statistics endpoint returned success. Settings have not been saved.";
            }
            case "ldap","oidc" -> {
                String key=section.equals("ldap")?"clamav.identity.ldap.manager-password":"clamav.identity.oidc.client-secret";
                new AuthenticationProviderProber().test(section,ConfigurationCatalog.provider(section,values),values.getOrDefault(key,""));
                yield section.equals("ldap")?"Verified LDAP transport and manager bind succeeded. Settings have not been saved.":"HTTPS issuer discovery succeeded. Client-secret validity is confirmed during real sign-in.";
            }
            default -> throw new IllegalArgumentException("This section does not have a connection test");
        };
    }
    private static String h2ExistingDatabaseUrl(String url) {
        StringJoiner selected=new StringJoiner(";");
        for(String option:url.split(";")) if(!option.regionMatches(true,0,"IFEXISTS=",0,9)) selected.add(option);
        return selected.add("IFEXISTS=TRUE").toString();
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
