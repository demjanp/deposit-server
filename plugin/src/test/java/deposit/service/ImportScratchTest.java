package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportScratchTest {
    @TempDir Path temporary;

    @Test void repeatedReadsReuseStatementAndRebindValues()throws Exception {
        try(var scratch=new ImportScratch(temporary,()->{})){
            String sql="SELECT body FROM records WHERE bucket=? AND key=?";
            scratch.update("INSERT INTO records(bucket,key,body) VALUES(?,?,?)",1,"a","first");
            scratch.update("INSERT INTO records(bucket,key,body) VALUES(?,?,?)",2,"a","second");
            var first=scratch.query(sql,1,"a");
            var statement=first.statement;
            assertEquals("first",first.next().get("body"));
            assertTrue(first.closed);
            for(int i=0;i<1000;i++){
                int bucket=i%2+1;
                try(var rows=scratch.query(sql,bucket,"a")){
                    assertSame(statement,rows.statement);
                    assertEquals(bucket==1?"first":"second",rows.next().get("body"));
                }
                assertNull(scratch.one(sql,bucket,"absent"));
            }
        }
    }

    @Test void nestedSameSqlReadDoesNotReplaceOpenCursorResultSet()throws Exception {
        try(var scratch=new ImportScratch(temporary,()->{})){
            for(int i=1;i<=3;i++)scratch.update("INSERT INTO records(bucket,key,body) VALUES(?,?,?)",1,"k"+i,"v"+i);
            String sql="SELECT key FROM records WHERE bucket=? ORDER BY key";
            try(var outer=scratch.query(sql,1)){
                var outerStatement=outer.statement;
                assertEquals("k1",outer.next().get("key"));
                try(var inner=scratch.query(sql,1)){
                    assertNotSame(outerStatement,inner.statement);
                    assertEquals("k1",inner.next().get("key"));
                }
                assertEquals("k2",outer.next().get("key"));
                assertEquals("k3",outer.next().get("key"));
                assertFalse(outer.hasNext());
            }
        }
    }
}
