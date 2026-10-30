package info.trizub.clamav.webclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Reads mounted files without writing them or reloading ClamD. */
@Service
public class ClamdConfigurationObserver {
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_INCLUDES = 40;
    private static final int MAX_DEPTH = 6;
    private static final java.util.regex.Pattern SECRET_KEYS = java.util.regex.Pattern.compile(
            "(?i).*(password|passwd|secret|token|api.?key|credential|auth|proxyuser|proxypass).*" );
    private final String clamdPath, freshclamPath;
    private final boolean discoveryEnabled;
    private info.trizub.clamav.webclient.service.IntegrationSettingsService integrationSettings;
    private long bootstrapPollMillis=30000;
    private record Observation(View clamd, View freshclam, DaemonConfiguration effective, String problem) {}
    private volatile Observation observation = new Observation(View.disabled(), View.disabled(), null, "");

    public ClamdConfigurationObserver(
            @Value("${clamav.config.clamd-path:}") String clamdPath,
            @Value("${clamav.config.freshclam-path:}") String freshclamPath,
            @Value("${clamav.config.discovery-enabled:true}") boolean discoveryEnabled) {
        this.clamdPath=clamdPath; this.freshclamPath=freshclamPath;
        this.discoveryEnabled=discoveryEnabled;
        refresh();
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void liveSettings(info.trizub.clamav.webclient.service.IntegrationSettingsService settings,
            @Value("${clamav.config.poll-ms:30000}") long pollMillis) {
        this.integrationSettings=settings;this.bootstrapPollMillis=pollMillis;refresh();
    }
    public boolean observationEnabled() { return integrationSettings==null || integrationSettings.observationEnabled(); }
    public boolean synchronizationRequested() { return integrationSettings==null ? discoveryEnabled : integrationSettings.synchronizationEnabled(discoveryEnabled); }
    public long pollMillis() { return integrationSettings==null ? bootstrapPollMillis : integrationSettings.observerPollMillis(bootstrapPollMillis); }
    public String clamdPath() { return clamdPath; }
    public String freshclamPath() { return freshclamPath; }

    public record View(String status, String path, String polledAt,
                       Map<String,List<String>> directives, List<String> notices) {
        // Bean-style accessors avoid differences across Thymeleaf/SpringEL record resolvers.
        public String getStatus(){return status;}
        public String getPath(){return path;}
        public String getPolledAt(){return polledAt;}
        public Map<String,List<String>> getDirectives(){return directives;}
        public List<String> getNotices(){return notices;}
        static View disabled() { return new View("NOT_MOUNTED", "", "", Map.of(), List.of("No explicit configuration mount; observation disabled.")); }
    }

    public View clamd() {return observation.clamd();}
    public View freshclam() {return observation.freshclam();}
    public boolean discoveryEnabled(){return observationEnabled() && synchronizationRequested() && clamdPath != null && !clamdPath.isBlank();}
    public DaemonConfiguration effective() { return discoveryEnabled() ? observation.effective() : null; }
    public String policyProblem() { return observation.problem(); }
    public boolean usingLastKnownConfiguration() { return effective() != null && !observation.problem().isEmpty(); }

    /** Compatibility accessor for callers interested only in TCP discovery. */
    public Optional<TcpCandidate> tcpCandidate() {
        return endpointCandidate().filter(e -> !e.unixSocket()).map(e -> new TcpCandidate(e.host(),e.port()));
    }
    public Optional<DaemonConfiguration.Endpoint> endpointCandidate() {
        DaemonConfiguration config = effective();
        return config == null ? Optional.empty() : Optional.ofNullable(config.endpoint());
    }
    public record TcpCandidate(String host, int port) {}

    // EndpointService polls this observer and then synchronizes the managed default endpoint.
    public synchronized void refresh() {
        if (!observationEnabled()) {
            observation=new Observation(new View("PAUSED",clamdPath,"",Map.of(),List.of("Observation is paused in the Web UI.")),
                new View("PAUSED",freshclamPath,"",Map.of(),List.of("Observation is paused in the Web UI.")),null,"");return;
        }
        View clamd = parse(clamdPath);
        View freshclam = parse(freshclamPath);
        DaemonConfiguration effective = observation.effective();
        String problem = "";
        if ("NOT_MOUNTED".equals(clamd.status())) effective = null;
        else if ("LOADED".equals(clamd.status())) {
            try { effective = DaemonConfiguration.from(clamd); }
            catch (IllegalArgumentException ex) { problem = ex.getMessage(); }
        } else problem = "Daemon configuration is unavailable or incomplete: " + clamd.status();
        observation = new Observation(clamd,freshclam,effective,problem);
    }

    public static View parse(String path) {
        if (path == null || path.isBlank()) return View.disabled();
        Path file;
        try { file = Path.of(path).toAbsolutePath().normalize(); }
        catch (Exception ex) { return new View("INVALID_PATH", "", Instant.now().toString(), Map.of(), List.of("Invalid configured file path")); }
        Map<String,List<String>> values = new LinkedHashMap<>();
        List<String> notices = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        try {
            Path directory = file.getParent().toRealPath();
            readFile(file, directory, values, notices, visited, 0);
            Map<String,List<String>> safe = new LinkedHashMap<>();
            for (var e : values.entrySet()) safe.put(e.getKey(),List.copyOf(e.getValue()));
            return new View(notices.isEmpty() ? "LOADED" : "INCOMPLETE",file.toString(),Instant.now().toString(), Collections.unmodifiableMap(safe),List.copyOf(notices));
        } catch (AccessDeniedException ex) {
            return new View("PERMISSION_DENIED",file.toString(), Instant.now().toString(),Map.of(), List.of("Read permission denied"));
        } catch (NoSuchFileException ex) {
            return new View("NOT_FOUND",file.toString(),Instant.now().toString(),Map.of(), List.of("Mounted config file not found"));
        } catch (Exception ex) {
            return new View("READ_ERROR",file.toString(),Instant.now().toString(),Map.of(),List.of("Could not safely read config: " + ex.getClass().getSimpleName()));
        }
    }

    private static void readFile(Path file, Path root, Map<String,List<String>> values, List<String> notices,
                                 Set<Path> visited, int depth) throws IOException {
        if (depth > MAX_DEPTH || visited.size() >= MAX_INCLUDES) { notices.add("Include limit reached"); return; }
        Path real = file.toRealPath();
        if (!real.startsWith(root)) {notices.add("Include outside mounted config directory rejected"); return;}
        if (!visited.add(real)) {notices.add("Include cycle/repetition skipped"); return;}
        if (Files.size(real) > MAX_FILE_BYTES) {notices.add("Config too large; skipped");return;}
        List<String> lines=Files.readAllLines(real,StandardCharsets.UTF_8);
        for (String raw:lines) {
            List<String> tokens = tokenize(raw);
            if (tokens.isEmpty()) continue;
            String key=tokens.get(0);
            if (!key.matches("[A-Za-z][A-Za-z0-9_]*")) continue;
            String value=String.join(" ",tokens.subList(1,tokens.size()));
            if (key.equalsIgnoreCase("Include")) {
                if (value.isBlank()) {notices.add("Empty Include skipped");continue;}
                Path include = Path.of(value);
                if (!include.isAbsolute()) include=real.getParent().resolve(include);
                include=include.normalize();
                Path parent=include.getParent();
                if (parent==null || !parent.toAbsolutePath().normalize().startsWith(root)) {
                    notices.add("Include outside mounted directory rejected");continue;
                }
                String glob=include.getFileName().toString();
                if (glob.contains("*")||glob.contains("?")||glob.contains("[")) {
                    try (DirectoryStream<Path> stream=Files.newDirectoryStream(parent,glob)) {
                        List<Path> sorted=new ArrayList<>();
                        stream.forEach(sorted::add); sorted.sort(Comparator.naturalOrder());
                        for (Path child:sorted) readFile(child,root,values,notices,visited,depth+1);
                    } catch (NoSuchFileException ex) {notices.add("Included directory not found");}
                } else {
                    try {readFile(include,root,values,notices,visited,depth+1);}
                    catch (NoSuchFileException ex) {notices.add("Included file not found");}
                }
                continue;
            }
            if (SECRET_KEYS.matcher(key).matches() || value.toLowerCase(Locale.ROOT).matches(".*(://[^/ ]+:[^/@ ]+@).*")) value="[REDACTED]";
            // No arbitrary limit on directives accepted; cap per key for predictable rendering.
            List<String> list=values.computeIfAbsent(key,k->new ArrayList<>());
            if (value.length()>1024) {notices.add("Directive value too long: "+key);continue;}
            if (list.size()<100) list.add(value);
            else if (list.size()==100) notices.add("Repeated directive limit reached: "+key);
        }
    }

    /** Shell-free ClamAV directive tokenizer: comments, quoted values and escapes. */
    public static List<String> tokenize(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder word=new StringBuilder();
        char quote=0; boolean escaped=false; boolean started=false;
        for (int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if (escaped) { word.append(c); escaped=false; started=true; continue; }
            if (c=='\\' && quote!= '\'' && i+1<line.length()) {
                char next=line.charAt(i+1);
                // Keep regex escapes such as \\. and \\d intact.
                if (next=='\\' || next=='"' || next=='\'' || (quote==0 && (next=='#' || Character.isWhitespace(next)))) {
                    escaped=true; started=true; continue;
                }
            }
            if (quote!=0) { if (c==quote) quote=0; else word.append(c); started=true; continue; }
            if (c=='\'' || c=='"') { quote=c; started=true; continue; }
            if (c=='#') break;
            if (Character.isWhitespace(c)) { if(started){result.add(word.toString());word.setLength(0);started=false;} continue; }
            word.append(c);started=true;
        }
        if (escaped) word.append('\\');
        if (quote!=0) throw new IllegalArgumentException("Unterminated configuration quote");
        if (started) result.add(word.toString());
        return result;
    }
}
