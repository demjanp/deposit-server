package deposit.common;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class GeometryCodecTest {
    @Test void coordinatesPreserveHolesZAndMetadata() {
        var rings=List.of(List.of(List.of(0.,0.,1.),List.of(9.,0.,2.),List.of(9.,9.,3.),List.of(0.,0.,1.)),List.of(List.of(6.,1.,4.),List.of(7.,1.,5.),List.of(7.,2.,6.),List.of(6.,1.,4.)));
        var result=GeometryCodec.fromCoordinates("Polygon",rings,999999L,null);assertEquals(rings,result.get("coordinates"));assertEquals(3L,result.get("dimensions"));assertTrue(((String)result.get("wkt")).contains("Z"));assertEquals(999999L,result.get("srid"));
    }
    @Test void rejectsMalformedAndUnsupportedInputsAndNestedReferenceTypes() {
        for(String wkt:List.of("POINT EMPTY","POINT M (1 2 3)","POINT ZM (1 2 3 4)","POINT (1 2 3)","POINT Z (1 2)","POINT (NaN 2)","GEOMETRYCOLLECTION (POINT (1 2))","MULTIPOINT (EMPTY,(1 2))","POINT (1 2) POINT (3 4)","POLYGON ((0 0,2 2,0 2,2 0,0 0))"))assertThrows(ExceptionHandler.DepositException.class,()->GeometryCodec.fromWkt(wkt,null,null),wkt);
        for(String type:List.of("DGeometry","DResource")) {
            assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("List",Json.map("itemType",type,"items",List.of())));
            assertThrows(ExceptionHandler.DepositException.class,()->ValueCodec.normalize("DDict",Json.map("x",Json.map("type",type,"value",null))));
        }
    }
}
