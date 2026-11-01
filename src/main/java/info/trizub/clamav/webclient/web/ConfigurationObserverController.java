package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.metrics.ClamdMetricsCollector;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import info.trizub.clamav.webclient.service.IntegrationSettingsService;
import info.trizub.clamav.webclient.service.AuditService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.*;

/** Admin-only inspection of mounted configuration, effective policy and sampled STATS. */
@Controller
public class ConfigurationObserverController {
    private final ClamdConfigurationObserver observer;
    private final ClamdMetricsCollector metrics;
    private final EndpointService endpoints;
    private final SettingsService settings;
    private final long pollMillis;
    private final IntegrationSettingsService integrationSettings;
    private final AuditService audit;

    public ConfigurationObserverController(ClamdConfigurationObserver observer, ClamdMetricsCollector metrics,
                                           EndpointService endpoints, SettingsService settings,
                                           @Value("${clamav.config.poll-ms:30000}") long pollMillis,
                                           IntegrationSettingsService integrationSettings,AuditService audit) {
        this.observer=observer; this.metrics=metrics; this.endpoints=endpoints; this.settings=settings;
        this.pollMillis=Math.max(1000,pollMillis);
        this.integrationSettings=integrationSettings;this.audit=audit;
    }

    @GetMapping("/admin/configuration")
    public String index(Model model) {
        model.addAttribute("clamdConfig", observer.clamd());
        model.addAttribute("freshclamConfig", observer.freshclam());
        model.addAttribute("discoveryEnabled",observer.discoveryEnabled());
        model.addAttribute("activeEndpoints",endpoints.all());
        model.addAttribute("daemonMetrics",metrics.status());
        model.addAttribute("pollMillis",observer.pollMillis());
        model.addAttribute("observerEnabled",observer.observationEnabled());
        model.addAttribute("synchronizationRequested",observer.synchronizationRequested());
        model.addAttribute("observerManaged",integrationSettings.observerManaged());
        model.addAttribute("clamdPath",observer.clamdPath());model.addAttribute("freshclamPath",observer.freshclamPath());
        model.addAttribute("hasMount",observer.clamdPath()!=null && !observer.clamdPath().isBlank());
        model.addAttribute("connectionOverride",endpoints.hasConnectionOverride());
        model.addAttribute("policyProblem",observer.policyProblem());
        model.addAttribute("usingLastKnownConfiguration",observer.usingLastKnownConfiguration());
        var effective = observer.effective();
        Map<String,Object> policy = new LinkedHashMap<>();
        if (effective != null) {
            policy.put("StreamMaxLength",effective.streamMaxLength()==null ? "Not specified" : effective.streamMaxLength());
            policy.put("MaxScanSize",effective.maxScanSize()==null ? "Not specified" : effective.maxScanSize());
            policy.put("MaxFileSize",effective.maxFileSize()==null ? "Not specified" : effective.maxFileSize());
            policy.put("ExcludePath",effective.excludePaths());
            policy.put("Observed",effective.observedAt());
        }
        model.addAttribute("effectivePolicy",policy);
        Map<String,String> overrides=new LinkedHashMap<>();
        overrides.put("Default endpoint host (application seed)", settings.legacyHost());
        overrides.put("Default endpoint port (application seed)", String.valueOf(settings.legacyPort()));
        overrides.put("Upload path scan enabled",String.valueOf(settings.uploadPathScanEnabled()));
        overrides.put("Upload path fallback to INSTREAM",String.valueOf(settings.uploadPathScanFallbackToInstream()));
        model.addAttribute("appOverrides",overrides);
        return "pages/configuration";
    }
    @PostMapping("/admin/configuration/settings") @ResponseBody
    public org.springframework.http.ResponseEntity<?> retiredSettings(HttpServletRequest request) {
        return ConfigurationNavigation.moved(request,"observer");
    }

}
