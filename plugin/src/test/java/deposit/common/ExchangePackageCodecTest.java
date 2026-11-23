package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import deposit.service.PatternService;

class ExchangePackageCodecTest {
    @TempDir Path temp;

    @Test void workspaceSmallRecordsAvoidDiskFieldProbesWithoutLosingDuplicateChecks() throws Exception {
        var json=new StringBuilder("{\"rows\":[");
        for(int i=0;i<5000;i++){
            if(i>0)json.append(',');
            json.append("{\"kind\":\"Object\",\"id\":").append(i).append(",\"value\":{\"nested\":").append(i).append("}}");
        }
        json.append("],\"large\":{");
        for(int i=0;i<34;i++){
            if(i>0)json.append(',');
            json.append("\"field").append(i).append("\":").append(i);
        }
        json.append("},\"").append("x".repeat(257)).append("\":true}");
        try(var workspace=new PackageWorkspace(temp)){
            var root=workspace.parse(new java.io.StringReader(json.toString()));
            var rows=(List<?>)root.get("rows");
            assertEquals(5000,rows.size());
            assertEquals(4999L,ValueCodec.map(rows.get(4999)).get("id"));
            assertEquals(4999L,ValueCodec.map(ValueCodec.map(rows.get(4999)).get("value")).get("nested"));
            var large=ValueCodec.map(root.get("large"));
            assertEquals(0L,large.get("field0"));
            assertEquals(33L,large.get("field33"));
            assertNull(large.get("absent"));
            assertEquals(true,root.get("x".repeat(257)));
            assertTrue(workspace.probeCount()<1000,"small row properties should not enter the disk field index");
        }
        for(int count:List.of(2,33,34)){
            var object=new StringBuilder("{");
            for(int i=0;i<count;i++){
                if(i>0)object.append(',');
                object.append("\"field").append(i==count-1?0:i).append("\":").append(i);
            }
            object.append('}');
            try(var workspace=new PackageWorkspace(temp)){
                assertThrows(java.io.IOException.class,()->workspace.parse(new java.io.StringReader(object.toString())));
            }
        }
    }


    @Test void packageWriterCreatesValidPortableArchive() throws Exception {
        Path archive=temp.resolve("package.zip");
        ExchangePackageCodec.write(archive,manifest(),graph(),null,Map.of());
        var decoded=ExchangePackageCodec.validate(archive);
        assertEquals("namespace",decoded.manifest().get("scope"));
        assertEquals(List.of(),decoded.graph().get("objects"));
        assertNull(decoded.history());
        assertEquals("namespace",ExchangePackageCodec.validateSummary(archive).scope());
    }

    @Test void namespaceUserToolSurvivesStreamingValidationAndStrictlyBindsToSource() throws Exception {
        var manifest=manifest();String namespace=(String)manifest.get("sourceNamespaceId"),object=UUID.randomUUID().toString(),clazz=UUID.randomUUID().toString(),membership=UUID.randomUUID().toString();
        var graph=graph();graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",namespace,"name","Specimen","descriptors",List.of())));graph.put("objects",List.of(Json.map("kind","Object","id",object,"namespaceId",namespace,"classId",clazz)));graph.put("memberships",List.of(Json.map("kind","Membership","id",membership,"namespaceId",namespace,"objectId",object,"classId",clazz)));graph.put("userTools",List.of(Json.map("kind","UserTool","id",UUID.randomUUID().toString(),"namespaceId",namespace,"name","NetworkX tool","data","retain opaque data")));
        Path valid=temp.resolve("networkx-current-data.zip");ExchangePackageCodec.write(valid,manifest,graph,null,Map.of());
        var decoded=ExchangePackageCodec.validate(valid);var tool=ValueCodec.map(((List<?>)decoded.graph().get("userTools")).getFirst());assertEquals("NetworkX tool",tool.get("name"));assertEquals("retain opaque data",tool.get("data"));
        ExchangePackageCodec.withValidatedPackage(valid,()->{},pkg->{assertEquals(tool,((List<?>)pkg.graph().get("userTools")).getFirst());return null;});
        assertEquals(1L,ExchangePackageCodec.validateSummary(valid).counts().get("userTools"));
        var subgraph=new LinkedHashMap<>(manifest);
        subgraph.put("scope","subgraph");subgraph.put("rootIds",List.of(object));
        Path rejected=temp.resolve("subgraph-with-user-tool.zip");
        ExchangePackageCodec.write(rejected,subgraph,graph,null,Map.of());
        assertAll(
                ()->assertEquals("Subgraph packages cannot contain User Tools",assertThrows(java.io.IOException.class,
                        ()->ExchangePackageCodec.validate(rejected)).getMessage()),
                ()->assertEquals("Subgraph packages cannot contain User Tools",assertThrows(java.io.IOException.class,
                        ()->ExchangePackageCodec.withValidatedPackage(rejected,()->{},pkg->null)).getMessage()));
        var escaped=new LinkedHashMap<>(manifest);escaped.put("sourceNamespaceId",UUID.randomUUID().toString());Path mismatch=temp.resolve("networkx-escaped-tool.zip");ExchangePackageCodec.write(mismatch,escaped,graph,null,Map.of());assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(mismatch));
        var malformed=new LinkedHashMap<String,Object>(tool);malformed.put("unexpected",true);graph.put("userTools",List.of(malformed));Path unknownField=temp.resolve("networkx-malformed-tool.zip");ExchangePackageCodec.write(unknownField,manifest,graph,null,Map.of());assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(unknownField));
    }
    @Test void depositVersionMayBeNullOrAValidServerVersionButNotEmptyOrMalformed() throws Exception {
        var detached=manifest();detached.put("depositVersion",null);
        Path detachedArchive=temp.resolve("detached.zip");ExchangePackageCodec.write(detachedArchive,detached,graph(),null,Map.of());
        assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(detachedArchive));
        var serverPackage=manifest();serverPackage.put("depositVersion","2.4.1-rc.2");
        Path serverArchive=temp.resolve("server.zip");ExchangePackageCodec.write(serverArchive,serverPackage,graph(),null,Map.of());
        assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(serverArchive));
        var empty=manifest();empty.put("depositVersion","");reject(empty,graph(),null);
        var malformed=manifest();malformed.put("depositVersion","unknown");reject(malformed,graph(),null);
    }

    @Test void manifestRejectsProducerAndNamespaceCreationProvenance() throws Exception {
        var producer=manifest();producer.put("producerComponent","server");reject(producer,graph(),null);
        var namespaceVersion=manifest();namespaceVersion.put("namespaceDepositVersion","1.0.0");reject(namespaceVersion,graph(),null);
    }

    @Test void sharedPythonTransportFixturesLeaveDomainAcceptanceToServer() throws Exception {
        Path fixturePath=Path.of("..","..","test_harness","deposit-python","fixtures","stage3_server_semantic_boundary.json");
        assertTrue(Files.isRegularFile(fixturePath),"Shared cross-language semantic fixture is missing: "+fixturePath);
        Map<String,Object> fixture=Json.read(Files.readString(fixturePath));
        Map<String,Object> baseManifest=ValueCodec.map(fixture.get("manifest"));
        for(Object raw:(List<?>)fixture.get("scenarios")) {
            Map<String,Object> scenario=ValueCodec.map(raw);
            var sourceManifest=new LinkedHashMap<>(baseManifest);
            sourceManifest.putAll(ValueCodec.map(scenario.getOrDefault("manifest",Map.of())));
            Map<String,Object> sourceGraph=ValueCodec.map(scenario.get("graph"));
            Map<String,Object> history=scenario.containsKey("history")?ValueCodec.map(scenario.get("history")):null;
            Path archive=temp.resolve((String)scenario.get("name")+".zip");
            ExchangePackageCodec.write(archive,sourceManifest,sourceGraph,history,Map.of());
            if(Boolean.TRUE.equals(scenario.get("validOnServer"))) {
                assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(archive), (String)scenario.get("name"));
                assertDoesNotThrow(()->ExchangePackageCodec.validate(archive), (String)scenario.get("name"));
            } else {
                assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validateSummary(archive), (String)scenario.get("name"));
                assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(archive), (String)scenario.get("name"));
            }
        }
    }

    @Test void archiveTraversalIsRejected() throws Exception {
        Path archive=temp.resolve("unsafe.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../escape"));zip.write(1);zip.closeEntry();
        }
        assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(archive));
    }

    @Test void contentAddressCannotBeBypassedByUpdatingManifestChecksum() throws Exception {
        Path archive=temp.resolve("wrong-address.zip");
        writeFixture(archive,manifest(),graph(),Map.of("resources/"+"0".repeat(64),new byte[]{1,2,3}));
        var error=assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(archive));
        assertTrue(error.getMessage().contains("content-addressed"),error.getMessage());
    }

    @Test void historyOnlyResourceContentMustMatchItsContentAddressedEntry()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId"),resourceId=UUID.randomUUID().toString(),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString(),descriptor=UUID.randomUUID().toString();byte[] content="historical resource payload".getBytes(StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(content);Path source=temp.resolve("history-resource.bin");Files.write(source,content);
        var currentGraph=graph();currentGraph.put("resources",List.of(Json.map("kind","DResource","id",resourceId,"filename","history-resource.bin","bytes",(long)content.length,"mediaType","application/octet-stream","sha256",checksum,"entry","resources/"+checksum)));
        var images=List.of(historicalImage(ns,"Namespace",ns,"name","N","description",""),historicalImage(ns,"Class",clazz,"name","Thing"),historicalImage(ns,"Object",object),historicalImage(ns,"Membership",UUID.randomUUID().toString(),"objectId",object,"classId",clazz),historicalImage(ns,"ClassDescriptor",UUID.randomUUID().toString(),"classId",clazz,"name","File","type","DResource"),historicalImage(ns,"Descriptor",descriptor,"objectId",object,"name","File","value",Json.map("resourceId",resourceId)));
        var history=bundle(ns,images);Path valid=temp.resolve("history-only-resource-valid.zip");ExchangePackageCodec.write(valid,manifest,currentGraph,history,Map.of(checksum,source));assertTrue(((List<?>)currentGraph.get("descriptors")).isEmpty());assertDoesNotThrow(()->ExchangePackageCodec.validate(valid));
        Path corrupted=temp.resolve("history-only-resource-corrupt.zip");rewriteCorruptResource(valid,corrupted,"resources/"+checksum);var error=assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(corrupted));assertTrue(error.getMessage().contains("content-addressed"),error.getMessage());
    }

    private void rewriteCorruptResource(Path source,Path destination,String corruptEntry)throws Exception{
        try(var input=new ZipFile(source.toFile());var output=new ZipOutputStream(Files.newOutputStream(destination))){var entries=input.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();byte[] bytes;try(var content=input.getInputStream(entry)){bytes=content.readAllBytes();}if(entry.getName().equals(corruptEntry)){assertFalse(bytes.length==0);bytes[0]^=1;}output.putNextEntry(new ZipEntry(entry.getName()));output.write(bytes);output.closeEntry();}}
    }

    @Test void duplicateArchiveEntriesAreRejected() throws Exception {
        Path archive=temp.resolve("duplicates.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){
            for(String name:List.of("graph.json","graqh.json")){
                zip.putNextEntry(new ZipEntry(name));zip.write("{}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        byte[] bytes=Files.readAllBytes(archive),oldName="graqh.json".getBytes(StandardCharsets.UTF_8),newName="graph.json".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<=bytes.length-oldName.length;i++)if(Arrays.equals(Arrays.copyOfRange(bytes,i,i+oldName.length),oldName))System.arraycopy(newName,0,bytes,i,newName.length);
        Files.write(archive,bytes);
        var error=assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(archive));
        assertTrue(error.getMessage().contains("Duplicate"),error.getMessage());
    }

    @Test void identicalUuidAcrossConcreteCategoriesIsValid() throws Exception {
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),shared=UUID.randomUUID().toString();var graph=graph();
        var declaration=declaration(ns,shared,"Unused","String");declaration.put("id",shared);graph.put("classes",List.of(Json.map("kind","Class","id",shared,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration))));
        graph.put("objects",List.of(Json.map("kind","Object","id",shared,"namespaceId",ns,"classId",shared)));
        graph.put("memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",shared,"classId",shared)));
        Path archive=temp.resolve("qualified-identity.zip");writeFixture(archive,manifest,graph,Map.of());
        assertDoesNotThrow(()->ExchangePackageCodec.validate(archive));assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(archive));
    }

    @Test void scientificIntegerRequiresDecimalStringUsingIncludedDeclaration() throws Exception {
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString();var graph=graph();
        graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration(ns,clazz,"Count","Integer")))));
        graph.put("objects",List.of(Json.map("kind","Object","id",object,"namespaceId",ns,"classId",clazz)));
        graph.put("memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"classId",clazz)));
        var descriptor=Json.map("kind","Descriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"name","Count","value",Long.toString(Long.MIN_VALUE));graph.put("descriptors",List.of(descriptor));
        Path valid=temp.resolve("integer-string.zip");writeFixture(valid,manifest,graph,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validate(valid));assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(valid));
        descriptor.put("value",Long.MIN_VALUE);Path invalid=temp.resolve("integer-number.zip");writeFixture(invalid,manifest,graph,Map.of());assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(invalid));
    }

    @Test void integerEncodingPreservesSigned64BitValuesAndNull() {
        assertEquals("-9223372036854775808",ExchangePackageCodec.encodeValue("Integer",Long.MIN_VALUE));
        assertNull(ExchangePackageCodec.encodeValue("Integer",null));
    }

    @Test void patternNormalizationUsesDescriptorListAsMatchingScope() {
        var role=Json.map("className","Specimen","root",true);
        var definition=PatternService.normalizeDefinition(Json.map("objects",Json.map("specimen",role)));
        var normalized=ValueCodec.map(ValueCodec.map(definition.get("objects")).get("specimen"));
        assertFalse(normalized.containsKey("matchDescriptors"));
        assertEquals(false,normalized.get("alwaysCreate"));
        role.put("matchDescriptors",List.of());
        assertThrows(ExceptionHandler.DepositException.class,()->PatternService.normalizeDefinition(Json.map("objects",Json.map("specimen",role))));
    }

    @Test void graphRejectsMissingMembershipAbsentValueAndProhibitedEmptyLists()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString();var graph=graph();
        graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration(ns,clazz,"Values","List")))));
        var record=Json.map("kind","Object","id",object,"namespaceId",ns);graph.put("objects",List.of(record));reject(manifest,graph,null);
        record.put("classId",clazz);graph.put("memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"classId",clazz)));
        var descriptor=Json.map("kind","Descriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"name","Values");graph.put("descriptors",List.of(descriptor));reject(manifest,graph,null);
        for(String item:List.of("DGeometry","DResource")){descriptor.put("value",Json.map("itemType",item,"items",List.of()));reject(manifest,graph,null);}
        descriptor.put("value",null);Path valid=temp.resolve(UUID.randomUUID()+".zip");ExchangePackageCodec.write(valid,manifest,graph,null,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(valid));
    }
    private Map<String,Object> historicalImage(String ns,String kind,String id,Object... fields){var image=Json.map("namespaceId",ns,"kind",kind,"id",id);image.putAll(Json.map(fields));return image;}
    private Map<String,Object> bundle(String ns,List<Map<String,Object>> images){String epoch=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();var changes=images.stream().map(image->Json.map("entityKind",image.get("kind"),"entityId",image.get("id"),"before",null,"after",image)).toList();var baseline=Json.map("id",id,"revisionId",id,"namespaceId",ns,"historyEpochId",epoch,"sequence",0L,"kind","baseline","parentRevisionId",null,"changes",changes,"touches",Map.of(),"dependencies",List.of());var envelope=Json.map("id",epoch,"mode","persistent","closed",false,"startedSequence",0L,"endedSequence",null,"baselineRevisionId",id,"headRevisionId",id,"baseline",baseline,"revisions",List.of(baseline));return Json.map("namespaceId",ns,"historyMode","persistent","activeEpochId",epoch,"headRevisionId",id,"epochs",List.of(envelope),"complete",true);}
    @Test void sharedRevisionSpoolExportsClosedAndActiveEpochsWithIndependentBaselines()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId");
        var root=historicalImage(ns,"Namespace",ns,"name","Scientific namespace","description","");
        var closed=bundle(ns,List.of(root));var closedEpoch=ValueCodec.map(((List<?>)closed.get("epochs")).getFirst());closedEpoch.put("closed",true);closedEpoch.put("endedSequence",1L);
        var active=bundle(ns,List.of(root));var activeEpoch=ValueCodec.map(((List<?>)active.get("epochs")).getFirst());activeEpoch.put("startedSequence",2L);
        var activeBaseline=ValueCodec.map(activeEpoch.get("baseline"));activeBaseline.put("sequence",2L);
        try(var records=new ExportRecords(temp)){
            records.addRevision("history-revisions",ValueCodec.map(closedEpoch.get("baseline")));
            var first=records.revisions("history-revisions",0,1);closedEpoch.put("baseline",first.getFirst());closedEpoch.put("revisions",first);
            records.addRevision("history-revisions",activeBaseline);
            var second=records.revisions("history-revisions",1,2);activeEpoch.put("baseline",second.getFirst());activeEpoch.put("revisions",second);
            active.put("epochs",List.of(closedEpoch,activeEpoch));Path archive=temp.resolve("closed-and-active.zip");
            ExchangePackageCodec.write(archive,manifest,graph(),active,Map.of());
            assertDoesNotThrow(()->ExchangePackageCodec.validate(archive));
            assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(archive));
            var incorrectlyShared=new LinkedHashMap<>(activeEpoch);incorrectlyShared.put("revisions",records.revisions("history-revisions"));
            var invalid=new LinkedHashMap<>(active);invalid.put("epochs",List.of(closedEpoch,incorrectlyShared));
            Path bad=temp.resolve("shared-epochs.zip");ExchangePackageCodec.write(bad,manifest,graph(),invalid,Map.of());
            var failure=assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validateSummary(bad));assertTrue(failure.getMessage().contains("baseline"),failure.getMessage());
        }
    }
    private Map<String,Object> onlyEpoch(Map<String,Object> history){return ValueCodec.map(((List<?>)history.get("epochs")).getFirst());}
    @Test void historyUsesHistoricalDeclarationsLargeScientificStringsAndCompleteBeforeStates()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString(),descriptor=UUID.randomUUID().toString();String value=" Unicode ? ".repeat(5000);
        var images=new ArrayList<Map<String,Object>>(List.of(historicalImage(ns,"Namespace",ns,"name","N"),historicalImage(ns,"Class",clazz,"name","Thing"),historicalImage(ns,"Object",object),historicalImage(ns,"Membership",UUID.randomUUID().toString(),"objectId",object,"classId",clazz),historicalImage(ns,"ClassDescriptor",UUID.randomUUID().toString(),"classId",clazz,"name","Values","type","List"),historicalImage(ns,"Descriptor",descriptor,"objectId",object,"name","Values","value",Json.map("itemType","String","items",Arrays.asList(value,null)))));
        var history=bundle(ns,images);Path valid=temp.resolve("large-historical-string.zip");ExchangePackageCodec.write(valid,manifest,graph(),history,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(valid));
        var epoch=onlyEpoch(history);var baseline=ValueCodec.map(epoch.get("baseline"));var changedBaseline=new LinkedHashMap<>(baseline);changedBaseline.put("kind","edit");epoch.put("baseline",changedBaseline);reject(manifest,graph(),history);
        history=bundle(ns,images);ValueCodec.map(onlyEpoch(history).get("baseline")).put("namespaceId",UUID.randomUUID().toString());reject(manifest,graph(),history);
        images.get(1).put("id","not-a-uuid");reject(manifest,graph(),bundle(ns,images));
    }
    @Test void historicalPatternsMustBeCompatibleEvenWhenCurrentGraphHasNone()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString();var images=new ArrayList<Map<String,Object>>(List.of(historicalImage(ns,"Namespace",ns,"name","N"),historicalImage(ns,"Class",clazz,"name","Thing")));
        images.add(historicalImage(ns,"Pattern",UUID.randomUUID().toString(),"name","One","definition",Json.map("objects",Json.map("root",Json.map("className","Thing","root",true)))));
        images.add(historicalImage(ns,"Pattern",UUID.randomUUID().toString(),"name","Two","definition",Json.map("objects",Json.map("root",Json.map("className","Thing","root",true,"alwaysCreate",true)))));
        reject(manifest,graph(),bundle(ns,images));
    }
    @Test void packagePatternsUseLookupSchemaForPresentAndAbsentClassesAndDeclarations()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString();var graph=graph();
        graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration(ns,clazz,"Code","String")))));
        var pattern=Json.map("kind","Pattern","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","ByCode","definition",Json.map("objects",Json.map("root",Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Code"))))));
        graph.put("patterns",List.of(pattern));Path valid=temp.resolve("pattern-lookup.zip");ExchangePackageCodec.write(valid,manifest,graph,null,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validate(valid));
        var missingClass=graph();missingClass.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration(ns,clazz,"Code","String")))));pattern.put("definition",Json.map("objects",Json.map("root",Json.map("className","Absent","root",true))));missingClass.put("patterns",List.of(pattern));reject(manifest,missingClass,null);
        pattern.put("definition",Json.map("objects",Json.map("root",Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Absent"))))));missingClass.put("patterns",List.of(pattern));reject(manifest,missingClass,null);
        pattern.put("definition",Json.map("objects",Json.map("root",Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Absent","type","String"))))));missingClass.put("patterns",List.of(pattern));Path proposed= temp.resolve("pattern-missing-declaration-with-proposal.zip");ExchangePackageCodec.write(proposed,manifest,missingClass,null,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validate(proposed));
    }
    @Test void malformedMetadataAndOversizedRoleNamesAreRejected()throws Exception{
        var manifest=manifest();manifest.put("exportedRevision",1.5);reject(manifest,graph(),null);
        manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString();var graph=graph();graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of())));graph.put("patterns",List.of(Json.map("kind","Pattern","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","Oversized","definition",Json.map("objects",Json.map("x".repeat(40000),Json.map("className","Thing","root",true))))));reject(manifest,graph,null);
    }
    @Test void nestedDeclarationsRejectWrongKindsOwnersDuplicateIdsAndInternalFields()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString();
        for(String defect:List.of("kind","owner","duplicate","internal")){
            var declaration=declaration(ns,clazz,"Count","Integer");var rows=new ArrayList<Map<String,Object>>();rows.add(declaration);
            switch(defect){case "kind"->declaration.put("kind","Object");case "owner"->declaration.put("classId",UUID.randomUUID().toString());case "duplicate"->{var second=new LinkedHashMap<>(declaration);second.put("name","Other");rows.add(second);}case "internal"->declaration.put("storedFilename","absolute internal path");}
            var graph=graph();graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",rows)));reject(manifest,graph,null);
        }
    }
    @Test void declarationDirectedDictResourceIdKeyIsNotAResourceAndDuplicateFieldsFail()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString();var graph=graph();
        graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(declaration(ns,clazz,"Dict","DDict")))));
        graph.put("objects",List.of(Json.map("kind","Object","id",object,"namespaceId",ns,"classId",clazz)));graph.put("memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"classId",clazz)));
        var descriptor=Json.map("kind","Descriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",object,"name","Dict","value",Json.map("resourceId",Json.map("type","String","value","opaque dictionary entry"),"revisionId",Json.map("type","String","value","opaque dictionary entry"),"changes",Json.map("type","String","value","opaque dictionary entry"),"touches",Json.map("type","String","value","opaque dictionary entry"),"dependencies",Json.map("type","String","value","opaque dictionary entry")));
        graph.put("descriptors",List.of(descriptor));Path valid=temp.resolve("dictionary-resourceId-key.zip");ExchangePackageCodec.write(valid,manifest,graph,null,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(valid));assertDoesNotThrow(()->ExchangePackageCodec.validate(valid));
        String other=UUID.randomUUID().toString();graph.put("objects",List.of(ValueCodec.map(((List<?>)graph.get("objects")).getFirst()),Json.map("kind","Object","id",other,"namespaceId",ns,"classId",clazz)));graph.put("memberships",List.of(ValueCodec.map(((List<?>)graph.get("memberships")).getFirst()),Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",other,"classId",clazz)));
        var duplicate=new LinkedHashMap<>(descriptor);duplicate.put("id",UUID.randomUUID().toString());duplicate.put("objectId",other);graph.put("descriptors",List.of(descriptor,duplicate));Path sharedName=temp.resolve("distinct-owners-same-field.zip");ExchangePackageCodec.write(sharedName,manifest,graph,null,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(sharedName));duplicate.put("objectId",object);reject(manifest,graph,null);
    }
    @Test void uuidCaseUsesCategoryQualifiedCanonicalValidationWithoutChangingPayload()throws Exception{
        var manifest=manifest();String ns=(String)manifest.get("sourceNamespaceId"),id="abcdefab-cdef-4abc-8def-abcdefabcdef";var graph=graph();
        graph.put("classes",List.of(Json.map("kind","Class","id",id.toUpperCase(Locale.ROOT),"namespaceId",ns.toUpperCase(Locale.ROOT),"name","Thing","descriptors",List.of(declaration(ns,id,"Text","String")))));
        graph.put("objects",List.of(Json.map("kind","Object","id",id,"namespaceId",ns,"classId",id)));
        graph.put("memberships",List.of(Json.map("kind","Membership","id",UUID.randomUUID().toString(),"namespaceId",ns,"objectId",id.toUpperCase(Locale.ROOT),"classId",id)));
        graph.put("patterns",List.of(Json.map("kind","Pattern","id",UUID.randomUUID().toString(),"namespaceId",ns,"name","TouchesRole","definition",Json.map("objects",Json.map("touches",Json.map("className","Thing","root",true))))));
        Path path=temp.resolve("mixed-case.zip");ExchangePackageCodec.write(path,manifest,graph,null,Map.of());
        var decoded=ExchangePackageCodec.validate(path);assertEquals(id.toUpperCase(Locale.ROOT),ValueCodec.map(((List<?>)decoded.graph().get("classes")).getFirst()).get("id"));assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(path));
        graph.put("objects",List.of(Json.map("kind","Object","id",id,"namespaceId",ns,"classId",id),Json.map("kind","Object","id",id.toUpperCase(Locale.ROOT),"namespaceId",ns,"classId",id)));reject(manifest,graph,null);
        manifest.put("packageId","1-1-1-1-1");reject(manifest,graph(),null);
    }
    @Test void nestedDeclarationAndHistoricalIdentitiesCannotDuplicateByCase()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId"),id="abcdefab-cdef-4abc-8def-abcdefabcdef",clazz=UUID.randomUUID().toString();var graph=graph();
        var first=declaration(ns,clazz,"First","String");first.put("id",id);var second=declaration(ns,clazz,"Second","String");second.put("id",id.toUpperCase(Locale.ROOT));
        graph.put("classes",List.of(Json.map("kind","Class","id",clazz,"namespaceId",ns,"name","Thing","descriptors",List.of(first,second))));reject(manifest,graph,null);
        var images=List.of(historicalImage(ns,"Namespace",ns,"name","N"),historicalImage(ns,"Class",id,"name","One"),historicalImage(ns,"Class",id.toUpperCase(Locale.ROOT),"name","Two"));reject(manifest,graph(),bundle(ns,images));
    }
    @Test void historyCanonicalizesAuditAndJournalIdsButPreservesScientificStrings()throws Exception{
        var manifest=manifest();manifest.put("historyMode","persistent");String ns=(String)manifest.get("sourceNamespaceId"),clazz=UUID.randomUUID().toString(),object=UUID.randomUUID().toString(),descriptor=UUID.randomUUID().toString(),actor="abcdefab-cdef-4abc-8def-abcdefabcdef";
        var image=historicalImage(ns,"Descriptor",descriptor,"objectId",object,"name","Text","value",actor,"createdBy",actor);
        var images=List.of(historicalImage(ns,"Namespace",ns,"name","N"),historicalImage(ns,"Class",clazz,"name","Thing"),historicalImage(ns,"Object",object),historicalImage(ns,"Membership",UUID.randomUUID().toString(),"objectId",object,"classId",clazz),historicalImage(ns,"ClassDescriptor",UUID.randomUUID().toString(),"classId",clazz,"name","Text","type","String"),image);
        var history=bundle(ns,images);var epoch=onlyEpoch(history);var baseline=ValueCodec.map(epoch.get("baseline"));String key=HistoryCodec.key("Descriptor",descriptor);baseline.put("touches",Map.of(key,List.of("value")));baseline.put("dependencies",List.of(key));
        var alternate=new LinkedHashMap<>(baseline);alternate.put("revisionId",((String)baseline.get("revisionId")).toUpperCase(Locale.ROOT));alternate.put("touches",Map.of(HistoryCodec.key("Descriptor",descriptor.toUpperCase(Locale.ROOT)),List.of("value")));alternate.put("dependencies",List.of(HistoryCodec.key("Descriptor",descriptor.toUpperCase(Locale.ROOT))));epoch.put("baselineRevisionId",alternate.get("revisionId"));
        var before=new LinkedHashMap<>(image);before.put("id",descriptor.toUpperCase(Locale.ROOT));before.put("createdBy",actor.toUpperCase(Locale.ROOT));var after=new LinkedHashMap<>(image);after.put("value","changed");
        var revision=new LinkedHashMap<>(baseline);String next=UUID.randomUUID().toString();revision.put("id",next);revision.put("revisionId",next);revision.put("kind","edit");revision.put("sequence",1L);revision.put("parentRevisionId",((String)baseline.get("revisionId")).toUpperCase(Locale.ROOT));revision.put("changes",List.of(Json.map("entityKind","Descriptor","entityId",descriptor.toUpperCase(Locale.ROOT),"before",before,"after",after)));epoch.put("revisions",List.of(alternate,revision));epoch.put("headRevisionId",next);history.put("headRevisionId",next);
        Path valid=temp.resolve("history-mixed-case.zip");ExchangePackageCodec.write(valid,manifest,graph(),history,Map.of());assertDoesNotThrow(()->ExchangePackageCodec.validate(valid));assertDoesNotThrow(()->ExchangePackageCodec.validateSummary(valid));
        before.put("value",actor.toUpperCase(Locale.ROOT));reject(manifest,graph(),history);before.put("value",actor);
        var duplicate=new LinkedHashMap<String,Object>();duplicate.put(key,List.of("value"));duplicate.put(HistoryCodec.key("Descriptor",descriptor.toUpperCase(Locale.ROOT)),List.of("value"));revision.put("touches",duplicate);reject(manifest,graph(),history);revision.put("touches",Map.of());
        revision.put("changes",List.of(Json.map("entityKind","Object","entityId","1-1-1-1-1","before",null,"after",null)));reject(manifest,graph(),history);
    }
    private void reject(Map<String,Object> manifest,Map<String,Object> graph,Map<String,Object> history)throws Exception{Path path=temp.resolve(UUID.randomUUID()+".zip");ExchangePackageCodec.write(path,manifest,graph,history,Map.of());assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validateSummary(path));assertThrows(java.io.IOException.class,()->ExchangePackageCodec.validate(path));}

    private Map<String,Object> declaration(String ns,String clazz,String name,String type){return Json.map("kind","ClassDescriptor","id",UUID.randomUUID().toString(),"namespaceId",ns,"classId",clazz,"name",name,"type",type);}
    private Map<String,Object> manifest(){return Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",UUID.randomUUID().toString(),"name","empty","description","","depositVersion","2.0.0","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");}
    private Map<String,Object> graph(){return Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());}
    private void writeFixture(Path archive,Map<String,Object> sourceManifest,Map<String,Object> graph,Map<String,byte[]> extra)throws Exception{
        var entries=new TreeMap<String,byte[]>(extra);entries.put("graph.json",Json.write(graph).getBytes(StandardCharsets.UTF_8));
        var files=new TreeMap<String,Object>();for(var entry:entries.entrySet())files.put(entry.getKey(),Json.map("bytes",(long)entry.getValue().length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        var manifest=new LinkedHashMap<>(sourceManifest);manifest.put("files",files);manifest.put("includeHistory",false);entries.put("manifest.json",Json.write(manifest).getBytes(StandardCharsets.UTF_8));
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
    }
}
