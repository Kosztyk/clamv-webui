package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;

@Entity
@Table(name = "watched_directories")
public class WatchedDirectory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String path;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id")
    private ClamdEndpoint endpoint;

    @Column(nullable = false)
    private boolean enabled = true;

    public WatchedDirectory() {}

    public WatchedDirectory(String path, ClamdEndpoint endpoint) {
        this.path = path;
        this.endpoint = endpoint;
    }

    public Long getId() { return id; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
