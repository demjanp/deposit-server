package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.NamespaceCloneAnalysisIT.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** Resource import regressions against the installed release, independent of the browser workflow. */
@Timeout(180)
class CloneAndImportProbeIT {
    String token,namespace,name;
    static final byte[] BYTES="Operations shared scientific resource\n".getBytes(StandardCharsets.UTF_8);
    @BeforeAll static void open()throws Exception{NamespaceCloneAnalysisIT.connect();}
    @AfterAll static void close(){NamespaceCloneAnalysisIT.disconnect();}
    @BeforeEach void setup(){token=login();name="OperationsImport-"+key();namespace=id(call("deposit.create.namespace",name,m("historyMode","persistent"),token));select(namespace);}
    void select(String ns){call("deposit.set.activeNamespace",m("id",ns),token);}
    String upload(){String hash=DataImportExportIT.hash(BYTES);String upload=call("deposit.resource.beginUpload","specimen.txt",(long)BYTES.length,hash,"text/plain",token).get("data").get("uploadId").asString();call("deposit.resource.uploadChunk",upload,0L,Base64.getEncoder().encodeToString(BYTES),hash,token);return call("deposit.resource.finishUpload",upload,token).get("data").get("resourceId").asString();}
    String export(){return call("deposit.save.data",m(),m("includeHistory",true),token).get("data").get("resourceId").asString();}
    long scalar(String cypher,Map<String,Object> parameters){return query(cypher,parameters).getFirst().get("n").asLong();}
    String receiver(){String ns=id(call("deposit.create.namespace","OperationsReceiver-"+key(),m("historyMode","persistent"),token));select(ns);return ns;}
    void schema(){call("deposit.create.class","Specimen",m("descriptors",List.of(m("name","Code","type","String"),m("name","File","type","DResource"),m("name","Other","type","DResource"))),token);}
    String object(String code,String resource){return id(call("deposit.create.object","Specimen",m("descriptors",m("Code",code,"File",m("resourceId",resource),"Other",m("resourceId",resource))),token));}
    void assertBytes(String resource){var info=call("deposit.resource.info",resource,token).get("data");assertEquals(DataImportExportIT.hash(BYTES),info.get("checksum").asString());assertEquals(BYTES.length,info.get("bytes").asLong());assertArrayEquals(BYTES,Base64.getDecoder().decode(call("deposit.resource.downloadChunk",resource,0L,(long)BYTES.length,token).get("data").get("bytesBase64").asString()));}

    @Test void persistentCloneSharesFreshResourcesAndRestoresHistoryOnlyContent(){
        schema();String live=upload();object("live",live);
        String historical=upload();String oldObject=id(call("deposit.create.object","Specimen",m("descriptors",m("Code","historical","File",m("resourceId",historical))),token));
        long attachedSequence=scalar("MATCH (n:Namespace {id:$ns}) RETURN n.sequence AS n",m("ns",namespace));
        call("deposit.set.descriptor",oldObject,"File",null,token);
        String packageId=export();call("deposit.set.namespace",m("name",name+"-renamed"),token);
        var cloned=call("deposit.import.namespaceData",packageId,token).get("data");String clone=cloned.get("namespaceId").asString();
        assertEquals(namespace,call("deposit.get.session",token).get("data").get("namespaceId").asString());
        var mapping=cloned.get("resourceMapping");String newLive=mapping.get(live).asString(),newHistorical=mapping.get(historical).asString();assertNotEquals(live,newLive);assertNotEquals(historical,newHistorical);
        assertEquals(2,scalar("MATCH (:Descriptor)-[r:HAS_RESOURCE]->(:DResource {id:$id,namespaceId:$ns}) RETURN count(r) AS n",m("id",newLive,"ns",clone)));
        assertEquals(0,scalar("MATCH (:Descriptor)-[r:HAS_RESOURCE]->(:DResource {id:$id,namespaceId:$ns}) RETURN count(r) AS n",m("id",newHistorical,"ns",clone)));
        assertTrue(scalar("MATCH (:DepositSnapshot)-[r:RETAINS_RESOURCE]->(:DResource {id:$id,namespaceId:$ns}) RETURN count(r) AS n",m("id",newHistorical,"ns",clone))>0);
        assertEquals(0,scalar("MATCH (g:DepositExportGrant {namespaceId:$ns}) RETURN count(g) AS n",m("ns",clone)));
        assertEquals(0,scalar("MATCH (t:DepositTransfer {namespaceId:$ns}) RETURN count(t) AS n",m("ns",clone)));
        select(clone);assertBytes(newLive);assertBytes(newHistorical);
        String clonedObject=cloned.get("identityMappings").get("Object").get(oldObject).asString();
        call("deposit.utils.undo",null,token);assertEquals(newHistorical,query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'File'})-[:HAS_RESOURCE]->(r) RETURN r.id AS id",m("id",clonedObject)).getFirst().get("id").asString());
        String undo=call("deposit.get.revision",token).get("data").get("revisionId").asString();call("deposit.utils.redo",undo,token);
        assertEquals(0,scalar("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'File'})-[r:HAS_RESOURCE]->() RETURN count(r) AS n",m("id",clonedObject)));
        String target=query("MATCH (r:Revision {namespaceId:$ns,sequence:$seq}) RETURN r.id AS id",m("ns",clone,"seq",attachedSequence)).getFirst().get("id").asString();call("deposit.utils.restore",target,token);assertBytes(newHistorical);
        assertEquals(1,scalar("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'File'})-[:HAS_RESOURCE]->(:DResource {id:$resource}) RETURN count(*) AS n",m("id",clonedObject,"resource",newHistorical)));
    }

    @Test void cloneRetainsResourcesReferencedOnlyByAClosedEpoch(){
        schema();String historical=upload();String sourceObject=object("closed epoch",historical);
        long attachedSequence=scalar("MATCH (n:Namespace {id:$ns}) RETURN n.sequence AS n",m("ns",namespace));
        call("deposit.set.descriptor",sourceObject,"File",null,token);call("deposit.set.descriptor",sourceObject,"Other",null,token);
        call("deposit.set.versioningType","disabled",token);call("deposit.set.versioningType","persistent",token);
        String packageId=export();call("deposit.set.namespace",m("name",name+"-renamed"),token);
        var cloned=call("deposit.import.namespaceData",packageId,token).get("data");String clone=cloned.get("namespaceId").asString();
        String resource=cloned.get("resourceMapping").get(historical).asString();assertNotEquals(historical,resource);select(clone);assertBytes(resource);
        assertEquals(0,scalar("MATCH (:Descriptor)-[r:HAS_RESOURCE]->(:DResource {id:$id}) RETURN count(r) AS n",m("id",resource)));
        String target=query("MATCH (r:Revision {namespaceId:$ns,sequence:$seq}) RETURN r.id AS id",m("ns",clone,"seq",attachedSequence)).getFirst().get("id").asString();
        call("deposit.utils.restore",target,token);assertBytes(resource);
        assertEquals(2,scalar("MATCH (:Descriptor)-[r:HAS_RESOURCE]->(:DResource {id:$id}) RETURN count(r) AS n",m("id",resource)));
    }

    @Test void analyzedImportAndPatternFormAttachResourcesInReceivingNamespace(){
        schema();String sourceResource=upload();String sourceObject=object("source",sourceResource);String packageId=export();String target=receiver();
        String plan=call("deposit.import.analyze",m("packageResourceId",packageId),m(),token).get("data").get("planId").asString();var applied=call("deposit.import.apply",plan,token).get("data");String targetResource=applied.get("resourceMapping").get(sourceResource).asString();String targetObject=applied.get("identityMappings").get("Object").get(sourceObject).asString();assertNotEquals(sourceResource,targetResource);assertBytes(targetResource);
        assertEquals(2,scalar("MATCH (:Descriptor)-[r:HAS_RESOURCE]->(:DResource {id:$id,namespaceId:$ns}) RETURN count(r) AS n",m("id",targetResource,"ns",target)));
        call("deposit.create.pattern","ResourceForm",m("objects",m("specimen",m("className","Specimen","root",true,"descriptors",List.of(m("name","Code"),m("name","File"))))),m(),token);
        String formPlan=call("deposit.import.dataByPattern","ResourceForm",m("specimen",m("Code","form updated","File",m("resourceId",targetResource))),m("rootId",targetObject),token).get("data").get("planId").asString();
        call("deposit.import.apply",formPlan,token);
        String formResource=query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'File'})-[:HAS_RESOURCE]->(r) RETURN r.id AS id",m("id",targetObject)).getFirst().get("id").asString();assertBytes(formResource);
        assertEquals("form updated",call("deposit.get.descriptor",m("objectId",targetObject,"name","Code"),token).get("node").asNode().get("value").asString());
        assertEquals(targetResource,query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'Other'})-[:HAS_RESOURCE]->(r) RETURN r.id AS id",m("id",targetObject)).getFirst().get("id").asString());
    }

    @Test void successfulCloneCallerRollbackLeavesNoReceivingGraphOrResourceRecords(){
        schema();object("rollback",upload());String packageId=export();call("deposit.set.namespace",m("name",name+"-renamed"),token);
        String rolledBackNamespace;List<String> rolledBackResources;
        try(var session=driver.session();var transaction=session.beginTransaction()){
            var cloned=transaction.run("CYPHER 25 CALL deposit.import.namespaceData($package,$token)",m("package",packageId,"token",token)).single().get("data");
            rolledBackNamespace=cloned.get("namespaceId").asString();
            rolledBackResources=cloned.get("resourceMapping").asMap().values().stream().map(String.class::cast).toList();
            assertFalse(rolledBackResources.isEmpty());
            assertEquals(1,transaction.run("MATCH (n:Namespace {id:$ns}) RETURN count(n) AS n",m("ns",rolledBackNamespace)).single().get("n").asLong());
            assertEquals(rolledBackResources.size(),transaction.run("MATCH (r:DResource) WHERE r.id IN $ids RETURN count(r) AS n",m("ids",rolledBackResources)).single().get("n").asLong());
            transaction.rollback();
        }
        assertEquals(0,scalar("MATCH (n:Namespace {id:$ns}) RETURN count(n) AS n",m("ns",rolledBackNamespace)));
        assertEquals(0,scalar("MATCH (n {namespaceId:$ns}) RETURN count(n) AS n",m("ns",rolledBackNamespace)));
        assertEquals(0,scalar("MATCH (r:DResource) WHERE r.id IN $ids RETURN count(r) AS n",m("ids",rolledBackResources)));
        assertEquals(0,scalar("MATCH (r:DepositResourceLocation) WHERE r.id IN $ids RETURN count(r) AS n",m("ids",rolledBackResources)));
        assertEquals(0,scalar("MATCH (:Descriptor)-[edge:HAS_RESOURCE]->(r) WHERE r.id IN $ids RETURN count(edge) AS n",m("ids",rolledBackResources)));
        var retried=call("deposit.import.namespaceData",packageId,token).get("data");String retriedNamespace=retried.get("namespaceId").asString();assertNotEquals(rolledBackNamespace,retriedNamespace);select(retriedNamespace);
        for(Object raw:retried.get("resourceMapping").asMap().values()){String resource=(String)raw;assertFalse(rolledBackResources.contains(resource));assertBytes(resource);}
    }

    /** Root runs these only while the explicit test probe package is installed. */
    @Test void probeRejectsCapabilityMisuseWithoutPersistingGraph(){
        schema();String object=object("probe",upload());String actor=query("MATCH (u:User {username:'typed-data-admin'}) RETURN u.id AS id",m()).getFirst().get("id").asString();String other=receiver();select(namespace);
        long before=scalar("MATCH (n {namespaceId:$ns}) RETURN count(n) AS n",m("ns",namespace));
        try(var session=driver.session();var transaction=session.beginTransaction()){
            var row=transaction.run("CALL testprobe.storage.importAuthority($ns,$other,$actor,$object)",m("ns",namespace,"other",other,"actor",actor,"object",object)).single();assertEquals(10,row.get("data").get("refusals").asLong());assertTrue(row.get("data").get("sharedAttached").asBoolean());transaction.rollback();
        }
        assertEquals(before,scalar("MATCH (n {namespaceId:$ns}) RETURN count(n) AS n",m("ns",namespace)));
    }

    @Test void probePublicationFailureRollsBackEntireCloneThenRetrySucceeds(){
        schema();object("failure",upload());String packageId=export();call("deposit.set.namespace",m("name",name+"-renamed"),token);
        long before=scalar("MATCH (n:Namespace) RETURN count(n) AS n",m());call("testprobe.storage.failNextPublication");
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.import.namespaceData",packageId,token));assertTrue(failure.getMessage().contains("RESOURCE_CORRUPT"),failure.getMessage());
        assertEquals(before,scalar("MATCH (n:Namespace) RETURN count(n) AS n",m()));assertEquals(0,scalar("MATCH (n:Namespace {name:$name}) RETURN count(n) AS n",m("name",name)));
        var cloned=call("deposit.import.namespaceData",packageId,token).get("data");select(cloned.get("namespaceId").asString());assertBytes(cloned.get("resourceMapping").asMap().values().iterator().next().toString());
    }
}
