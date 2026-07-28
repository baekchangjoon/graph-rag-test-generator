package io.graphrag.fixture.impldispatch;

import org.springframework.stereotype.Service;

/** NotifyService의 유일한 <b>구체</b> 구현 — 가드가 여기 있다. */
@Service
public class NotifyServiceImpl extends AbstractAuditBase {

    @Override
    public String notify(String channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel required");
        }
        return "OK";
    }
}
