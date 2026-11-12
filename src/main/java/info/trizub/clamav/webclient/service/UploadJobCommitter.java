package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

/** File copying and hashing have finished before this short metadata transaction begins. */
@Service
public class UploadJobCommitter {
    private final ScanJobRepository jobs;
    private final ScanExecutionService executor;
    public UploadJobCommitter(ScanJobRepository jobs,ScanExecutionService executor) {this.jobs=jobs;this.executor=executor;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<ScanJob> commit(List<ScanJob> prepared,UploadStorageService.Batch batch) {
        boolean transaction=TransactionSynchronizationManager.isActualTransactionActive();
        if(transaction) batch.bindToTransaction();
        jobs.saveAllAndFlush(prepared);
        if(transaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {for(ScanJob job:prepared) executor.enqueue(job.getId());}
            });
        } else {
            // Unmanaged embedding/tests still preserve copies if enqueue fails after the repository committed.
            batch.succeeded();for(ScanJob job:prepared) executor.enqueue(job.getId());
        }
        return prepared;
    }
}
