package io.graphrag.builder.capture.mybatis;

import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.builder.capture.CapturedSqlBuilder;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis Plugin/Interceptor: 발행되는 SQL을 {@link CaptureContext}에 기록.
 *
 * <p>동적 SQL ({@code <if>}, {@code <foreach>} 등) 처리 후의 최종 SQL을 그대로 캡처하므로,
 * 분기 enumeration 없이 실제 형태가 보존됨.
 *
 * <p>Phase 1: prepared SQL 텍스트 + 바인딩 값을 추출. source는 MYBATIS_XML_MAPPER 기본
 * (annotation 출처 식별은 Phase 1+에서 MappedStatement.getId() 분석으로 강화).
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
})
public final class MyBatisCaptureInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        CaptureContext ctx = CaptureContext.current();
        if (ctx == null) {
            return invocation.proceed();
        }
        try {
            Object[] args = invocation.getArgs();
            MappedStatement ms = (MappedStatement) args[0];
            Object parameter = args.length >= 2 ? args[1] : null;
            BoundSql boundSql = ms.getBoundSql(parameter);

            String sql = normalizeWhitespace(boundSql.getSql());
            List<Object> values = extractParameterValues(ms.getConfiguration(), boundSql, parameter);

            CapturedSql captured = CapturedSqlBuilder.build(
                    ctx.pathId(), sql, values, CapturedSqlSource.MYBATIS_XML_MAPPER);
            ctx.addCapturedSql(captured);
        } catch (RuntimeException ignored) {
            // 캡처 실패가 SQL 실행을 막지 않도록
        }
        return invocation.proceed();
    }

    private static String normalizeWhitespace(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static List<Object> extractParameterValues(
            Configuration config, BoundSql boundSql, Object parameter) {
        List<Object> values = new ArrayList<>();
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        if (mappings == null || mappings.isEmpty()) {
            return values;
        }
        MetaObject metaParam = parameter == null
                ? null
                : config.newMetaObject(parameter);
        for (ParameterMapping pm : mappings) {
            String propName = pm.getProperty();
            Object value;
            if (boundSql.hasAdditionalParameter(propName)) {
                value = boundSql.getAdditionalParameter(propName);
            } else if (parameter == null) {
                value = null;
            } else if (config.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass())) {
                value = parameter;
            } else if (metaParam != null && metaParam.hasGetter(propName)) {
                value = metaParam.getValue(propName);
            } else {
                value = null;
            }
            values.add(value);
        }
        return values;
    }
}
