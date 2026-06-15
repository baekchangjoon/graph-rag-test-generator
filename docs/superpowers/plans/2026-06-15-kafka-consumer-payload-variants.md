# Kafka consumer payload 양-arm (결측-필드 / 중복 이벤트 변종)

작성일: 2026-06-15 · 브랜치: `worktree-feat-kafka-payload-variants` (main 기준)
근거: consumer 미커버 브랜치 실측 — consumer happy는 커버되나 내부 가드의 **반대 arm**(결측 필드 early-return,
중복 이벤트 dedup-skip)이 미탐색. #4(HTTP by-id 양-arm 시드)의 Kafka payload 버전. **3-모델 리뷰 반영(§8).**

## 1. 문제

`KafkaCaptureRunner`는 consumer당 **happy payload 1개**만 발행 → 핸들러 내부 가드의 반대 arm이 안 열린다.
in-repo 벤치마크 = order-service `OrderEventConsumer.onOrderEvent`(검증된 가드):
- **결측-필드 guard** `if (event.eventId()==null || event.userId()==null) return;` (line 32-33) — happy는 두 필드를
  채우므로 early-return arm 미커버.
- **dedup guard** `if (!repository.existsById(event.eventId()))` (line 35) — happy는 새 이벤트라 dedup-skip arm 미커버.

(notification/analytics 등 외부 consumer도 동형 가드 보유 — best-effort 적용, 단언은 order-service에 한정.)

## 2. 접근 — happy 뒤에 결정적 payload 변종 2종 발행

`KafkaCaptureRunner.run()`에서 happy 발행·캡처 후, 같은 producer로 변종을 발행해 반대 arm을 연다. 변종의 consumer
실행 커버리지를 `coverage.dump(true)` delta로 누적(F1[#33] 메커니즘) → `cumulativeExec`→`runWideExec` 병합으로
missed→covered 자동 반영. `GRB_KAFKA_VARIANTS=off`면 변종 skip(ablation/control).

변종(결정적, 필드 지식 불요):
1. **missing-field(하드 게이트, 결정적)**: payload = **빈 `ObjectNode `{}``**, key = 합성 상수 `"variant-missing-" +
   consumer.id()`(중복 회피). 역직렬화 시 전 필드 null → required-필드 null-guard early-return arm 실행(또는
   null-guard 없으면 파싱예외→catch arm — 어느 쪽이든 happy와 다른 arm, 0 SQL). **커밋 의존 없음 → 결정적.**
2. **duplicate(best-effort)**: happy payload를 **동일 key로 재발행** → 이미 존재 → dedup-skip arm. happy가 INSERT를
   캡처했으면(order-service) 그 PK·값으로 빌더 connection에서 `SELECT count(*) ... WHERE pk=?` 를 폴링해 **커밋 가시성
   확인 후** 발행(존재 보장). INSERT 미캡처(Redis 등)면 settle 후 재발행(best-effort).

**커버리지 dump 순서**(변종마다 baseline 리셋): happy `dump(true)`[baseline]→send→awaitConsumerSql→`dump(true)
.accept`[happy delta] → 변종마다 `dump(true)`[baseline]→send→settle→`dump(true).accept`[변종 delta].
SQL 없는 변종은 8s 폴링 대신 **`VARIANT_SETTLE_MILLIS = 2500` 고정 settle**(POLL_MILLIS×10; consumer 처리+probe
등록 여유) 후 dump.

**변종 교환 캡처 + 생성기 제외(리뷰 I1)**: `KafkaExchange`에 `boolean variant`(기본 false) 추가. 변종 교환은
`variant=true`(id에 `-dup`/`-missing` 접미)로 캡처. `Generator.generateKafka`는 **변종 교환을 skip**(`if
(exchange.variant()) continue;`) → happy(variant=false)만 테스트 생성 → **B2/`GeneratorKafkaTest` 무변경**.
(0-SQL로 거르면 Redis-happy까지 빠지므로 SQL이 아닌 **명시 flag**로 구분.)

`GRB_KAFKA_VARIANTS` 게이트: `KafkaCaptureRunner.run()`에서 `!"off".equalsIgnoreCase(System.getenv(
"GRB_KAFKA_VARIANTS"))` (GRB_STATE_GUARDS 패턴) — off면 변종 루프 skip.

## 3. E2E/수용 기준 (먼저 작성, 바깥 루프 — Docker 필요)

> `BuilderE2eTest`(`--with-kafka`, 실제 order-service jar). 단언은 `kafka-order-events` consumer에 한정.

1. **하드 게이트(결정적)**: `asset.kafkaExchanges()` 중 `kafkaConsumerId=="kafka-order-events"` 가 **happy(variant=false,
   `order_events` INSERT 보유) 1개 + missing-field 변종(variant=true, 0 SQL) ≥1개**. 즉 ≥2 교환, variant=true가 ≥1,
   variant=false가 정확히 1(INSERT 보유). 되돌리면(happy만) variant 교환 0 → FAIL.
2. **생성 무회귀**: `GeneratorKafkaTest`에 happy+변종 3교환 시나리오 추가 — `generateKafka`가 **variant 교환을 skip**해
   happy 1개만 생성(hasSize(1)). run-e2e 생성 Kafka 테스트도 happy만.
3. **best-effort(비-게이트)**: duplicate 변종은 커밋-가시성 폴링 성공 시 dedup arm 커버(0 SQL). 폴링 타임아웃/Redis면
   best-effort(커버 못해도 회귀 아님). E2E는 duplicate의 SQL 수를 **단언하지 않음**(flaky 회피).
4. **ablation/전 SUT 회귀**: `GRB_KAFKA_VARIANTS=off`면 변종 0. petclinic + tainted-spring MVC 6개 스윕 — consumer
   없는/적은 SUT 무영향(변종은 추가만, 감소 불가).

## 4. Double-loop TDD 순서

1. **바깥 먼저(RED)**: §3-1 하드 게이트 단언을 `BuilderE2eTest`에 추가 — 현재 교환 1개 → RED.
2. **inner #1 변종 payload 합성(단위, RED→GREEN)**: `KafkaCaptureRunnerVariantTest`(`graph-rag-builder/src/test/
   java/io/graphrag/builder/run/`) — `missingFieldPayload()`=빈 ObjectNode, `duplicatePayload(happy)`=deepCopy,
   variant key 합성을 순수 정적 메서드로 단언(producer/SUT 불요).
3. **inner #2 모델+생성기(단위, RED→GREEN)**: `KafkaExchange.variant` 추가(JsonRoundTrip 갱신) + `generateKafka`
   variant skip. `GeneratorKafkaTest`에 3교환→1생성 시나리오.
4. **inner #3 발행 루프 배선**: happy 후 변종 2종 발행(dump 순서 §2) + `VARIANT_SETTLE_MILLIS` + duplicate 커밋 폴링 +
   교환 캡처(variant=true). `GRB_KAFKA_VARIANTS` 게이트. 통합은 바깥 E2E로 검증.
5. **단위 회귀(no Docker)**: `./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test` (BuilderE2eTest는 Docker-gated).
6. **바깥 GREEN(Docker)**: `BuilderE2eTest` §3-1 통과 + ablation(off)로 변종 0 확인.
7. **PR 게이트**: 회귀 green(Docker-skip 명시) + 문서 갱신(docs/24) → spec-compliance 리뷰 먼저 → code-quality 리뷰 → triage.

## 5. 범위 / 비범위
- **범위**: order-service consumer의 결측-필드 early-return arm(하드)+ 중복 dedup-skip arm(best-effort)을 변종 2종으로 연다. 변종 교환 캡처(variant 플래그) + 커버리지 크레딧. 생성기는 변종 제외.
- **비범위(보류)**: 필드별 부분-null 변종(type-null 삼항 등 — 어느 필드가 optional인지 정적 미상). 변종에서 negative-path 테스트 **생성**(현재는 커버리지만). 외부 SUT(notification/analytics) 변종 arm은 best-effort(단언 안 함).

## 6. 리스크 (리뷰 반영)
- **duplicate 커밋 가시성(리뷰 Opus I3)**: awaitConsumerSql은 INSERT **로그 출현**만 확인(커밋 아님). 완화: happy의
  캡처 INSERT PK·값으로 빌더 connection `SELECT count` 폴링(≥1=커밋) 후 재발행. Redis/미캡처면 best-effort(게이트 아님).
- **변종 settle 비결정**: SQL 없는 변종은 완료 신호 없음 → `VARIANT_SETTLE_MILLIS=2500` 고정. arm 미커버여도 회귀 아님(happy 불변).
- **빈 `{}` 파싱 예외 vs null-guard**: 어느 쪽이든 happy와 다른 arm(커버 가치). E2E는 missing 변종의 0-SQL+variant 플래그만 단언(특정 arm 아님).
- **생성기 변종 누출(리뷰 I1, critical)**: `variant` 플래그로 generateKafka에서 skip — SQL 유무가 아니라 명시 플래그(Redis-happy 보호). `GeneratorKafkaTest` 3교환 시나리오로 가드.
- **예산**: consumer당 발행 +2(settle ~2.5s×2). consumer 수 적어 영향 작음.

## 7. 관련 파일
- 수정: `run/KafkaCaptureRunner.java`(변종 발행 루프+payload 합성+settle+커밋 폴링+게이트), `model/KafkaExchange.java`(variant 플래그), `generator/Generator.java`(generateKafka variant skip), `cli/BuilderCli.java`(CLI 주석에 env 문서화).
- 테스트: `KafkaCaptureRunnerVariantTest`(단위), `GeneratorKafkaTest`(3교환 시나리오), `JsonRoundTripTest`(KafkaExchange variant), `BuilderE2eTest`(수용).
- 문서: `docs/24` 갱신.

## 8. 3-모델 리뷰 triage (Opus/Sonnet/Haiku)
세 모델 모두 `needs_revision` — 공통 **critical**: generateKafka가 교환당 1파일 생성이라 변종 캡처 시 B2 회귀(원안의 "생성 무변경"은 거짓). 반영:
- **반영(설계 수정)**: (1) `KafkaExchange.variant` 플래그 + generateKafka skip(SQL 아닌 명시 플래그 — Redis-happy 보호)[I1/I2]; (2) `VARIANT_SETTLE_MILLIS=2500` 상수 확정[settle]; (3) `GRB_KAFKA_VARIANTS` 읽기 위치·패턴 명시(KafkaCaptureRunner, GRB_STATE_GUARDS 패턴)[gate]; (4) dump baseline-per-variant 순서 명시[isolation]; (5) duplicate 커밋-가시성 폴링 + missing-field를 결정적 하드 게이트로, duplicate는 best-effort[Opus I3/I5]; (6) E2E를 kafka-order-events에 한정·falsifiable화; (7) notification 예시는 best-effort로 강등, 단언은 order-service 한정.
- **거부/보류**: 외부 SUT(notification/analytics) consumer 가드 라인 검증은 best-effort라 불요(단언 대상 아님). type-null 등 부분-null 변종은 비범위(필드 optional 정적 미상).
