package it.govpay.pendenze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Configurazione del datasource dell'applicazione.
 */
@SpringBootTest
@ActiveProfiles("test")
class IsolamentoDatasourceTest {

    @Autowired
    private DataSource dataSource;

    /**
     * #29: il pool usa READ_COMMITTED come il core GovPay, non il default del database.
     */
    @Test
    void isolamentoReadCommitted() throws SQLException {
        assertEquals("TRANSACTION_READ_COMMITTED", dataSource.unwrap(HikariDataSource.class).getTransactionIsolation());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
        }
    }
}
