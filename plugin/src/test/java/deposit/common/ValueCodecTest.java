package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueCodecTest {
    @Test void storageRoundTripsExactCollectionsAndSpatialNativePoints() {
        var point=Json.map("srid",9157L,"coordinates",List.of(1.25,2.5,3.75));
        assertEquals(point,ValueCodec.decode("Point",ValueCodec.storage("Point",ValueCodec.normalize("Point",point))));
        var collection=Json.map("itemType","DDict","items",Arrays.asList(Json.map("n",Json.map("type","Integer","value",Long.MAX_VALUE)),null));
        Object normalized=ValueCodec.normalize("List",collection);String stored=(String)ValueCodec.storage("List",normalized);
        assertTrue(stored.contains("\"9223372036854775807\""));assertEquals(collection,ValueCodec.decode("List",stored));
        var duration=Json.map("months",5L,"days",2L,"seconds",3L,"nanoseconds",4L);
        assertEquals(duration,ValueCodec.decode("Duration",ValueCodec.storage("Duration",ValueCodec.normalize("Duration",duration))));
    }
    @Test void scalarCoercionNeverLosesPrecisionAndDeclaredDictionaryIsNotUnwrapped() {
        assertEquals((double)Long.MIN_VALUE,ValueCodec.normalize("Float",Long.MIN_VALUE));
        assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("Float",Long.MAX_VALUE));
        assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("Float",Double.NaN));
        assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("Integer",1.0));
        assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("Point",Json.map("srid",7203L,"coordinates",List.of(Long.MAX_VALUE,0L))));
        var dictionary=Json.map("type",Json.map("type","String","value","actual content"),"value",Json.map("type","Integer","value",3L));
        assertEquals(dictionary,ValueCodec.decode("DDict",ValueCodec.storage("DDict",ValueCodec.normalize("DDict",dictionary))));
    }
}
