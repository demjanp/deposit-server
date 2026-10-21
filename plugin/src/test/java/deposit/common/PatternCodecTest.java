package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatternCodecTest {
    private Map<String,Object> definition(Map<String,Object> role){return Json.map("objects",Json.map("root",role));}
    @Test void declaredDescriptorsAreTheMatchingScopeAndLegacyKeyControlIsRejected(){
        var definition=definition(Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Code"))));
        assertDoesNotThrow(()->PatternCodec.validate(List.of(definition),Map.of("Thing",Map.of("Code","String"))));
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.validate(List.of(definition),Map.of("Thing",Map.of())));
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.normalize(definition(Json.map("className","Thing","root",true,"matchDescriptors",List.of("Code")))));
    }
    @Test void missingDeclarationProposalsMustAgreeAcrossDefinitions(){
        var string=definition(Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Code","type","String"))));
        var integer=definition(Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Code","type","Integer"))));
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.validate(List.of(string,integer),Map.of("Thing",Map.of())));
        assertDoesNotThrow(()->PatternCodec.validate(List.of(string,string),Map.of("Thing",Map.of())));
    }
    @Test void sameClassAliasesShareNormalizedMatchingTuple(){
        var first=Json.map("className","Thing","root",true,"descriptors",List.of(Json.map("name","Code"),Json.map("name","Mass")),"relationships",List.of(Json.map("label","next","targetName","second")));
        var second=Json.map("className","Thing","descriptors",List.of(Json.map("name","Mass"),Json.map("name","Code")));
        var definition=Json.map("objects",Json.map("first",first,"second",second));
        var schema=Map.of("Thing",Map.of("Code","String","Mass","Integer"));
        assertDoesNotThrow(()->PatternCodec.validate(List.of(definition),schema));
        second.put("matchEmpty",true);
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.validate(List.of(definition),schema));
    }
    @Test void normalizedRoleCollisionsAndUnsupportedLegacyFieldsFail(){
        var role=Json.map("className","Thing","root",true);
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.normalize(Json.map("objects",Json.map("root",role," root ",role))));
        role.put("unique",true);
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.normalize(definition(role)));
    }
    @Test void everyConnectedGroupRequiresOneRootEvenForCycles(){
        var role=Json.map("className","Thing","relationships",List.of(Json.map("label","self","targetName","root")));
        assertThrows(ExceptionHandler.DepositException.class,()->PatternCodec.normalize(definition(role)));
        role.put("root",true);assertDoesNotThrow(()->PatternCodec.normalize(definition(role)));
    }
    @Test void lookupSchemaBorrowsIndexesAndRejectsEnumeration(){
        var classes=new HashMap<String,String>();classes.put("Thing","class-id");
        var declarations=new HashMap<String,String>();declarations.put("class-id\u0000Code","String");
        var schema=PatternCodec.lookupSchema(classes,declarations);
        assertEquals("String",schema.get("Thing").get("Code"));
        assertTrue(schema.get("Thing").containsKey("Code"));
        assertFalse(schema.get("Thing").containsKey("Missing"));
        assertNull(schema.get("Absent"));
        declarations.put("class-id\u0000Code","Integer");
        assertEquals("Integer",schema.get("Thing").get("Code"));
        assertThrows(UnsupportedOperationException.class,()->schema.containsKey("Thing"));
        assertThrows(UnsupportedOperationException.class,()->schema.entrySet());
        assertThrows(UnsupportedOperationException.class,()->schema.get("Thing").entrySet());
    }
}
