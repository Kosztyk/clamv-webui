package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import xyz.capybara.clamav.Platform;

@Entity
@Table(name = "clamd_endpoints")
public class ClamdEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port = 3310;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Platform platform = Platform.UNIX;

    @Column(nullable = false)
    private boolean enabled = true;

    public ClamdEndpoint() {}

    public ClamdEndpoint(String name, String host, int port, Platform platform) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.platform = platform;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
