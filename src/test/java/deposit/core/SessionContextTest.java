package deposit.core;

import deposit.core.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SessionContextTest {

    @BeforeEach
    public void setup() {
        SessionContext.setActiveDatabase(null);
    }

    @Test
    public void setActiveDatabase_setsDatabase() {
        String expectedDatabase = "testDB";
        SessionContext.setActiveDatabase(expectedDatabase);
        assertEquals(expectedDatabase, SessionContext.getActiveDatabase());
    }

    @Test
    public void getActiveDatabase_returnsNullWhenNoDatabaseSet() {
        assertNull(SessionContext.getActiveDatabase());
    }

    @Test
    public void setActiveDatabase_overwritesExistingDatabase() {
        String initialDatabase = "initialDB";
        String expectedDatabase = "newDB";
        SessionContext.setActiveDatabase(initialDatabase);
        SessionContext.setActiveDatabase(expectedDatabase);
        assertEquals(expectedDatabase, SessionContext.getActiveDatabase());
    }
}