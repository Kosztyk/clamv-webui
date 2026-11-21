#!/usr/bin/env python3
"""Isolated Java 17 compilation of the actual API v1 DTO; no Spring/Jackson runtime.

This verifies metadata/error/quarantine compatibility with tiny model stubs.
Jackson signature extraction is exercised by Maven integration tests, not here.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
from subprocess import run
ROOT=Path(__file__).resolve().parent.parent
BASE=ROOT/'src/main/java/info/trizub/clamav/webclient'
STUBS={
'info/trizub/clamav/webclient/model/ScanJob.java': '''package info.trizub.clamav.webclient.model;
import java.time.Instant;
public class ScanJob {private String id,target,virus,error,quarantine;private ScanJobType type;
 private ScanJobStatus status;private ScanVerdict verdict;private ClamdEndpoint endpoint;private Long size;
 private Instant submitted,started,finished;
 public String getId(){return id;}public ScanJobType getType(){return type;}public ScanJobStatus getStatus(){return status;}
 public ScanVerdict getVerdict(){return verdict;}public ClamdEndpoint getEndpoint(){return endpoint;}
 public String getTarget(){return target;}public Long getSizeBytes(){return size;}public Instant getSubmittedAt(){return submitted;}
 public Instant getStartedAt(){return started;}public Instant getFinishedAt(){return finished;}
 public String getFoundVirusesJson(){return virus;}public String getErrorMessage(){return error;}
 public String getQuarantinePath(){return quarantine;}
 }''',
'info/trizub/clamav/webclient/model/ClamdEndpoint.java': '''package info.trizub.clamav.webclient.model;
public class ClamdEndpoint {public long getId(){return 1;}public String getName(){return "default";}
public boolean isEnabled(){return true;}}''',
'info/trizub/clamav/webclient/model/AuditEvent.java': '''package info.trizub.clamav.webclient.model;
import java.time.Instant;
public class AuditEvent {public long getId(){return 1;}public Instant getAt(){return null;}
public String getUsername(){return "";}public String getAction(){return "";}public String getOutcome(){return "";}
public String getTransport(){return "";}public Long getEndpointId(){return 1L;}public String getJobId(){return "";}}''',
'info/trizub/clamav/webclient/service/QuarantineService.java': '''package info.trizub.clamav.webclient.service;
public class QuarantineService {public static boolean failureRecorded(String s){return "Quarantine failed".equals(s);}}''',
'com/fasterxml/jackson/databind/ObjectMapper.java': '''package com.fasterxml.jackson.databind;
public class ObjectMapper {public JsonNode readTree(String raw){return new JsonNode();}}''',
'com/fasterxml/jackson/databind/JsonNode.java': '''package com.fasterxml.jackson.databind;
import java.util.*;
public class JsonNode implements Iterable<JsonNode>{public boolean isObject(){return false;}public boolean isArray(){return false;}
public boolean isTextual(){return false;}public String asText(){return "";}
public Iterator<JsonNode> iterator(){return List.<JsonNode>of().iterator();}}''',
'org/springframework/data/domain/Page.java': '''package org.springframework.data.domain;
import java.util.List;
public interface Page<E> {List<E> getContent();int getNumber();int getSize();long getTotalElements();int getTotalPages();}''',
}
# Actual model/DTO compilation with lightweight Jackson and Spring stubs.
STUBS['ApiDtoAuditMain.java']='''import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.api.v1.ApiV1Dtos;
import java.time.Instant;
public class ApiDtoAuditMain {
 public static void main(String[] args){
 var date=Instant.parse("2026-11-12T15:00:00Z");
 var virus=new ScanJobSummary("v",ScanJobType.UPLOAD,ScanJobStatus.FINISHED,ScanVerdict.VIRUS_FOUND,
   "file.txt",null,null,date,1L,"default",1L,date,date);
 var recorded=ApiV1Dtos.Job.fromSummary(virus,"/private/quarantine",new ScanJobListDiagnostics("v",null,null,"/private/quarantine"));
 if(!"RECORDED".equals(recorded.quarantineStatus())||recorded.toString().contains("/private/quarantine"))
  throw new AssertionError("Private quarantine path or status regression");
 var failed=ApiV1Dtos.Job.fromSummary(virus,null,new ScanJobListDiagnostics("v",null,"Quarantine failed",null));
 if(!"FAILED".equals(failed.quarantineStatus())||!"QUARANTINE_FAILED".equals(failed.error().code()))
  throw new AssertionError("Quarantine failure was lost");
 var problem=new ScanJobSummary("e",ScanJobType.PATH,ScanJobStatus.FINISHED,ScanVerdict.ERROR,
    "/secret/source",null,null,date,1L,"default",null,date,date);
 var error=ApiV1Dtos.Job.fromSummary(problem,null,new ScanJobListDiagnostics("e",null,
  "Path is excluded by the daemon scan policy.",null));
 if(!"POLICY_REJECTED".equals(error.error().code())||error.filename()!=null||error.toString().contains("/secret/source"))
  throw new AssertionError("Sanitized policy failure is missing");
 var clean=new ScanJobSummary("c",ScanJobType.UPLOAD,ScanJobStatus.FINISHED,ScanVerdict.OK,
   "clean.txt",null,null,date,1L,"default",null,date,date);
 if(!"RECORDED".equals(ApiV1Dtos.Job.fromSummary(clean,"/private/legacy",null).quarantineStatus()))
  throw new AssertionError("Legacy quarantine status lost");
 System.out.println("PASS: real API v1 DTO compiles for Java 17; error/quarantine statuses and path protection preserved");
 }
}'''
with TemporaryDirectory(prefix='clamav-api-v1-') as temporary:
    work=Path(temporary);sources=[]
    for relative,content in STUBS.items():
        source=work/relative;source.parent.mkdir(parents=True,exist_ok=True);source.write_text(content);sources.append(str(source))
    for path in ['api/v1/ApiV1Dtos.java','model/ScanJobSummary.java','model/ScanJobListDiagnostics.java',
                 'model/ScanJobListQuarantine.java','model/ScanJobType.java','model/ScanJobStatus.java','model/ScanVerdict.java']:
        sources.append(str(BASE/path))
    out=work/'classes';out.mkdir()
    run(['javac','--release','17','-d',str(out),*sources],check=True)
    run(['java','-cp',str(out),'ApiDtoAuditMain'],check=True)
