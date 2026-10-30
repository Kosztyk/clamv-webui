package info.trizub.clamav.webclient.config;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Validated settings from one complete, immutable clamd.conf observation. */
public record DaemonConfiguration(Endpoint endpoint, Long streamMaxLength, Long maxScanSize,
                                  Long maxFileSize, List<String> excludePaths,
                                  List<Pattern> exclusions, String observedAt) {
    public record Endpoint(String host, int port) {
        public boolean unixSocket() { return host.startsWith("/"); }
    }

    static DaemonConfiguration from(ClamdConfigurationObserver.View view) {
        Map<String,List<String>> values = view.directives();
        String socket = single(values, "LocalSocket");
        String port = single(values, "TCPSocket");
        Endpoint tcp = null;
        if (port != null) {
            int number;
            try { number = Integer.parseInt(port); }
            catch (NumberFormatException ex) { throw invalid("TCPSocket"); }
            if (number < 1 || number > 65535) throw invalid("TCPSocket");
            List<String> addresses = values(values, "TCPAddr");
            String address = addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
            if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1,address.length()-1);
            if (address.equals("0.0.0.0")) address = "127.0.0.1";
            if (address.equals("::")) address = "::1";
            if (!address.matches("[a-zA-Z0-9_.:%-]{1,255}")) throw invalid("TCPAddr");
            tcp = new Endpoint(address, number);
        }
        Endpoint endpoint = tcp;
        if (socket != null) {
            if (socket.length() > 255 || socket.chars().anyMatch(c -> c < 32) || !Path.of(socket).isAbsolute())
                throw invalid("LocalSocket");
            endpoint = new Endpoint(Path.of(socket).normalize().toString(), 0);
        }
        List<String> raw = List.copyOf(values(values,"ExcludePath"));
        List<Pattern> patterns = new ArrayList<>();
        for (String expression : raw) {
            if (expression.isBlank()) throw invalid("ExcludePath");
            // Translate standard POSIX character classes to their Java equivalents.
            String translated = expression;
            for (String name : List.of("alnum","alpha","blank","cntrl","digit","graph","lower","print","punct","space","upper","xdigit")) {
                String javaName = name.equals("xdigit") ? "XDigit" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                translated = translated.replace("[:" + name + ":]", "\\p{" + javaName + "}");
            }
            try { patterns.add(Pattern.compile(translated)); }
            catch (IllegalArgumentException ex) { throw invalid("ExcludePath regular expression"); }
        }
        return new DaemonConfiguration(endpoint, size(values,"StreamMaxLength"), size(values,"MaxScanSize"),
                size(values,"MaxFileSize"), raw, List.copyOf(patterns), view.polledAt());
    }

    private static Long size(Map<String,List<String>> values, String key) {
        String value = single(values,key);
        if (value == null) return null;
        var match = Pattern.compile("([0-9]+)\\s*([KMG]?)(?:i?B)?", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!match.matches()) throw invalid(key);
        try {
            long amount = Long.parseLong(match.group(1));
            int power = switch (match.group(2).toUpperCase(Locale.ROOT)) { case "K" -> 1; case "M" -> 2; case "G" -> 3; default -> 0; };
            for (int i=0;i<power;i++) amount = Math.multiplyExact(amount,1024);
            return amount;
        } catch (ArithmeticException | NumberFormatException ex) { throw invalid(key); }
    }

    private static String single(Map<String,List<String>> values, String key) {
        List<String> found = values(values,key);
        if (found.size() > 1) throw invalid("duplicate " + key);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<String> values(Map<String,List<String>> values, String key) {
        return values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key))
                .flatMap(e -> e.getValue().stream()).toList();
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("Invalid daemon configuration: " + name);
    }
}
