package io.graphrag.fixture.impldispatch;

/** 구현이 추상 베이스를 거쳐 이뤄지는 인터페이스(구체 구현은 하나뿐). */
public interface NotifyService {

    String notify(String channel);
}
