#!/usr/bin/env python3
"""Compile actual ClamAV config observer and path policy with minimal Java stubs.

Runs without Maven or ClamAV. The fake client tests the directory-scan guard,
not daemon performance or database integration. Uses javac --release 17.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
from subprocess import run

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'src/main/java/info/trizub/clamav/webclient'
STUBS = {
 'org/springframework/beans/factory/annotation/Value.java': '''package org.springframework.beans.factory.annotation;
 import java.lang.annotation.*;
 @Retention(RetentionPolicy.RUNTIME) public @interface Value { String value(); }''',
 'org/springframework/beans/factory/annotation/Autowired.java': '''package org.springframework.beans.factory.annotation;
 import java.lang.annotation.*;
 @Retention(RetentionPolicy.RUNTIME) public @interface Autowired {}''',
 'org/springframework/stereotype/Service.java': '''package org.springframework.stereotype;
 import java.lang.annotation.*;
 @Retention(RetentionPolicy.RUNTIME) public @interface Service {}''',
 'org/slf4j/Logger.java': '''package org.slf4j;
 public interface Logger { default void warn(String msg,Object arg){} }''',
 'org/slf4j/LoggerFactory.java': '''package org.slf4j;
 public class LoggerFactory { public static Logger getLogger(Class<?> c) {return new Logger(){};} }''',
 'info/trizub/clamav/webclient/service/IntegrationSettingsService.java': '''package info.trizub.clamav.webclient.service;
 public class IntegrationSettingsService {
  public boolean observationEnabled(){return true;}
  public boolean synchronizationEnabled(boolean d){return d;}
  public long observerPollMillis(long d){return d;}
 }''',
 'info/trizub/clamav/webclient/model/ClamdEndpoint.java': '''package info.trizub.clamav.webclient.model;
 public class ClamdEndpoint { public String getName(){return "default";}
 public Long getId(){return 1L;} }''',
 'info/trizub/clamav/webclient/service/EndpointService.java': '''package info.trizub.clamav.webclient.service;
 import info.trizub.clamav.webclient.model.ClamdEndpoint;
 public class EndpointService { public ClamdEndpoint defaultEndpoint(){return new ClamdEndpoint();} }''',
 'info/trizub/clamav/webclient/service/ClamdClient.java': '''package info.trizub.clamav.webclient.service;
 import java.io.IOException; import java.nio.file.Path;
 import xyz.capybara.clamav.commands.scan.result.ScanResult;
 public interface ClamdClient { ScanResult parallelScan(Path file) throws IOException; }''',
 'info/trizub/clamav/webclient/service/SettingsService.java': '''package info.trizub.clamav.webclient.service;
 import java.nio.file.Path; import java.util.List;
 import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
 public class SettingsService {
  private final List<Path> roots; private final ClamdConfigurationObserver observer;
  private final Path config; private final boolean change;
  private int calls;
  public SettingsService(Path root,ClamdConfigurationObserver observer,Path config,boolean change){
   this.roots=List.of(root);this.observer=observer;this.config=config;this.change=change;}
  public long uploadMaxBytes(){return 100_000_000L;}
  public List<Path> allowedRoots(){
    if(++calls==2){try{if(change)java.nio.file.Files.writeString(config,
      "TCPSocket 3310\\nExcludePath \\\\.changed$\\n");observer.refresh();}
      catch(java.io.IOException e){throw new RuntimeException(e);} }
    return roots;
  }
 }''',
 'xyz/capybara/clamav/commands/scan/result/ScanResult.java': '''package xyz.capybara.clamav.commands.scan.result;
 import java.util.Map;import java.util.Collection;
 public interface ScanResult {
  enum OK implements ScanResult {INSTANCE}
  record VirusFound(Map<String,Collection<String>> foundViruses) implements ScanResult {
   public Map<String,Collection<String>> getFoundViruses(){return foundViruses;}
  }
 }''',
 'PolicyAuditMain.java': '''import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
 import info.trizub.clamav.webclient.service.*;
 import info.trizub.clamav.webclient.model.ClamdEndpoint;
 import xyz.capybara.clamav.commands.scan.result.ScanResult;
 import java.nio.file.*;
 import java.util.concurrent.atomic.AtomicInteger;
 public class PolicyAuditMain {
  public static void main(String[] args) throws Exception {
   Path dir=Files.createTempDirectory("clamav-audit-");
   Path config=Files.writeString(dir.resolve("clamd.conf"),"TCPSocket 3310\\n");
   Path tree=Files.createDirectory(dir.resolve("files"));
   for(int n=0;n<100;n++){
     Path folder=Files.createDirectory(tree.resolve("batch"+n));
     Files.writeString(folder.resolve("sample"+n+".txt"),"not virus");
   }
   ClamdConfigurationObserver observer=new ClamdConfigurationObserver(config.toString(),"",true);
   var first=observer.effective();var initial=java.time.Instant.parse(observer.lastSuccessfulObservedAt());observer.refresh();
   if(first!=observer.effective())throw new AssertionError("Unchanged refresh replaced effective policy");
   if(java.time.Instant.parse(observer.lastSuccessfulObservedAt()).isBefore(initial))
     throw new AssertionError("Last successful observation timestamp did not advance");
   var successful=observer.lastSuccessfulObservedAt();
   Files.writeString(config,"TCPSocket invalid\\n");observer.refresh();
   if(!successful.equals(observer.lastSuccessfulObservedAt())||first!=observer.effective())
     throw new AssertionError("Invalid refresh claimed a new successful observation");
   Files.writeString(config,"TCPSocket 3310\\n");observer.refresh();
   AtomicInteger commands=new AtomicInteger();
   ClamdClient fake=p->{commands.incrementAndGet();return ScanResult.OK.INSTANCE;};
   var policy=new DaemonScanPolicy(observer,new SettingsService(dir,observer,config,false));
   var result=policy.scanPathReport(fake,new ClamdEndpoint(),tree);
   if(!result.directoryCommand()||result.scannedFiles()!=100||commands.get()!=1)
     throw new AssertionError("Directory scan did not survive unchanged refresh");
   commands.set(0);
   var changed=new DaemonScanPolicy(observer,new SettingsService(dir,observer,config,true));
   boolean rejected=false;
   try{changed.scanPathReport(fake,new ClamdEndpoint(),tree);}
   catch(IllegalArgumentException e){ rejected=e.getMessage().contains("policy"); }
   if(!rejected)throw new AssertionError("Genuine policy change was not rejected");
   System.out.println("PASS: actual observer/path policy compiled on Java 17; unchanged refresh retained one MULTISCAN, genuine policy change rejected");
  }
 }'''
}

with TemporaryDirectory(prefix='clamav-audit-policy-') as temporary:
    work=Path(temporary); sources=[]
    for relative, text in STUBS.items():
        file=work/relative;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
        sources.append(str(file))
    for relative in ('config/DaemonConfiguration.java','config/ClamdConfigurationObserver.java',
                     'service/DaemonScanPolicy.java','util/PathPolicy.java'):
        sources.append(str(SOURCE/relative))
    classes=work/'classes';classes.mkdir()
    run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),*sources],check=True)
    run(['java','-cp',str(classes),'PolicyAuditMain'],check=True)
