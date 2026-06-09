package io.graphrag.testlib.adapter.jdbc;

import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.JdbcAdapter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 기본 어댑터: 그냥 JDBC (docs/07). */
public final class PlainJdbcAdapter implements JdbcAdapter {

    @Override
    public String name() {
        return "plain";
    }

    @Override
    public Connection connect(Env env) throws SQLException {
        return DriverManager.getConnection(
                env.require("JDBC_URL"),
                env.require("JDBC_USER"),
                env.require("JDBC_PASS"));
    }
}
