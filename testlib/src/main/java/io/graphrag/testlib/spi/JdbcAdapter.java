package io.graphrag.testlib.spi;

import java.sql.Connection;
import java.sql.SQLException;

public interface JdbcAdapter extends Adapter {
    Connection connect(Env env) throws SQLException;
}
