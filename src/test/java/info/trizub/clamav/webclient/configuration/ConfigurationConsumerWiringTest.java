package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import info.trizub.clamav.webclient.api.v1.ApiV1Controller;
import info.trizub.clamav.webclient.config.*;
import info.trizub.clamav.webclient.identity.IdentityProperties;
import info.trizub.clamav.webclient.metrics.*;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import info.trizub.clamav.webclient.security.*;
import info.trizub.clamav.webclient.service.*;
import info.trizub.clamav.webclient.web.UiModelAdvice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Checks saved GUI values at actual framework binders and service boundaries. */
class ConfigurationConsumerWiringTest {
    @TempDir Path folder;
    ConfigurationStore open() {return new ConfigurationStore(folder.resolve("center.properties"),folder.resolve("center.key"));}
    MockEnvironment saved(Map<String,String> values) {
        var store=open();store.save(store.snapshot().revision(),values,Set.of(),true,"consumer-fixture");
        var env=new MockEnvironment();env.getPropertySources().addFirst(new ConfigurationSource(open()));return env;
    }
    <T> T bind(MockEnvironment env,String prefix,Class<T> type) {return Binder.get(env).bind(prefix,Bindable.of(type)).get();}
    AnnotationConfigApplicationContext context(MockEnvironment env,Class<?> component,Class<?>... mocks) {
        var context=new AnnotationConfigApplicationContext();context.setEnvironment(env);
        for(var type:mocks) context.getBeanFactory().registerSingleton("fixture-"+type.getSimpleName(),mock(type));
        context.register(component);context.refresh();return context;
    }
    SettingsService settings(MockEnvironment env) {
        var settings=new SettingsService();ReflectionTestUtils.setField(settings,"props",new Properties());
        ReflectionTestUtils.setField(settings,"environment",env);return settings;
    }
    @Test void savedRestValuesControlEveryOutboundPathAuthenticationAndMultipartField() throws Exception {
        var seen=new ArrayList<String>();var bodies=new ArrayList<String>();var modes=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestURI().toString());modes.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        try {
            var values=new LinkedHashMap<String,String>();values.put("clamav.rest.enabled","true");
            values.put("CLAMAV_REST_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/base");
            values.put("CLAMAV_REST_API_KEY","synthetic-rest-secret");values.put("CLAMAV_REST_AUTH_MODE","bearer");
            values.put("CLAMAV_REST_FORM_KEY","DOCUMENTS");values.put("CLAMAV_REST_WEBHOOK_FIELD","callbackId");
            values.put("CLAMAV_REST_TIMEOUT_SECONDS","2");values.put("CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS","3");values.put("CLAMAV_REST_REPORT_MAX_JOBS","7");
            for(String operation:List.of("STATS","JOBS","HISTORY","WEBHOOKS","SCAN","SCAN_HISTORY")) values.put("CLAMAV_REST_"+operation+"_PATH","/custom/"+operation.toLowerCase(Locale.ROOT));
            var env=saved(values);
            try(var context=context(env,RestApiBridge.class,ObjectMapper.class)) {
                // Use the real JSON parser; transport must parse a real fixture response.
                ReflectionTestUtils.setField(context.getBean(RestApiBridge.class),"mapper",new ObjectMapper());
                var bridge=context.getBean(RestApiBridge.class);assertThat(bridge.configured()).isTrue();assertThat(bridge.reportMaxJobs()).isEqualTo(7);
                for(String operation:List.of("summary","jobs","history","webhooks","scan-history")) bridge.read(operation,Map.of("page","2"));
                bridge.detail("job1");bridge.progress("job1");bridge.webhookStatus("job1");bridge.retryWebhook("job1");
                bridge.upload(List.of(new MockMultipartFile("files","sample.txt","text/plain","sample".getBytes())),"receiver-1");
                assertThat(seen).containsExactly("/base/custom/stats?page=2","/base/custom/jobs?page=2","/base/custom/history?page=2",
                    "/base/custom/webhooks?page=2","/base/custom/scan_history?page=2","/base/custom/jobs/job1","/base/custom/jobs/job1/progress",
                    "/base/custom/jobs/job1/webhook","/base/custom/jobs/job1/webhook/retry","/base/custom/scan");
                assertThat(modes).allMatch("Bearer synthetic-rest-secret"::equals);
                assertThat(bodies.get(9)).contains("name=\"DOCUMENTS\"","name=\"callbackId\"","receiver-1","sample.txt");
                var store=ConfigurationSource.store(env).orElseThrow();store.save(store.snapshot().revision(),Map.of("clamav.rest.enabled","false"),Set.of(),true,"test");
                assertThat(bridge.configured()).isTrue(); // startup-bound until recreation
            }
            try(var context=context(saved(Map.of()),RestApiBridge.class,ObjectMapper.class)) {
                assertThat(context.getBean(RestApiBridge.class).configured()).isFalse();
                assertThatThrownBy(()->context.getBean(RestApiBridge.class).read("summary",Map.of())).hasMessageContaining("not configured");
            }
        } finally {server.stop(0);}
    }
    @Test void restReadAndUploadTimeoutsRemainDistinctAfterCentralBinding() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/",exchange->{try {exchange.getRequestBody().readAllBytes();Thread.sleep(1300);byte[] data="{}".getBytes();exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);}
            catch(Exception ignored){}finally{exchange.close();}});server.start();
        try(var context=context(saved(Map.of("CLAMAV_REST_URL","http://127.0.0.1:"+server.getAddress().getPort(),"CLAMAV_REST_TIMEOUT_SECONDS","1",
                "CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS","3")),RestApiBridge.class,ObjectMapper.class)) {
            var bridge=context.getBean(RestApiBridge.class);ReflectionTestUtils.setField(bridge,"mapper",new ObjectMapper());
            long begin=System.nanoTime();assertThatThrownBy(()->bridge.read("summary",Map.of())).hasMessageContaining("Cannot reach");
            assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(3));
            assertThat(bridge.upload(List.of(new MockMultipartFile("files","sample.txt","text/plain",new byte[]{1})),null).status()).isEqualTo(200);
        } finally {server.stop(0);workers.shutdownNow();}
    }
    @Test void datasourceCredentialsDriverPoolAndMigrationOptionsBindBeforeOpeningARealDatabase() throws Exception {
        var env=saved(Map.of("spring.datasource.url","jdbc:h2:mem:consumer_binding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username","binding_user","spring.datasource.password","synthetic-db-secret","spring.datasource.driver-class-name","org.h2.Driver",
            "spring.datasource.hikari.connection-timeout","2400","spring.flyway.baseline-on-migrate","false","spring.flyway.baseline-version","1",
            "spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation","false"));
        var properties=bind(env,"spring.datasource",DataSourceProperties.class);properties.afterPropertiesSet();
        try(var database=properties.initializeDataSourceBuilder().type(HikariDataSource.class).build()) {
            Binder.get(env).bind("spring.datasource.hikari",Bindable.ofInstance(database));
            assertThat(database.getConnectionTimeout()).isEqualTo(2400);
            try(var connection=database.getConnection()) {assertThat(connection.getMetaData().getUserName()).isEqualTo("BINDING_USER");assertThat(connection.isValid(1)).isTrue();}
            var flyway=bind(env,"spring.flyway",FlywayProperties.class);
            var migration=org.flywaydb.core.Flyway.configure().dataSource(database).locations("classpath:db/migration/h2")
                .baselineOnMigrate(flyway.isBaselineOnMigrate()).baselineVersion(flyway.getBaselineVersion()).load();
            migration.migrate();assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("4");
            assertThat(bind(env,"spring.jpa",JpaProperties.class).getProperties()).containsEntry("hibernate.jdbc.lob.non_contextual_creation","false");
        }
    }
    @Test void transportMultipartAndManagementSettingsBindToTheirRealSpringBootConsumers() {
        var env=saved(Map.of("server.forward-headers-strategy","none","server.servlet.context-path","/scanner",
            "server.servlet.session.cookie.secure","true","server.servlet.session.cookie.same-site","strict",
            "spring.servlet.multipart.max-file-size","3MB","spring.servlet.multipart.max-request-size","5MB","management.server.port","9097"));
        var server=bind(env,"server",ServerProperties.class);
        assertThat(server.getForwardHeadersStrategy().name()).isEqualTo("NONE");assertThat(server.getServlet().getContextPath()).isEqualTo("/scanner");
        assertThat(server.getServlet().getSession().getCookie().getSecure()).isTrue();assertThat(server.getServlet().getSession().getCookie().getSameSite().name()).isEqualTo("STRICT");
        var multipart=bind(env,"spring.servlet.multipart",MultipartProperties.class).createMultipartConfig();
        assertThat(multipart.getMaxFileSize()).isEqualTo(3L*1024*1024);assertThat(multipart.getMaxRequestSize()).isEqualTo(5L*1024*1024);
        assertThat(bind(env,"management.server",ManagementServerProperties.class).getPort()).isEqualTo(9097);
    }
    @Test void savedPolicyControlsRealUploadAndQuarantineLocationsAndLiveValidation() throws Exception {
        var env=saved(Map.of("app.allowedScanRoots",folder.toString(),"app.upload.maxBytes","4","app.storage.uploadDir",folder.resolve("uploads").toString(),
            "app.storage.quarantineDir",folder.resolve("quarantine").toString(),"app.quarantine.enabled","true","app.upload.pathScan.enabled","false",
            "app.upload.pathScan.fallbackToInstream","true","app.concurrentScans","3","app.watch.enabled","true","app.watch.pollSeconds","45"));
        var settings=settings(env);var repository=mock(ScanJobRepository.class);var executor=mock(ScanExecutionService.class);
        var policy=new DaemonScanPolicy(new ClamdConfigurationObserver("","",false),settings);
        var jobs=new ScanJobService(repository,settings,new ObjectMapper(),executor,policy);
        var endpoint=new ClamdEndpoint("primary","localhost",3310,xyz.capybara.clamav.Platform.UNIX);
        var job=jobs.createUploadJobs(List.of(new MockMultipartFile("files","file.txt","text/plain",new byte[]{1,2,3})),endpoint,"admin").get(0);
        assertThat(Path.of(job.getStoredPath())).hasParent(folder.resolve("uploads"));assertThat(Files.readAllBytes(Path.of(job.getStoredPath()))).containsExactly(1,2,3);
        assertThatThrownBy(()->jobs.createUploadJobs(List.of(new MockMultipartFile("files","too-large.txt","text/plain",new byte[5])),endpoint,"admin")).hasMessageContaining("limit");
        assertThatThrownBy(()->jobs.createPathJob(folder.resolveSibling("outside").toString(),endpoint,"admin")).isInstanceOf(IllegalArgumentException.class);
        Path quarantined=new QuarantineService(settings).quarantine(Path.of(job.getStoredPath()));assertThat(quarantined).hasParent(folder.resolve("quarantine")).exists();
        assertThat(settings.uploadPathScanEnabled()).isFalse();assertThat(settings.uploadPathScanFallbackToInstream()).isTrue();
        assertThat(settings.concurrentScans()).isEqualTo(3);assertThat(settings.watchEnabled()).isTrue();assertThat(settings.watchPollSeconds()).isEqualTo(45);
        var store=ConfigurationSource.store(env).orElseThrow();store.save(store.snapshot().revision(),Map.of("app.upload.maxBytes","8","app.quarantine.enabled","false",
            "app.upload.pathScan.enabled","true","app.upload.pathScan.fallbackToInstream","false","app.watch.enabled","false","app.watch.pollSeconds","60"),Set.of(),true,"test");
        assertThat(jobs.createUploadJobs(List.of(new MockMultipartFile("files","larger.txt","text/plain",new byte[5])),endpoint,"admin")).hasSize(1);
        assertThat(new QuarantineService(settings).quarantine(quarantined)).isNull();assertThat(quarantined).exists();
        assertThat(settings.uploadPathScanEnabled()).isTrue();assertThat(settings.uploadPathScanFallbackToInstream()).isFalse();assertThat(settings.watchEnabled()).isFalse();assertThat(settings.watchPollSeconds()).isEqualTo(60);
        var scanMetrics=mock(ScanMetrics.class);var execution=new ScanExecutionService(settings,repository,new ObjectMapper(),mock(ClamavClientProvider.class),
            mock(QuarantineService.class),mock(NotificationService.class),mock(AuditService.class),scanMetrics,policy);
        execution.init();var pool=org.mockito.ArgumentCaptor.forClass(ThreadPoolExecutor.class);verify(scanMetrics).bindExecutor(pool.capture());
        try {assertThat(pool.getValue().getCorePoolSize()).isEqualTo(3);}finally{pool.getValue().shutdownNow();}
    }
    @Test void notificationPolicyAndBoundedDispatchUseTheSavedParameters() throws Exception {
        var values=new LinkedHashMap<String,String>();for(var item:Map.of("connect-timeout","1200ms","read-timeout","1300ms","dns-timeout","1400ms",
            "delivery-timeout","1800ms","workers","3","queue-capacity","7","allow-private-addresses","true").entrySet()) values.put("clamav.webhook."+item.getKey(),item.getValue());
        values.put("app.webhook.enabled","true");values.put("app.webhook.url","http://127.0.0.1:1/events");var env=saved(values);
        var policy=bind(env,"clamav.webhook",WebhookNotificationProperties.class);
        assertThat(policy.getConnectTimeout()).isEqualTo(Duration.ofMillis(1200));assertThat(policy.getReadTimeout()).isEqualTo(Duration.ofMillis(1300));
        assertThat(policy.getDnsTimeout()).isEqualTo(Duration.ofMillis(1400));assertThat(policy.getDeliveryTimeout()).isEqualTo(Duration.ofMillis(1800));
        assertThat(new WebhookAddressPolicy(policy).validateUrl("http://127.0.0.1/events")).isNotNull();
        var pool=(ThreadPoolExecutor)new WebhookNotificationConfiguration().webhookNotificationExecutor(policy);
        try {assertThat(pool.getMaximumPoolSize()).isEqualTo(3);assertThat(pool.getQueue().remainingCapacity()).isEqualTo(7);
            var client=mock(WebhookDeliveryClient.class);when(client.validateUrl(anyString())).thenAnswer(i->java.net.URI.create(i.getArgument(0)));
            var delivery=new NotificationService(settings(env),new ObjectMapper(),client,pool);var job=new ScanJob();job.setId("one");
            delivery.notifyIfNeeded(job);pool.shutdown();assertThat(pool.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            verify(client).post(eq(java.net.URI.create("http://127.0.0.1:1/events")),contains("one"));
            var store=ConfigurationSource.store(env).orElseThrow();store.save(store.snapshot().revision(),Map.of("app.webhook.enabled","false"),Set.of(),true,"test");
            delivery.notifyIfNeeded(job);verify(client,times(1)).post(any(),anyString());
        } finally {pool.shutdownNow();}
    }
    @Test void observerModesAndIntervalsChangeTheActualObserverWithoutARestart() throws Exception {
        Path clamd=Files.writeString(folder.resolve("clamd.conf"),"TCPSocket 3315\nStreamMaxLength 64\n");
        Path fresh=Files.writeString(folder.resolve("freshclam.conf"),"Checks 12\nDatabaseMirror database.clamav.net\n");
        var env=saved(Map.of("clamav.config.clamd-path",clamd.toString(),"clamav.config.freshclam-path",fresh.toString(),
            "center.observer.enabled","true","clamav.config.discovery-enabled","true","clamav.config.poll-ms","7000"));
        try(var context=context(env,ClamdConfigurationObserver.class,IntegrationSettingsService.class)) {
            var observer=context.getBean(ClamdConfigurationObserver.class);var integrations=new IntegrationSettingsService(env,folder.resolve("integrations.properties").toString());
            observer.liveSettings(integrations,30000);assertThat(observer.discoveryEnabled()).isTrue();assertThat(observer.pollMillis()).isEqualTo(7000);
            assertThat(observer.clamdPath()).isEqualTo(clamd.toString());assertThat(observer.freshclamPath()).isEqualTo(fresh.toString());
            assertThat(observer.clamd().directives()).containsKey("TCPSocket");assertThat(observer.freshclam().directives()).containsKey("Checks");
            var store=ConfigurationSource.store(env).orElseThrow();store.save(store.snapshot().revision(),Map.of("clamav.config.discovery-enabled","false","clamav.config.poll-ms","11000"),Set.of(),true,"test");
            observer.refresh();assertThat(observer.discoveryEnabled()).isFalse();assertThat(observer.clamd().directives()).containsKey("TCPSocket");assertThat(observer.pollMillis()).isEqualTo(11000);
            store.save(store.snapshot().revision(),Map.of("center.observer.enabled","false"),Set.of(),true,"test");observer.refresh();
            assertThat(observer.observationEnabled()).isFalse();assertThat(observer.clamd().directives()).isEmpty();
        }
    }
    @Test void defaultUiPreferencesAndPublicUrlReachTheRenderedModelAndLocaleResolver() {
        var env=saved(Map.of("clamav.ui.default-theme","dark","clamav.ui.default-language","fr","clamav.public-url","https://scanner.example.test/prefix"));
        try(var context=context(env,UiModelAdvice.class,IdentityProperties.class)) {
            assertThat(context.getBean(UiModelAdvice.class).defaultTheme()).isEqualTo("dark");assertThat(context.getBean(UiModelAdvice.class).publicUrl()).isEqualTo("https://scanner.example.test/prefix");
        }
        try(var context=context(env,ClamAVWebClientAppConfig.class)) {
            var locale=context.getBean(org.springframework.web.servlet.i18n.CookieLocaleResolver.class);
            assertThat(locale.resolveLocale(new MockHttpServletRequest()).getLanguage()).isEqualTo("fr");
        }
    }
    @Test void apiLimitsAndDaemonFreshnessUseTheSavedValues() throws Exception {
        var env=saved(Map.of("api.v1.scan-rate-limit-per-minute","2","api.v1.max-page-size","2",
            "api.v1.clamd.max-database-age-hours","1","api.v1.clamd.database-timezone","Europe/Bucharest"));
        try(var context=context(env,ScanRateLimitFilter.class)) {
            var filter=context.getBean(ScanRateLimitFilter.class);var accepted=new AtomicInteger();
            for(int i=0;i<3;i++) {
                var request=new MockHttpServletRequest("POST","/api/v1/scans/upload");var response=new MockHttpServletResponse();
                filter.doFilter(request,response,(a,b)->accepted.incrementAndGet());assertThat(response.getStatus()).isEqualTo(i<2?200:429);
            }
            assertThat(accepted.get()).isEqualTo(2);
        }
        try(var context=context(env,ApiV1Controller.class,EndpointService.class,ScanJobService.class,ScanJobRepository.class,
                AuditEventRepository.class,AuditService.class,ApiV1Access.class,javax.sql.DataSource.class,ClamavClientProvider.class,ClamdTopService.class)) {
            var api=context.getBean(ApiV1Controller.class);var repository=context.getBean(ScanJobRepository.class);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<ScanJob>(List.of()));
            api.jobList(0,2,null,null,null,null);
            assertThatThrownBy(()->api.jobList(0,3,null,null,null,null)).hasMessageContaining("pagination");
            var endpoint=new ClamdEndpoint("primary","localhost",3310,xyz.capybara.clamav.Platform.UNIX);
            when(context.getBean(EndpointService.class).defaultEndpointOrEnsure()).thenReturn(endpoint);
            var client=mock(ClamdClient.class);when(context.getBean(ClamavClientProvider.class).clientFor(endpoint)).thenReturn(client);
            var timestamp=Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var format=java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy",Locale.US).withZone(ZoneId.of("Europe/Bucharest"));
            when(client.version()).thenReturn("ClamAV 1.5.4/28170/"+format.format(timestamp));
            var health=api.clamd(null,null);assertThat(health.getStatusCode().value()).isEqualTo(503);
            assertThat(health.getBody().signatureDatabaseFreshness()).isEqualTo("STALE");
            assertThat(health.getBody().signatureDatabaseTimestamp()).isEqualTo(timestamp);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Scheduling { }
    @Test void metricsEndpointCeilingAndPollingIntervalReachTheRealScheduledCollector() throws Exception {
        var env=saved(Map.of("clamav.metrics.clamd-max-endpoints","1","clamav.metrics.clamd-poll-ms","1200"));
        env.getPropertySources().addFirst(new MapPropertySource("timer-fixture",Map.of("clamav.metrics.clamd-initial-delay-ms","20")));
        var endpoints=mock(EndpointService.class);var top=mock(ClamdTopService.class);
        var first=new ClamdEndpoint("first","localhost",3310,xyz.capybara.clamav.Platform.UNIX);
        var second=new ClamdEndpoint("second","localhost",3311,xyz.capybara.clamav.Platform.UNIX);
        ReflectionTestUtils.setField(first,"id",1L);ReflectionTestUtils.setField(second,"id",2L);
        when(endpoints.all()).thenReturn(List.of(first,second));when(top.snapshot(1L)).thenReturn(Map.of("ok",false));
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(env);context.getBeanFactory().registerSingleton("endpoints",endpoints);context.getBeanFactory().registerSingleton("top",top);
            context.getBeanFactory().registerSingleton("registry",registry);context.register(ClamdMetricsCollector.class,Scheduling.class);context.refresh();
            verify(top,timeout(2200).atLeast(2)).snapshot(1L);verify(top,never()).snapshot(2L);
            assertThat(context.getBean(ClamdMetricsCollector.class).status()).hasSize(1);
            assertThat(registry.get("clamav.clamd.endpoint.configured").tag("slot","0").gauge().value()).isEqualTo(1);
        } finally {registry.close();}
    }
}
