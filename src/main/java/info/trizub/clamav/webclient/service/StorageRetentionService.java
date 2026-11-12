package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class StorageRetentionService {
    private static final Logger log=LoggerFactory.getLogger(StorageRetentionService.class);
    private final SettingsService settings;
    private final UploadStorageService uploads;
    private final ScanJobRepository jobs;
    private final AuditEventRepository audits;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public StorageRetentionService(SettingsService settings,UploadStorageService uploads,ScanJobRepository jobs,AuditEventRepository audits) {
        this(settings,uploads,jobs,audits,Clock.systemUTC());
    }
    StorageRetentionService(SettingsService settings,UploadStorageService uploads,ScanJobRepository jobs,AuditEventRepository audits,Clock clock) {
        this.settings=settings;this.uploads=uploads;this.jobs=jobs;this.audits=audits;this.clock=clock;
    }
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    public synchronized void clean() {
        try {uploads.pruneExpired();}catch(Exception ex){log.warn("Upload retention deferred ({})",ex.getClass().getSimpleName());}
        try {pruneJobs();}catch(Exception ex){log.warn("Job history retention deferred ({})",ex.getClass().getSimpleName());}
        try {pruneAudits();}catch(Exception ex){log.warn("Audit history retention deferred ({})",ex.getClass().getSimpleName());}
    }
    private void pruneJobs() {
        if(settings.jobRetentionDays()==0) return;
        Instant cutoff=clock.instant().minus(Duration.ofDays(settings.jobRetentionDays()));String after="";long removed=0;
        while(!Thread.currentThread().isInterrupted()) {
            var batch=jobs.findTop500ByStatusAndFinishedAtBeforeAndIdGreaterThanOrderByIdAsc(ScanJobStatus.FINISHED,cutoff,after);
            if(batch.isEmpty()) break;
            List<String> ids=batch.stream().filter(uploads::artifactsAbsent).map(ScanJob::getId).toList();
            if(!ids.isEmpty()){jobs.deleteAllByIdInBatch(ids);removed+=ids.size();}
            after=batch.get(batch.size()-1).getId();
        }
        if(removed>0)log.info("Retention pruned {} completed job records",removed);
    }
    private void pruneAudits() {
        if(settings.auditRetentionDays()==0) return;
        Instant cutoff=clock.instant().minus(Duration.ofDays(settings.auditRetentionDays()));long after=0,removed=0;
        while(!Thread.currentThread().isInterrupted()) {
            var batch=audits.findTop500ByAtBeforeAndIdGreaterThanOrderByIdAsc(cutoff,after);
            if(batch.isEmpty())break;
            audits.deleteAllByIdInBatch(batch.stream().map(AuditEvent::getId).toList());removed+=batch.size();
            after=batch.get(batch.size()-1).getId();
        }
        if(removed>0)log.info("Retention pruned {} audit records",removed);
    }
}
