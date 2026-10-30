package info.trizub.clamav.webclient.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/** Commands used by the application, with TCP and Unix-domain socket transports. */
public interface ClamdClient {
    void ping() throws IOException;
    String version() throws IOException;
    String stats() throws IOException;
    ScanResult scan(InputStream input) throws IOException;
    ScanResult parallelScan(Path path) throws IOException;
}
