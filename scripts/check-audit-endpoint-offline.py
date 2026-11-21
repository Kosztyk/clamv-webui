#!/usr/bin/env python3
"""Compile actual EndpointService and test transaction-skipping with tiny Java stubs.

This is an offline behavior check, NOT a Spring/JPA integration test.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
from subprocess import run
ROOT=Path(__file__).resolve().parent.parent
SOURCE=ROOT/'src/main/java/info/trizub/clamav/webclient/service/EndpointService.java'
STUBS={
'org/springframework/stereotype/Service.java': 'package org.springframework.stereotype;public @interface Service {}',
'org/springframework/beans/factory/annotation/Autowired.java':'package org.springframework.beans.factory.annotation;public @interface Autowired {}',
'org/springframework/transaction/annotation/Transactional.java':'package org.springframework.transaction.annotation;public @interface Transactional {}',
'org/springframework/core/env/Environment.java':'package org.springframework.core.env; public interface Environment {}',
'org/springframework/transaction/PlatformTransactionManager.java':'package org.springframework.transaction; public interface PlatformTransactionManager {}',
'org/springframework/transaction/support/TransactionTemplate.java':'''package org.springframework.transaction.support;
 import org.springframework.transaction.PlatformTransactionManager;import java.util.function.Consumer;
 public class TransactionTemplate { public static int count=0;
 public TransactionTemplate(PlatformTransactionManager m){}
 public void executeWithoutResult(Consumer<Object> c){count++;c.accept(null);}
 }''',
'xyz/capybara/clamav/Platform.java':'package xyz.capybara.clamav; public enum Platform {UNIX, WINDOWS}',
'info/trizub/clamav/webclient/config/DaemonConfiguration.java':'''package info.trizub.clamav.webclient.config;
 public class DaemonConfiguration {public record Endpoint(String host,int port){}}''',
'info/trizub/clamav/webclient/config/ClamdConfigurationObserver.java':'''package info.trizub.clamav.webclient.config;
 import java.util.Optional;
 public class ClamdConfigurationObserver {public DaemonConfiguration.Endpoint candidate;
 public int refreshed=0;public void refresh(){refreshed++;}
 public Optional<DaemonConfiguration.Endpoint> endpointCandidate(){return Optional.ofNullable(candidate);}}
 ''',
'info/trizub/clamav/webclient/model/ClamdEndpoint.java':'''package info.trizub.clamav.webclient.model;
 import xyz.capybara.clamav.Platform;
 public class ClamdEndpoint {
 private Long id; private String name,host;private int port;private Platform platform;
 private boolean enabled=true;
 public ClamdEndpoint(String name,String host,int port,Platform platform){this.name=name;this.host=host;this.port=port;this.platform=platform;}
 public Long getId(){return id;} public void setId(Long n){id=n;}
 public String getName(){return name;} public String getHost(){return host;}public int getPort(){return port;}public Platform getPlatform(){return platform;}
 public void setName(String n){name=n;}public void setHost(String n){host=n;}public void setPort(int n){port=n;}
 public void setPlatform(Platform n){platform=n;}public void setEnabled(boolean n){enabled=n;}
 }''',
'info/trizub/clamav/webclient/repo/ClamdEndpointRepository.java':'''package info.trizub.clamav.webclient.repo;
 import info.trizub.clamav.webclient.model.ClamdEndpoint;
 import java.util.*;
 public class ClamdEndpointRepository {
 private long next=1;public Map<Long,ClamdEndpoint> values=new HashMap<>();
 public Optional<ClamdEndpoint> findByName(String name){return values.values().stream().filter(e->name.equals(e.getName())).findFirst();}
 public Optional<ClamdEndpoint> findById(Long id){return Optional.ofNullable(values.get(id));}
 public long count(){return values.size();}
 public List<ClamdEndpoint> findAll(){return List.copyOf(values.values());}
 public ClamdEndpoint save(ClamdEndpoint ep){if(ep.getId()==null)ep.setId(next++);values.put(ep.getId(),ep);return ep;}
 public void deleteById(Long id){values.remove(id);}
 }''',
'info/trizub/clamav/webclient/service/SettingsService.java':'''package info.trizub.clamav.webclient.service;
 public class SettingsService {public String legacyPlatform(){return "UNIX";}
 public String legacyHost(){return "127.0.0.1";} public int legacyPort(){return 3310;}}
 ''',
'info/trizub/clamav/webclient/service/ClamavClientProvider.java':'''package info.trizub.clamav.webclient.service;
 public class ClamavClientProvider{public void invalidate(Long id) {}}''',
'info/trizub/clamav/webclient/service/EndpointInputValidator.java':'''package info.trizub.clamav.webclient.service;
 import xyz.capybara.clamav.Platform;
 public class EndpointInputValidator{public static void validate(String n,String h,int p,Platform x){}
 public static String host(String h){return h;}public static void name(String n){}public static void port(int n){}}''',
'info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java':'''package info.trizub.clamav.webclient.configuration;
 import java.util.List;
 public class ConfigurationCatalog {
 public record Field(String key,String section,String label){}
 public static List<Field> all(){return List.of();}
 public static Field get(String key){return new Field(key,"none",key);}
 public static String validate(Field f,String v){return v;}
 }''',
'info/trizub/clamav/webclient/configuration/ConfigurationSource.java':'''package info.trizub.clamav.webclient.configuration;
 import java.util.Optional;import org.springframework.core.env.Environment;
 public class ConfigurationSource {
 public record Override(String value){}
 public static class Store {public Optional<String> saved(String k){return Optional.empty();}
   public Optional<String> active(String k){return Optional.empty();}}
 public static Optional<Override> override(Environment env,ConfigurationCatalog.Field f){return Optional.empty();}
 public static Optional<Store> store(Environment env){return Optional.empty();}
 }''',
'EndpointAuditMain.java':'''import info.trizub.clamav.webclient.service.*;
 import info.trizub.clamav.webclient.repo.*;
 import info.trizub.clamav.webclient.config.*;
 import org.springframework.transaction.*;
 import org.springframework.transaction.support.TransactionTemplate;
 import org.springframework.core.env.Environment;
 public class EndpointAuditMain {
  public static void main(String[] args) throws Exception {
   var repo=new ClamdEndpointRepository();
   var observer=new ClamdConfigurationObserver();
   var service=new EndpointService(repo,new SettingsService(),observer,new Environment(){},
      new PlatformTransactionManager(){},new ClamavClientProvider());
   service.pollConfiguration();if(TransactionTemplate.count!=0)throw new AssertionError("Idle poll began a transaction");
   observer.candidate=new DaemonConfiguration.Endpoint("localhost",3310);
   service.pollConfiguration();service.pollConfiguration();service.pollConfiguration();
   if(TransactionTemplate.count!=1||observer.refreshed!=4)throw new AssertionError("Unchanged poll began extra transaction");
   observer.candidate=new DaemonConfiguration.Endpoint("localhost",3311);
   service.pollConfiguration();
   if(TransactionTemplate.count!=2)throw new AssertionError("Changed endpoint was not synchronized");
   var primary=repo.findByName("default").orElseThrow();
   if(primary.getPort()!=3311)throw new AssertionError("Changed endpoint was not persisted");
   // Force the periodic drift-check window to elapse without sleeping five minutes.
   var next=EndpointService.class.getDeclaredField("nextReconciliationNanos");next.setAccessible(true);
   next.setLong(service,System.nanoTime()-1L);
   service.pollConfiguration();
   if(TransactionTemplate.count!=2)throw new AssertionError("Matching periodic read opened write transaction");
   primary.setHost("external-change");primary.setPort(4444);
   next.setLong(service,System.nanoTime()-1L);
   service.pollConfiguration();
   if(TransactionTemplate.count!=3||!"localhost".equals(primary.getHost())||primary.getPort()!=3311)
     throw new AssertionError("External DB drift was not reconciled");
   service.delete(primary.getId());service.pollConfiguration();
   if(TransactionTemplate.count!=4||repo.findByName("default").isEmpty())
      throw new AssertionError("Endpoint deletion left stale candidate marker");
   System.out.println("PASS: actual EndpointService compiles on Java 17; idle polls avoid transactions, edits are synchronized");
  }
 }'''
}
with TemporaryDirectory(prefix='clamav-endpoint-') as temp:
 work=Path(temp);sources=[]
 for relative,text in STUBS.items():
  file=work/relative;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
  sources.append(str(file))
 classes=work/'classes';classes.mkdir()
 run(['javac','--release','17','-d',str(classes),str(SOURCE),*sources],check=True)
 run(['java','-cp',str(classes),'EndpointAuditMain'],check=True)
