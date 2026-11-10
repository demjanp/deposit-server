package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TabularLimitsTest {
    @Test void generatedResourceContentHasNoPolicySizeCeiling(){
        assertDoesNotThrow(()->TabularService.requireContentLimit(Long.MAX_VALUE,"content limit"));
        assertThrows(IllegalArgumentException.class,()->TabularService.requireContentLimit(-1,"content limit"));
    }

    @Test void xlsxDimensionsIncludeTheHeaderAndEnforceBothAxes(){
        assertDoesNotThrow(()->XlsxTableWriter.requireDimensions(1_048_575L,16_384));
        var rowFailure=assertThrows(RuntimeException.class,()->XlsxTableWriter.requireDimensions(1_048_576L,1));
        assertTrue(rowFailure.getMessage().contains("LIMIT_EXCEEDED"));
        var columnFailure=assertThrows(RuntimeException.class,()->XlsxTableWriter.requireDimensions(0,16_385));
        assertTrue(columnFailure.getMessage().contains("LIMIT_EXCEEDED"));
    }
}
