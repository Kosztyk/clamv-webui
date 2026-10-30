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
    @PostMapping("/admin/integrations/prometheus")
    public String save(@RequestParam(defaultValue="false") boolean enabled,@RequestParam(defaultValue="") String token,
            @RequestParam(defaultValue="save") String action,Authentication auth,HttpServletRequest request,RedirectAttributes redirect) {
        try {
            switch(action) {
                case "generate" -> redirect.addFlashAttribute("newScrapeToken",settings.generateMetricsToken());
                case "reset" -> settings.resetMetrics();
                case "save" -> settings.saveMetrics(enabled,token);
                default -> throw new IllegalArgumentException("Unknown settings action.");
            }
            audit.record(auth,request,"MONITORING_SETTINGS_UPDATE","action="+action,"SUCCESS",null);
            redirect.addFlashAttribute("successMsg",action.equals("generate") ? "Prometheus enabled. Copy the new scrape token now; it will not be shown again." : "Monitoring settings saved. Changes are active now.");
        } catch (IllegalArgumentException|IllegalStateException ex) { redirect.addFlashAttribute("errorMsg",ex.getMessage()); }
        return "redirect:/admin/integrations";
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
