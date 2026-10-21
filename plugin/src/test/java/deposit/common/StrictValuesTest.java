package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.service.BaseService;
import org.junit.jupiter.api.Test;

class StrictValuesTest {
    @Test void stringConversionAcceptsOnlyStringsAndFacadePreservesExactError(){
        assertEquals("",StrictValues.string(""));
        assertEquals("text",BaseService.string("text"));
        for(Object value:new Object[]{null,1L,1,1.0,new Object()}){
            var helper=assertThrows(ExceptionHandler.DepositException.class,()->StrictValues.string(value));
            var facade=assertThrows(ExceptionHandler.DepositException.class,()->BaseService.string(value));
            assertTrue(helper.getMessage().contains("\"code\":\"VALIDATION_FAILED\""));
            assertTrue(helper.getMessage().contains("\"message\":\"Expected String\""));
            assertEquals(helper.getMessage(),facade.getMessage());
        }
    }

    @Test void integerConversionAcceptsLongAndIntegerWithoutPrecisionLoss(){
        assertEquals(Long.MIN_VALUE,StrictValues.integer(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE,BaseService.integer(Long.MAX_VALUE));
        assertEquals(-7L,StrictValues.integer(-7));
        for(Object value:new Object[]{null,1.0,1.5,"1",new java.math.BigInteger("1"),
            java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE),
            java.math.BigInteger.valueOf(Long.MIN_VALUE).subtract(java.math.BigInteger.ONE)}){
            var helper=assertThrows(ExceptionHandler.DepositException.class,()->StrictValues.integer(value));
            var facade=assertThrows(ExceptionHandler.DepositException.class,()->BaseService.integer(value));
            assertTrue(helper.getMessage().contains("\"code\":\"VALIDATION_FAILED\""));
            assertTrue(helper.getMessage().contains("\"message\":\"Expected Integer\""));
            assertEquals(helper.getMessage(),facade.getMessage());
        }
    }
}
