package com.acme.opsweave.platform.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SchemaMigratorAtomicIT {
    @Test void failedDdlDoesNotLeavePartialSchemaOrSuccessMarker() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        var id="test-atomic-"+UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->new SchemaMigrator(source).apply("migration/atomic-failure.sql",id));
        try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT to_regclass('integration.test_incident_migration_rollback_probe')")){assertTrue(rows.next());assertNull(rows.getString(1));}
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT count(*) FROM integration.schema_migration WHERE id=?")){s.setString(1,id);try(var rows=s.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}}
    }
}
