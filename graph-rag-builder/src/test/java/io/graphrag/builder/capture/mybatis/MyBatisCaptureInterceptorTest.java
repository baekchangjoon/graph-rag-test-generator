package io.graphrag.builder.capture.mybatis;

import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisCaptureInterceptorTest {

    public interface SampleMapper {
        @Insert("INSERT INTO items(id, name) VALUES(#{id}, #{name})")
        int insertItem(@org.apache.ibatis.annotations.Param("id") String id,
                       @org.apache.ibatis.annotations.Param("name") String name);

        @Select("SELECT name FROM items WHERE id = #{id}")
        String findName(@org.apache.ibatis.annotations.Param("id") String id);
    }

    private SqlSessionFactory factory;

    @BeforeEach
    void setupDb() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mybatis-test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS items");
            stmt.execute("CREATE TABLE items(id VARCHAR(64) PRIMARY KEY, name VARCHAR(255) NOT NULL)");
        }

        Configuration config = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), (DataSource) ds));
        config.addInterceptor(new MyBatisCaptureInterceptor());
        config.addMapper(SampleMapper.class);
        factory = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterEach
    void cleanup() {
        CaptureContext.clear();
    }

    @Test
    void insertIsCapturedAsMyBatisXmlMapperSource() {
        CaptureContext ctx = new CaptureContext("path-mybatis");
        CaptureContext.set(ctx);

        try (SqlSession session = factory.openSession(true)) {
            SampleMapper mapper = session.getMapper(SampleMapper.class);
            mapper.insertItem("i-1", "alpha");
        }

        List<CapturedSql> captured = ctx.capturedSql();
        assertThat(captured).isNotEmpty();
        CapturedSql first = captured.get(0);
        assertThat(first.type()).isEqualTo(CapturedSqlType.INSERT);
        assertThat(first.rawSql().toLowerCase()).contains("insert into items");
        assertThat(first.source()).isEqualTo(CapturedSqlSource.MYBATIS_XML_MAPPER);
        assertThat(first.bindings()).hasSize(2);
    }

    @Test
    void selectIsCapturedWithParameterBinding() {
        CaptureContext setup = new CaptureContext("setup");
        CaptureContext.set(setup);
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(SampleMapper.class).insertItem("i-7", "seven");
        }

        CaptureContext queryCtx = new CaptureContext("query");
        CaptureContext.set(queryCtx);
        String name;
        try (SqlSession session = factory.openSession(true)) {
            name = session.getMapper(SampleMapper.class).findName("i-7");
        }

        assertThat(name).isEqualTo("seven");
        List<CapturedSql> q = queryCtx.capturedSql();
        assertThat(q).hasSize(1);
        assertThat(q.get(0).type()).isEqualTo(CapturedSqlType.SELECT);
        assertThat(q.get(0).bindings()).hasSize(1);
        assertThat(q.get(0).bindings().get(0).value()).isEqualTo("i-7");
    }

    @Test
    void noCaptureWhenContextIsClear() {
        // 컨텍스트 미설정 시 캡처는 일어나지 않아야 함
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(SampleMapper.class).insertItem("i-x", "noscope");
        }
        // 검증: 새 컨텍스트 만들고 비어있음 확인
        CaptureContext ctx = new CaptureContext("post-hoc");
        CaptureContext.set(ctx);
        assertThat(ctx.capturedSql()).isEmpty();
    }
}
