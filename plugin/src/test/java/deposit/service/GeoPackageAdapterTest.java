package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.Json;
import deposit.common.ValueCodec;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoPackageAdapterTest {
    @TempDir Path temp;
    @Test void sharedChecksumPreservesPathReadFailureAsIOException()throws Exception {
        Path missing=temp.resolve("absent.gpkg");
        assertThrows(java.io.IOException.class,()->deposit.common.ResourceFiles.checksum(missing));
    }
    @Test void featureLayerRequiresItsSoleRegisteredGeometryAndAnExactSelection()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-registration-");try{
            Path multiple=root.resolve("multiple.gpkg"),out=root.resolve("multiple.json");featureFixture(multiple,true,false,false);assertThrows(java.io.IOException.class,()->load(multiple,out,Json.map("layer","specimens","geometryColumn","geom")));assertFalse(Files.exists(out));
            Path exact=root.resolve("exact.gpkg");featureFixture(exact,false,false,false);assertThrows(java.io.IOException.class,()->load(exact,root.resolve("missing.json"),Json.map("layer","specimens")));assertThrows(java.io.IOException.class,()->load(exact,root.resolve("wrong.json"),Json.map("layer","specimens","geometryColumn","wrong")));
            Path other=root.resolve("other.gpkg"),otherOut=root.resolve("other.json");featureFixture(other,false,true,false);assertEquals(1L,load(other,otherOut,Json.map("layer","specimens","geometryColumn","geom")).get("rowCount"));
        }finally{deleteTree(root);}
    }
    @Test void unresolvedAndMixedGeometrySrsAreReportedWithoutInventingCrs()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-srs-");try{
            Path unresolved=root.resolve("unresolved.gpkg");featureFixture(unresolved,false,false,true);assertThrows(java.io.IOException.class,()->load(unresolved,root.resolve("unresolved.json"),Json.map("layer","specimens","geometryColumn","geom")));
            Path mixed=root.resolve("mixed.gpkg");featureFixture(mixed,false,false,false);try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+mixed);Statement sql=db.createStatement()){sql.execute("INSERT INTO gpkg_spatial_ref_sys VALUES('Test CRS',3857,'EPSG',3857,'PROJCS[\\\"test\\\"]','')");try(PreparedStatement put=db.prepareStatement("INSERT INTO specimens(code,geom) VALUES('wrong-srs',?)")){put.setBytes(1,gpkgPoint(3857));put.executeUpdate();}}
            Path staged=root.resolve("mixed.json");var metadata=load(mixed,staged,Json.map("layer","specimens","geometryColumn","geom"));assertEquals(2L,metadata.get("rowCount"));assertFalse((Boolean)metadata.get("valid"));assertFalse(((List<?>)metadata.get("diagnostics")).isEmpty());
        }finally{deleteTree(root);}
    }
    @Test void attributeLayerRejectsGeometrySelection()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-attributes-");try{Path source=root.resolve("attributes.gpkg");featureFixture(source,false,false,false);try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+source);Statement sql=db.createStatement()){sql.execute("INSERT INTO gpkg_contents(table_name,data_type,identifier,description,last_change,srs_id) VALUES('attributes','attributes','attributes','','2026-01-01T00:00:00.000Z',NULL)");sql.execute("CREATE TABLE attributes(fid INTEGER PRIMARY KEY,code TEXT)");sql.execute("INSERT INTO attributes VALUES(1,'A-1')");}
            assertThrows(java.io.IOException.class,()->load(source,root.resolve("bad.json"),Json.map("layer","attributes","geometryColumn","geom")));assertEquals(1L,load(source,root.resolve("ok.json"),Json.map("layer","attributes")).get("rowCount"));
        }finally{deleteTree(root);}
    }
    @Test void outputSeparatesMixedUnknownAndNullGeometryCrsAndPreservesFieldIdentity()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-split-");try{
            List<Map<String,Object>> columns=List.of(Json.map("name","first","type","DGeometry"),Json.map("name","second","type","DGeometry"));Object xy=geometry("POINT (1 2)",4326L,null,2),projected=geometry("POINT (3 4)",32633L,null,2),z=geometry("POINT Z (5 6 7)",-1L,5703L,3);
            Path table=root.resolve("table.json");Files.writeString(table,Json.write(Json.map("columns",columns,"rows",List.of(Json.map("rowId","r1","values",Json.map("first",xy,"second",projected),"presence",Json.map("first",true,"second",true)),Json.map("rowId","r2","values",Json.map("first",z,"second",null),"presence",Json.map("first",true,"second",true))),"rowCount",2L,"valid",true,"diagnostics",List.of(),"diagnosticsTruncated",false)));
            var outputs=GeoPackageAdapter.write(table,columns,"mixed.gpkg","mixed",root,()->{});assertEquals(3,outputs.size());assertEquals(Set.of("mixed_4326.gpkg","mixed_32633.gpkg","mixed_unknown.gpkg"),outputs.stream().map(GeoPackageAdapter.Output::filename).collect(java.util.stream.Collectors.toSet()));for(var output:outputs){byte[] content=Files.readAllBytes(output.path());assertEquals(content.length,output.bytes());assertEquals(hash(content),output.checksum());}
            var split=outputs.stream().filter(o->o.filename().equals("mixed_4326.gpkg")).findFirst().orElseThrow();try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+split.path());Statement sql=db.createStatement();ResultSet rows=sql.executeQuery("SELECT _deposit_row_id,_deposit_geometry_field FROM mixed")){assertTrue(rows.next());assertEquals("r1",rows.getString(1));assertEquals("first",rows.getString(2));assertFalse(rows.next());}
            var unknown=outputs.stream().filter(o->o.filename().equals("mixed_unknown.gpkg")).findFirst().orElseThrow();assertTrue(unknown.warnings().stream().anyMatch(w->"VERTICAL_CRS_OMITTED".equals(w.get("code"))));try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+unknown.path());Statement sql=db.createStatement();ResultSet rows=sql.executeQuery("SELECT _deposit_geometry_field,geometry FROM mixed ORDER BY fid")){assertTrue(rows.next());assertEquals("first",rows.getString(1));byte[] blob=rows.getBytes(2);assertNotNull(blob);assertEquals('G',blob[0]);assertTrue(rows.next());assertEquals("second",rows.getString(1));assertNull(rows.getBytes(2));assertFalse(rows.next());}
        }finally{deleteTree(root);}
    }
    private static Map<String,Object> load(Path source,Path destination,Map<String,Object> options)throws Exception{return GeoPackageAdapter.load(source,destination,options,Map.of(),Map.of(),".",()->{});}
    private static Object geometry(String wkt,long srid,Long vertical,int dimensions){return Json.map("wkt",wkt,"srid",srid,"verticalSrid",vertical,"axisOrder","xy","dimensions",(long)dimensions);}
    private static byte[] gpkgPoint(int srid){byte[] wkb=HexFormat.of().parseHex("0101000000000000000000F03F0000000000000040"),blob=new byte[8+wkb.length];blob[0]='G';blob[1]='P';blob[2]=0;blob[3]=1;java.nio.ByteBuffer.wrap(blob,4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(srid);System.arraycopy(wkb,0,blob,8,wkb.length);return blob;}
    private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    private static void featureFixture(Path path,boolean multiple,boolean otherLayer,boolean unresolved)throws Exception {try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+path);Statement sql=db.createStatement()){
        sql.execute("PRAGMA application_id=1196437808");sql.execute("PRAGMA user_version=10300");sql.execute("CREATE TABLE gpkg_spatial_ref_sys(srs_name TEXT NOT NULL,srs_id INTEGER NOT NULL PRIMARY KEY,organization TEXT NOT NULL,organization_coordsys_id INTEGER NOT NULL,definition TEXT NOT NULL,description TEXT)");sql.execute("INSERT INTO gpkg_spatial_ref_sys VALUES('Undefined Cartesian',-1,'NONE',-1,'undefined','')");sql.execute("INSERT INTO gpkg_spatial_ref_sys VALUES('Undefined Geographic',0,'NONE',0,'undefined','')");sql.execute("CREATE TABLE gpkg_contents(table_name TEXT NOT NULL PRIMARY KEY,data_type TEXT NOT NULL,identifier TEXT UNIQUE,description TEXT DEFAULT '',last_change TEXT NOT NULL,min_x DOUBLE,min_y DOUBLE,max_x DOUBLE,max_y DOUBLE,srs_id INTEGER)");sql.execute("CREATE TABLE gpkg_geometry_columns(table_name TEXT NOT NULL,column_name TEXT NOT NULL,geometry_type_name TEXT NOT NULL,srs_id INTEGER NOT NULL,z TINYINT NOT NULL,m TINYINT NOT NULL,PRIMARY KEY(table_name,column_name))");sql.execute("INSERT INTO gpkg_contents(table_name,data_type,identifier,description,last_change,srs_id) VALUES('specimens','features','specimens','','2026-01-01T00:00:00.000Z',4326)");if(!unresolved)sql.execute("INSERT INTO gpkg_spatial_ref_sys VALUES('WGS 84',4326,'EPSG',4326,'GEOGCS[WGS 84]','')");sql.execute("CREATE TABLE specimens(fid INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT,geom BLOB)");if(multiple)sql.execute("ALTER TABLE specimens ADD COLUMN geom2 BLOB");sql.execute("INSERT INTO specimens(code,geom) VALUES('A-1',X'47500001E61000000101000000000000000000F03F0000000000000040')");sql.execute("INSERT INTO gpkg_geometry_columns VALUES('specimens','geom','POINT',4326,0,0)");if(multiple)sql.execute("INSERT INTO gpkg_geometry_columns VALUES('specimens','geom2','POINT',4326,0,0)");if(otherLayer){sql.execute("CREATE TABLE other(fid INTEGER PRIMARY KEY,geom BLOB)");sql.execute("INSERT INTO gpkg_contents(table_name,data_type,identifier,description,last_change,srs_id) VALUES('other','features','other','','2026-01-01T00:00:00.000Z',4326)");sql.execute("INSERT INTO gpkg_geometry_columns VALUES('other','geom','POINT',4326,0,0)");}}}
    private static void deleteTree(Path root)throws Exception{try(var files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}}
    @Test void positiveCrsDefinitionsComeFromTheInstalledAuthorityDatabase()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-crs-");try{
            Path table=root.resolve("table.json");List<Map<String,Object>> columns=List.of(Json.map("name","geom","type","DGeometry"));Map<String,Object> geometry=Json.map("wkt","POINT (500000 4649776)","srid",32633L,"verticalSrid",null,"axisOrder","xy","dimensions",2L);
            Files.writeString(table,Json.write(Json.map("columns",columns,"rows",List.of(Json.map("rowId","1","values",Json.map("geom",geometry),"presence",Json.map("geom",true))),"rowCount",1L,"valid",true,"diagnostics",List.of(),"diagnosticsTruncated",false)));
            var result=GeoPackageAdapter.write(table,columns,"projected.gpkg","projected",root,()->{});assertEquals("projected_32633.gpkg",result.getFirst().filename());
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+result.getFirst().path());PreparedStatement query=db.prepareStatement("SELECT srs_name,definition FROM gpkg_spatial_ref_sys WHERE srs_id=32633");ResultSet row=query.executeQuery()){assertTrue(row.next());assertTrue(row.getString("srs_name").contains("32633")||row.getString("srs_name").toLowerCase(Locale.ROOT).contains("utm"));assertTrue(row.getString("definition").contains("PROJCS")||row.getString("definition").contains("PROJCRS"));}
        }finally{try(var files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}}
    }
    @Test void attributeAndFeatureGeoPackagesRoundTripThroughIndependentSqliteReader()throws Exception {
        Path root=Files.createTempDirectory("deposit-gpkg-test");try{
            Path table=root.resolve("table.json");List<Map<String,Object>> columns=List.of(Json.map("name","code","type","String"),Json.map("name","geometryValue","type","DGeometry"));Map<String,Object> geometry=Json.map("geometryType","Point","coordinates",List.of(1.25,2.5),"wkt","POINT (1.25 2.5)","srid",4326L,"verticalSrid",null,"axisOrder","xy","dimensions",2L);
            try(var out=Files.newBufferedWriter(table)){out.write(Json.write(Json.map("columns",columns,"rows",List.of(Json.map("rowId","1","values",Json.map("code","A-1","geometryValue",geometry),"presence",Json.map("code",true,"geometryValue",true))),"rowCount",1L,"valid",true,"diagnostics",List.of(),"diagnosticsTruncated",false)));}
            var result=GeoPackageAdapter.write(table,columns,"result.gpkg","specimens",root,()->{});assertEquals(1,result.size());assertEquals("result_4326.gpkg",result.getFirst().filename());
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+result.getFirst().path());Statement sql=db.createStatement();ResultSet check=sql.executeQuery("PRAGMA integrity_check")){assertTrue(check.next());assertEquals("ok",check.getString(1));}
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+result.getFirst().path());Statement sql=db.createStatement();ResultSet row=sql.executeQuery("SELECT code,_deposit_row_id,_deposit_geometry_field,hex(geometry) AS geometry FROM specimens")){assertTrue(row.next());assertEquals("A-1",row.getString("code"));assertEquals("1",row.getString("_deposit_row_id"));assertEquals("geometryValue",row.getString("_deposit_geometry_field"));assertTrue(row.getString("geometry").startsWith("47500001E6100000"),row.getString("geometry"));}
            Path loaded=root.resolve("loaded.json");var metadata=GeoPackageAdapter.load(result.getFirst().path(),loaded,Json.map("layer","specimens","geometryColumn","geometry"),Map.of(),Map.of(),".",()->{});assertEquals(1L,metadata.get("rowCount"));assertTrue(Boolean.TRUE.equals(metadata.get("valid")));Map<String,Object> staged=Json.read(Files.readString(loaded));Map<String,Object> row=ValueCodec.map(((List<?>)staged.get("rows")).getFirst());Map<String,Object> values=ValueCodec.map(row.get("values"));assertEquals("A-1",values.get("code"));assertEquals("POINT (1.25 2.5)",ValueCodec.map(values.get("geometry")).get("wkt"));
        }finally{try(var files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}}
    }
    @Test void failedLaterPartCleansAllOwnedPrivateOutputs(@org.junit.jupiter.api.io.TempDir Path root)throws Exception {
        Path table=root.resolve("table.json"),sentinel=root.resolve("unrelated.bin");Files.writeString(sentinel,"retain");
        var columns=List.of(Json.map("name","attachment","type","DResource"),Json.map("name","geometryValue","type","DGeometry"));
        var rows=new ArrayList<Map<String,Object>>();
        for(int srid:List.of(3857,4326))rows.add(Json.map("rowId",Integer.toString(srid),
            "values",Json.map("attachment",Json.map("resourceId",UUID.randomUUID().toString()),"geometryValue",Json.map("wkt","POINT (1 2)","srid",srid)),
            "presence",Json.map("attachment",true,"geometryValue",true)));
        Files.writeString(table,Json.write(Json.map("columns",columns,"rows",rows)));
        var rendered=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(deposit.common.ExceptionHandler.DepositException.class,()->GeoPackageAdapter.write(table,columns,"parts.gpkg","data",root,()->{},value->{
            if(rendered.incrementAndGet()==2)throw deposit.common.ExceptionHandler.fail("VALIDATION_FAILED","Resource download listener is unavailable");
            return "[attachment](http://localhost/resource)";
        }));
        assertEquals(2,rendered.get(),"One output finishes before the later part fails");
        try(var files=Files.list(root)){assertEquals(Set.of(table,sentinel),files.collect(java.util.stream.Collectors.toSet()),"Delete only this call's completed and partial private outputs");}
        assertEquals("retain",Files.readString(sentinel));
    }

}
