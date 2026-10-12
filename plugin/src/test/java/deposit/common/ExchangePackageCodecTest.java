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


    @Test void packageWriterCreatesValidPortableArchive() throws Exception {
        Path archive=temp.resolve("package.zip");
        ExchangePackageCodec.write(archive,manifest(),graph(),null,Map.of());
        var decoded=ExchangePackageCodec.validate(archive);
        assertEquals("namespace",decoded.manifest().get("scope"));
        assertEquals(List.of(),decoded.graph().get("objects"));
        assertNull(decoded.history());
        assertEquals("namespace",ExchangePackageCodec.validateSummary(archive).scope());
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
    private Map<String,Object> manifest(){return Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",UUID.randomUUID().toString(),"name","empty","description","","depositVersion","test","historyMode","disabled","exportedRevision",0L,"createdAt","2026-01-01T00:00:00Z","rootIds",List.of(),"scope","namespace");}
    private Map<String,Object> graph(){return Json.map("classes",List.of(),"objects",List.of(),"descriptors",List.of(),"geometries",List.of(),"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of(),"resources",List.of());}
    private void writeFixture(Path archive,Map<String,Object> sourceManifest,Map<String,Object> graph,Map<String,byte[]> extra)throws Exception{
        var entries=new TreeMap<String,byte[]>(extra);entries.put("graph.json",Json.write(graph).getBytes(StandardCharsets.UTF_8));
        var files=new TreeMap<String,Object>();for(var entry:entries.entrySet())files.put(entry.getKey(),Json.map("bytes",(long)entry.getValue().length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        var manifest=new LinkedHashMap<>(sourceManifest);manifest.put("files",files);manifest.put("includeHistory",false);entries.put("manifest.json",Json.write(manifest).getBytes(StandardCharsets.UTF_8));
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
    }
}
