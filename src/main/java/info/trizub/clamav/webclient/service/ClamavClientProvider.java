package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.ClamavClient;

@Service
public class ClamavClientProvider {

    public ClamdClient clientFor(ClamdEndpoint ep) {
        if (ep.getHost().startsWith("/")) return new UnixClamdClient(ep.getHost());
        ClamavClient client = new ClamavClient(ep.getHost(), ep.getPort(), ep.getPlatform());
        return new ClamdClient() {
            public void ping() { client.ping(); }
            public String version() { return client.version(); }
            public String stats() { return client.stats(); }
            public xyz.capybara.clamav.commands.scan.result.ScanResult scan(java.io.InputStream input) { return client.scan(input); }
            public xyz.capybara.clamav.commands.scan.result.ScanResult parallelScan(java.nio.file.Path path) { return client.parallelScan(path); }
        };
    }
}
