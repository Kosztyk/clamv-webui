package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PrivateFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only application-generated upload files in the configured upload directory are eligible for deletion. */
@Service
public class UploadStorageService {
    private static final Logger log=LoggerFactory.getLogger(UploadStorageService.class);
    private static final Pattern NAME=Pattern.compile("^([0-9a-f]{32})-.*$");
    private final SettingsService settings;
    private final ScanJobRepository jobs;
    private final Clock clock;
    private final Set<Path> inFlight=new HashSet<>();

    @org.springframework.beans.factory.annotation.Autowired
    public UploadStorageService(SettingsService settings,ScanJobRepository jobs) {this(settings,jobs,Clock.systemUTC());}
    UploadStorageService(SettingsService settings,ScanJobRepository jobs,Clock clock) {this.settings=settings;this.jobs=jobs;this.clock=clock;}

    public Batch beginBatch() {return new Batch();}
    public final class Batch implements AutoCloseable {
        private final List<Path> paths=new ArrayList<>();
        private boolean transactional, completed, durable, success;
        private Batch() {
            if(TransactionSynchronizationManager.isActualTransactionActive()) bindToTransaction();
        }
        public void bindToTransaction() {
            if(transactional || !TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Upload batch requires one active metadata transaction");
            transactional=true;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {durable=true;}
                @Override public void afterCompletion(int status) {
                    completed=true;
                    if(status==STATUS_ROLLED_BACK) rollback();
                    else {
                        durable=true;
                        if(status!=STATUS_COMMITTED) log.warn("Upload transaction outcome is unknown; files retained for database-aware retention");
                    }
                    release();
                }
            });
        }
        public Path create(String id,String name) throws IOException {
            Path root=PrivateFiles.directory(settings.uploadDir().toAbsolutePath().normalize());
            Path file=root.resolve(id+"-"+name);
            synchronized(inFlight) {if(!inFlight.add(file))throw new FileAlreadyExistsException(file.toString());}
            try {PrivateFiles.create(file);paths.add(file);return file;}
            catch(IOException|RuntimeException ex) {synchronized(inFlight) {inFlight.remove(file);}throw ex;}
        }
        public void succeeded() {success=true;}
        private void release() {synchronized(inFlight) {inFlight.removeAll(paths);}}
        private void rollback() {for(Path path:paths) delete(path);}
        @Override public void close() {
            if(!success && !durable && (!transactional || completed)) rollback();
            if(!transactional || completed) release();
        }
    }

    /** Called after the scan's streams are closed and its terminal result has been persisted. */
    public void cleanupFinished(String id) {
        try {
            ScanJob job=jobs.findById(id).orElse(null);
            if(job==null || job.getType()!=ScanJobType.UPLOAD || job.getStatus()!=ScanJobStatus.FINISHED) return;
            if(job.getVerdict()!=ScanVerdict.VIRUS_FOUND && (job.getVerdict()!=ScanVerdict.OK || !settings.deleteCleanUploads())) return;
            Path file=owned(job.getStoredPath(),job.getId());
            if(file!=null) delete(file);
        } catch(Exception ex) {log.warn("Upload cleanup deferred for job {} ({})",id,ex.getClass().getSimpleName());}
    }

    /** Safe to call for history retention: unknown or still-present artifacts keep their job row. */
    public boolean artifactsAbsent(ScanJob job) {
        if(job.getQuarantinePath()!=null && !job.getQuarantinePath().isBlank()) return false;
        if(job.getStoredPath()==null || job.getStoredPath().isBlank()) return true;
        try {return Files.notExists(Path.of(job.getStoredPath()),LinkOption.NOFOLLOW_LINKS);}
        catch(RuntimeException ex) {return false;}
    }

    public void pruneExpired() {
        Path root=settings.uploadDir().toAbsolutePath().normalize();
        Instant cutoff=clock.instant().minus(Duration.ofHours(settings.uploadRetentionHours()));
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return;
        long removed=0;
        try {PrivateFiles.directory(root);}catch(IOException ex){log.warn("Upload retention cannot secure {}",root);return;}
        try(DirectoryStream<Path> files=Files.newDirectoryStream(root)) {
            for(Path file:files) {
                var match=NAME.matcher(file.getFileName().toString());
                if(!match.matches()) continue;
                synchronized(inFlight) {if(inFlight.contains(file)) continue;}
                try {
                    BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!attrs.isRegularFile()) continue;
                    try {PrivateFiles.restrict(file,"rw-------");}
                    catch(IOException ex) {log.warn("Cannot tighten legacy upload permissions for {}; eligible expired files will still be removed",file);}
                    if(!attrs.lastModifiedTime().toInstant().isBefore(cutoff)) continue;
                    // The UUID prefix is the primary key: no full job-table scan or per-file path LIKE query.
                    ScanJob job=jobs.findById(match.group(1)).orElse(null);
                    if(job!=null) {
                        if(job.getType()!=ScanJobType.UPLOAD || job.getStatus()!=ScanJobStatus.FINISHED) continue;
                        if(owned(job.getStoredPath(),job.getId())==null || !file.equals(Path.of(job.getStoredPath()).toAbsolutePath().normalize())) continue;
                        if(job.getQuarantinePath()!=null && !job.getQuarantinePath().isBlank()
                            && file.equals(Path.of(job.getQuarantinePath()).toAbsolutePath().normalize())) continue;
                        if(job.getFinishedAt()!=null && !job.getFinishedAt().isBefore(cutoff)) continue;
                    }
                    synchronized(inFlight) {if(!inFlight.contains(file) && delete(file)) removed++;}
                } catch(NoSuchFileException ignored) {
                    // Completion/rollback may have removed the same file since enumeration.
                } catch(Exception ex) {log.warn("Upload retention deferred for {} ({})",file,ex.getClass().getSimpleName());}
            }
            if(removed>0) log.info("Upload retention removed {} expired files",removed);
        } catch(IOException ex) {log.warn("Upload retention cannot inspect {} ({})",root,ex.getClass().getSimpleName());}
    }

    private Path owned(String raw,String id) throws IOException {
        if(raw==null || raw.isBlank() || id==null) return null;
        Path file=Path.of(raw).toAbsolutePath().normalize(),root=settings.uploadDir().toAbsolutePath().normalize();
        if(!Objects.equals(file.getParent(),root) || !NAME.matcher(file.getFileName().toString()).matches()
                || !file.getFileName().toString().startsWith(id+"-")) {
            log.warn("Upload cleanup refused a path outside managed storage for job {}",id);return null;
        }
        if(Files.isSymbolicLink(root) || Files.isSymbolicLink(file)) return null;
        if(Files.exists(root) && !file.getParent().toRealPath().equals(root.toRealPath())) return null;
        return file;
    }
    private boolean delete(Path file) {
        try {
            if(Files.isSymbolicLink(file) || Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS)) return false;
            return Files.deleteIfExists(file);
        } catch(IOException ex) {log.warn("Could not delete managed upload {} ({}); retention will retry",file,ex.getClass().getSimpleName());return false;}
    }
}
