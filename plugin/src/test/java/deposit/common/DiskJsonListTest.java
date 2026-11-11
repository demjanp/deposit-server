package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DiskJsonListTest {
    @Test void intListViewSaturatesWhileLongRecordBoundaryRemainsCorrect(){
        assertEquals(Integer.MAX_VALUE,DiskJsonList.intSize(Integer.MAX_VALUE+1L));
        assertEquals(Integer.MAX_VALUE,DiskJsonList.intSize(Long.MAX_VALUE));
        assertTrue(DiskJsonList.hasNextRecord(Integer.MAX_VALUE,(long)Integer.MAX_VALUE+2));
        assertFalse(DiskJsonList.hasNextRecord(Long.MAX_VALUE-1,Long.MAX_VALUE));
        assertFalse(DiskJsonList.hasNextRecord(0,0));
    }
}
