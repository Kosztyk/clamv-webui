package info.trizub.clamav.webclient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.UUID;

@Service
public class QuarantineService {
    public static final String UPLOAD_FAILURE="Quarantine failed. Upload cleanup will attempt to remove the infected uploaded copy; inspect the server logs.";
    public static final String SOURCE_FAILURE="Quarantine failed for one or more infected source files. Upload cleanup does not delete source files; inspect the server logs.";
    public record Result(Path path,boolean failed) { }
    private static final Logger log=LoggerFactory.getLogger(QuarantineService.class);
    private final SettingsService settings;
    public QuarantineService(SettingsService settings) {this.settings=settings;}
    public Path quarantine(Path file) {return attempt(file,null).path();}

    /** Uploaded copies use the job ID; source scans use a fresh ID for each quarantined file. */
    public Result attempt(Path file,String jobId) {
        if(!settings.quarantineEnabled()) return new Result(null,false);
        Path moved=null;
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Only regular files can be quarantined");
            Path qdir=settings.quarantineDir();
            if(qdir.toAbsolutePath().normalize().startsWith(settings.uploadDir().toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Quarantine must be outside the upload directory");
            info.trizub.clamav.webclient.util.PrivateFiles.directory(qdir);
            String id=jobId!=null && jobId.matches("[0-9a-f]{32}")?jobId:UUID.randomUUID().toString().replace("-","");
            moved=Files.move(file,qdir.resolve(id+".bin"));
            info.trizub.clamav.webclient.util.PrivateFiles.restrict(moved,"rw-------");
            return new Result(moved,false);
        } catch(Exception ex) {
            log.warn("Quarantine failed for {} ({})",file,ex.getClass().getSimpleName());
            // Preserve evidence if the move succeeded but permission hardening failed afterward.
            return new Result(moved,true);
        }
    }
    public static boolean failureRecorded(String message) {return UPLOAD_FAILURE.equals(message) || SOURCE_FAILURE.equals(message);}
}
