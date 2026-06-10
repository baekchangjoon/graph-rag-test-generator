package io.graphrag.sample.orders;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** MyBatis XML mapper (동적 SQL). 레거시 혼재 시나리오 재현용. */
@Mapper
public interface OrderSearchMapper {

    List<Map<String, Object>> search(@Param("userId") String userId,
                                     @Param("type") String type,
                                     @Param("minAmount") Integer minAmount);
}
