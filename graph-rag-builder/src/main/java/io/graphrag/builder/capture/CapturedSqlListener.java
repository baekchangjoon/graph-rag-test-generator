package io.graphrag.builder.capture;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

import java.util.ArrayList;
import java.util.List;

/**
 * datasource-proxy의 QueryExecutionListener 구현체.
 *
 * <p>{@link CaptureContext#current()}가 활성일 때만 캡처. 활성이 아니면 noop.
 *
 * <p>{@link CapturedSqlSource}는 JPA_ENTITYMANAGER로 기본 설정. 실제 출처 식별은 Phase 1+에서
 * stack trace inspection 등으로 보강.
 */
public final class CapturedSqlListener implements QueryExecutionListener {

    private final CapturedSqlSource defaultSource;

    public CapturedSqlListener() {
        this(CapturedSqlSource.JPA_ENTITYMANAGER);
    }

    public CapturedSqlListener(CapturedSqlSource defaultSource) {
        this.defaultSource = defaultSource;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) { /* noop */ }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        CaptureContext ctx = CaptureContext.current();
        if (ctx == null) return;
        for (QueryInfo qi : queryInfoList) {
            List<Object> params = flattenFirstBatch(qi);
            CapturedSql sql = CapturedSqlBuilder.build(ctx.pathId(), qi.getQuery(), params, defaultSource);
            ctx.addCapturedSql(sql);
        }
    }

    private static List<Object> flattenFirstBatch(QueryInfo qi) {
        List<Object> params = new ArrayList<>();
        if (qi.getParametersList() == null || qi.getParametersList().isEmpty()) {
            return params;
        }
        // batch 첫 번째만 사용 (단일 실행 가정). Phase 1+에서 batch 처리 강화.
        qi.getParametersList().get(0).forEach(p -> params.add(p.getArgs()[1]));
        return params;
    }
}
