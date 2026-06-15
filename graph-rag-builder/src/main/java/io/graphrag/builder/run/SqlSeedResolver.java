package io.graphrag.builder.run;

import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * pass-1 탐색이 캡처한 SELECT SQL을 근거로 시드 타깃을 해석한다.
 * FROM 절(=CapturedSql.tableName) → 시드 테이블, WHERE col=? 절 → param이 시드할 컬럼.
 * path-string 휴리스틱이 놓친 엔드포인트(resource명≠table명, 비-PK 조회)를 실측으로 보정.
 */
public final class SqlSeedResolver {

    private SqlSeedResolver() {
    }

    /**
     * @param pass1Sql         pass-1 전체 path 합산 캡처 SQL
     * @param sentParamValues  PATH/QUERY param 이름 → pass-1에서 보낸 문자열 값
     * @return 해석된 hint. 스키마와 일치하는 SELECT가 없으면 null(Redis 등 SQL 미발생).
     */
    static ResolutionHint resolve(List<CapturedSql> pass1Sql, Map<String, String> sentParamValues,
                                  Endpoint endpoint, List<TableSchema> tables) {
        Set<String> tableNames = tables.stream().map(TableSchema::name).collect(Collectors.toSet());
        List<CapturedSql> candidates = pass1Sql.stream()
                .filter(s -> "SELECT".equalsIgnoreCase(s.sqlKind()))
                .filter(s -> tableNames.contains(s.tableName()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        List<EndpointParam> params = endpoint.params().stream()
                .filter(p -> p.kind() == ParamKind.PATH || p.kind() == ParamKind.QUERY)
                .toList();

        // 각 후보의 paramColumn 매핑을 계산, param 매칭이 가장 많은(=조회 주체) 후보 선정.
        CapturedSql best = candidates.get(0);
        Map<String, String> bestMap = paramColumns(best, params, sentParamValues);
        for (CapturedSql candidate : candidates) {
            Map<String, String> map = paramColumns(candidate, params, sentParamValues);
            if (map.size() > bestMap.size()) {
                best = candidate;
                bestMap = map;
            }
        }
        return new ResolutionHint(best.tableName(), bestMap);
    }

    /** 선정 SELECT의 바인딩에서 각 param이 시드할 컬럼을 도출. */
    private static Map<String, String> paramColumns(CapturedSql select, List<EndpointParam> params,
                                                    Map<String, String> sentParamValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (EndpointParam param : params) {
            String column = columnFor(param, select, sentParamValues.get(param.name()));
            if (column != null) {
                map.put(param.name(), column);
            }
        }
        return map;
    }

    /** 1순위: 컬럼명==camelToSnake(param). 2순위: API_PARAM 바인딩 값 == 보낸 값. */
    private static String columnFor(EndpointParam param, CapturedSql select, String sentValue) {
        String snake = ReadInputSynthesizer.camelToSnake(param.name());
        for (SqlBinding binding : select.bindings()) {
            if (binding.column() != null && binding.column().equalsIgnoreCase(snake)) {
                return binding.column();
            }
        }
        if (sentValue != null) {
            for (SqlBinding binding : select.bindings()) {
                if (binding.origin() == BindingOrigin.API_PARAM
                        && sentValue.equals(binding.value())
                        && binding.column() != null && !binding.column().isEmpty()) {
                    return binding.column();
                }
            }
        }
        return null;
    }
}
