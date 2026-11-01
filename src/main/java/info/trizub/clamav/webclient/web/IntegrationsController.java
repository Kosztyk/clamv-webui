package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.service.*;
import jakarta.servlet.http.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class IntegrationsController {
    private final IntegrationSettingsService settings;
    private final AuditService audit;
    private final Environment env;
    public IntegrationsController(IntegrationSettingsService settings,AuditService audit,Environment env) {
        this.settings=settings;this.audit=audit;this.env=env;
    }
    @GetMapping("/admin/integrations") public String index(Model model,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var gate=settings.scrapeGate();
        model.addAttribute("metricsEnabled",gate.enabled());model.addAttribute("metricsConfigured",!gate.hash().isBlank());
        model.addAttribute("metricsManaged",settings.metricsManaged());
        model.addAttribute("managementPort",env.getProperty("management.server.port",env.getProperty("server.port","8080")));
        model.addAttribute("metricsPollSeconds",Math.max(1,env.getProperty("clamav.metrics.clamd-poll-ms",Long.class,30000L)/1000));
        return "pages/integrations";
    }
    @PostMapping("/admin/integrations/prometheus") @ResponseBody
    public org.springframework.http.ResponseEntity<?> retiredSettings(HttpServletRequest request) {
        return ConfigurationNavigation.moved(request,"metrics");
    }
    @GetMapping("/admin/integrations/files/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        String path=switch(name) {
            case "testing-guide" -> "admin/home-lab-testing.md";
            case "grafana-dashboard" -> "monitoring/grafana-clamav-webui.json";
            case "alert-rules" -> "monitoring/clamav-alerts.yml";
            default -> throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        var resource=new ClassPathResource(path);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+resource.getFilename()+"\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
    }
}
