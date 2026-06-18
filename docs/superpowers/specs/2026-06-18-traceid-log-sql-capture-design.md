# Sleuth(B3) trace 모드 + 로그 기반 SQL 캡처 백엔드 설계 (레거시 비동기 SUT)

- 작성일: 2026-06-18 (3-model 리뷰 + `--trace-mode` 명명 확정 rev.2)
- 상태: Draft (설계리뷰 반영 → 사용자 검토 대기)
- 관련: PoC `poc/legacy-async-capture/` (브랜치 `worktree-poc-legacy-async-sql-capture`)

## 1. Goal

Java 8 + Spring Cloud Sleuth(Brave, B3) + Eventuate/Tram(Waffle) 기반 **레거시 비동기 MSA**에
graph-rag-builder를 **attach 모드(docker-compose)**로 붙여, **A→B→C 에서 B→C가 Tram 메시지로 비동기
발행/구독되고 C 컨슈머가 실행하는 SQL(+바인딩)**을 요청별로 캡처하고 **API↔SQL 매핑 그래프**를 만든다.
새 trace 모드 **`sleuth`**(B3 전파 + 로그 trace-id 상관, 신규 `SleuthLogCapture` 백엔드)와
`SqlLogParser`의 다중 ORM 자동 감지로 달성한다.

## 2. 공통 목표와 trace 모드의 역할

**모든 모드의 공통 목표는 API↔SQL 매핑 그래프**다. trace 전파(otel/sleuth)는 이 매핑을 확장하는 인프라이며,
SQL 귀속만이 아니라 **병렬 수행**과 **per-step/테스트케이스 식별(baggage path-id 격리)**까지 받친다.

`--trace-mode <otel|sleuth|none>` (기본 `otel`):

| 모드 | trace/baggage 전파 | SQL 추출 | API↔SQL 매핑 범위 | 병렬·per-step 격리 | 상관 헤더 |
|---|---|---|---|---|---|
| `none` | 없음 | 로그 byte-offset(직렬) | 동기·동일프로세스 SQL (모놀리식 baseline) | ❌ (직렬 강제) | 없음 |
| `sleuth` (신규) | Sleuth/B3 (+baggage) | 로그 trace-id 상관 | **+ 비동기 서비스간(B→C)** | ✅ | B3 |
| `otel` | OTEL agent (traceparent+baggage) | OTLP DB span (로그 fallback) | **+ 비동기 서비스간(B→C)** | ✅ | traceparent |

- `otel`/`sleuth`는 **trace 백엔드 선택**(매핑을 비동기 cross-service로 확장 + 병렬 + per-step 격리).
- **로그 파싱은 SQL 추출 fallback** — `sleuth`의 주 메커니즘이자 `otel`의 빈-trace 폴백, `none`의 유일 수단.
- `none`은 추적 전무 SUT의 **격하 baseline**(직렬·격리 없음) — 기존 `--sql-capture log` 보존.

본 spec은 이 중 **`sleuth` 모드 신규 도입**이 핵심이다(otel은 기존, none은 기존 log의 개명).

## 3. 배경 / 왜 `sleuth`(trace-id 로그 상관)인가 (PoC 실측 근거)

다른 PC 레거시 측정:
- **OTEL agent 부적합(이 스택)**: Eventuate가 `brave.Tracing` 빈을 강제해 Sleuth 제거 시 부팅 실패
  (`NoSuchBeanDefinitionException`); OTEL agent 컨텍스트는 Tram 메시지 경계를 못 넘어 비동기 SQL 누락.
- **Sleuth trace-id는 살아있음(초기 결론 번복)**: 1차 PoC는 "전파=0"으로 사망 판단했으나, 원인은 전파 실패가
  아니라 **레거시의 커스텀 logback이 trace-id를 출력하지 않음**이었다. SUT가 `logback-trace.xml`에
  `%X{traceId}` 패턴을 추가하자 **모든 SQL/bind 라인에 traceId가 출력**됨(binding parameter 포함). 즉
  Brave는 Tram 너머 C 컨슈머 스레드 MDC까지 trace context를 동기화하고 있었다. (번복은 PoC README에 동기화.)
- **bind = Hibernate 5**, 로거명이 logback `%logger{36}`로 **축약**됨(`o.h.type.descriptor.sql.BasicBinder`).
- **인프라 노이즈**: Eventuate CDC/relay가 `message`/`received_messages` 등을 상시 폴링 → 배경 SQL. 이들은
  **요청 traceId가 없는** 백그라운드 스레드에서 돌므로 traceId 필터로 **자동 배제**됨.

→ 요청별 고유 B3 traceId를 A에 주입하고, 그 traceId가 박힌 로그 라인만 필터하면 A→B→C 전체 SQL footprint를
요청 단위로 회수한다.

> **R1 (치명, 미확정)**: "주입한 traceId가 **동일 값으로** Tram 너머 C까지 전파"는 라이브로 미확인이며
> **Spec 1 샘플의 1순위 검증 대상**이다. 미확정인 채로 구현하되, 가정이 깨지면 probe/window로 회귀.

## 4. 범위와 분해

- **이 spec (Spec 2, 지금)**: `sleuth` 모드(`SleuthLogCapture` 백엔드) + `SqlLogParser` 다중 ORM 자동 감지/축약
  로거 + 다중 서비스 로그 수집·로그레벨 자동 주입 + `--trace-mode`(개명) / `--capture-services` CLI. **합성
  로그 픽스처** 기반 단위/통합 테스트까지. **attach 모드 전용.**
- **CLI 개명(이 spec 포함)**: 사용자 플래그 `--sql-capture` → `--trace-mode`, 값 `otel|log` → `otel|sleuth|none`
  (`log`→`none`). 내부 인터페이스 `SqlCaptureBackend` 이름은 유지(실제로 SQL을 캡처). 라이브 코드(`BuilderCli`,
  `BuildConfig`)·사용자 문서(README, docs/00·03·06·26·27)·e2e 스크립트의 참조 갱신. 과거 plan/report 문서는
  기록이라 미수정.
- **후속 (Spec 1, 나중)**: 충실한 Eventuate Tram(Kafka+CDC, 3서비스 A→B→C, Java8/Boot2/H5) **샘플** +
  라이브 E2E. 이 spec의 **최종 수용 게이트**.
- **Out of scope**: 샘플 스택(Spec 1), probe/payload 상관(백업), OTEL 경로 변경, 비-attach(jar) 경로.

## 5. 아키텍처 / 접근

기존 `SqlCaptureBackend` 인터페이스(`begin()`/`Scope`/`drain()`)를 그대로 따르는 신규 `sleuth` 백엔드.

**[정정 — 리뷰 I1] 멀티서비스 로그 수집:** 현재 `AttachedComposeEnvironment.logsCommand()`는
`docker compose ... logs --no-log-prefix -f <appService>`로 **단일 app 서비스만** tail한다(테스트
`logsCommandFollowsAppServiceNoPrefix`로 고정). A→B→C는 **별도 컨테이너**이므로 C 로그가 누락된다.
→ **`--capture-services a,b,c`** 옵션을 추가해 지정 서비스를 모두 tail하고
(`docker compose logs --no-log-prefix -f <a> <b> <c>`), 한 파일에 인터리브 수집한다. 미지정 시 기존 단일
`appService` 동작 유지(하위호환). traceId가 상관 키이므로 `--no-log-prefix` 유지.

```
begin():  요청별 유니크 B3 traceId 생성 + 현재 (수집)로그 오프셋 기록
   │
requestHeaders():  B3 멀티헤더(X-B3-TraceId/X-B3-SpanId/X-B3-Sampled) 반환
   │           → EndpointExplorationRunner 가 A 요청에 주입(사용자 B3 헤더는 제거, §7)
요청 전송 → A→B(동기) → B→C(Tram 비동기) → C 컨슈머가 H5 SQL 실행
   │           (Brave가 traceId를 메시지 너머 C 스레드 MDC로 전파, logback이 라인에 출력)
drain():  수집 로그 [offset, 현재) 에서 traceId 일치 라인만 필터
          → await(첫 매칭 출현 ~ quiescence) → SqlLogParser 파싱 → 순서 보존 List<ParsedSql>
```

핵심: **traceId 불일치 = 다른 요청·인프라 폴링 SQL → 자동 배제.** denylist 불필요.

## 6. 컴포넌트 (신규/수정)

- **Create** `capture/SleuthLogCapture.java` — `sleuth` 모드 backend. `SutHandle`(수집 로그) + `B3TraceId` 주입.
- **Create** `capture/B3TraceId.java` — **기존 `TraceParent`의 얇은 façade**. `TraceParent`가 이미 32-hex
  traceId/16-hex spanId를 결정적으로 생성하므로 위임하고, B3 헤더 맵(`X-B3-TraceId/SpanId/Sampled=1`)만
  포맷한다. (별도 클래스 이유: B3 헤더 책임 분리 + run 유일성 시드, §11 R5.)
- **Modify** `capture/SqlLogParser.java` — H5 BasicBinder(축약/풀 로거명) 패턴 추가, 라인 prefix traceId
  추출(§8), H6/MyBatis 자동 감지 유지.
- **Modify** `env/AttachedComposeEnvironment.java` — `logsCommand()`가 다중 `--capture-services`를 tail
  (테스트도 다중 서비스 케이스로 확장).
- **Modify** `env/OverrideComposeGenerator.java` — `SPRING_APPLICATION_JSON`(H5/H6 로그레벨 +
  `-Dfile.encoding`)을 **`--capture-services` 전체**에 주입(단일 appService → 서비스 목록).
- **Modify** `cli/BuilderCli.java` (+ `BuildConfig`) — `--sql-capture`→`--trace-mode` 개명, 값 검증
  `otel|sleuth|none`(그 외 throw); `--capture-services` 파싱; **dispatch 분기**(현 sqlCapture 분기 지점들,
  ~150/~235/~457)를 `otel|sleuth|none`으로 갱신(`sleuth`: OTEL agent·OTLP 리시버 미생성, `disableBatch=false`,
  `SleuthLogCapture`를 `env.sut()` 핸들로 **BuilderCli에서 생성**; `none`: 기존 LogParserCapture 경로). 환경
  클래스에 backend 로직 미주입(리뷰 I4·Gemini I3).
- **Modify** `run/EndpointExplorationRunner.java` (doSend) — backend 상관 헤더가 사용자 헤더보다 우선(§7).
- **삭제(범위 밖)**: `AnalysisEnvironment`는 비-attach(jar) 경로라 본 기능과 무관 → 수정 대상 제외.
- **문서**: README·docs/00·03·06·26·27·e2e 스크립트의 `--sql-capture`/`log` 표기를 `--trace-mode`/`none`으로 갱신.
- **Tests** — 합성 픽스처 기반 단위/통합(§10).

## 7. 데이터 흐름 / 상관 + 헤더 우선순위(모드별 선택)

**상관 헤더는 활성 `--trace-mode`가 결정한다 — 모드별 선택적·상호배타.** 기존
`SqlCaptureBackend.Scope.requestHeaders()` 추상화 그대로다:

| 모드 | 백엔드 | 주입 상관 헤더 |
|---|---|---|
| `otel` | `OtelSpanCapture` | `traceparent`(W3C) — **B3 미사용** |
| `sleuth` | `SleuthLogCapture`(신규) | **B3**(`X-B3-TraceId/SpanId/Sampled` + `b3`) — **traceparent 미사용** |
| `none` | `LogParserCapture` | 없음 |

즉 **B3 주입은 `sleuth` 모드 전용, OTEL agent(`otel`)에는 미적용.** 러너는 헤더를 하드코딩하지 않고
`scope.requestHeaders()`가 준 것만 주입한다.

흐름(`sleuth` 모드):
1. `begin()` → `B3TraceId.next()`로 `(traceId, spanId)` 발급, `logStart = sut.logOffset()` 기록.
2. `requestHeaders()` → `{X-B3-TraceId, X-B3-SpanId, X-B3-Sampled=1}` (+ 단일 `b3`).
3. 러너 doSend: **backend 헤더 우선.** 주입 전 알려진 상관 헤더(`traceparent`,`X-B3-TraceId/SpanId/Sampled`,
   `b3`)를 사용자 제공분에서 **case-insensitive 제거** 후 `scope.requestHeaders()`만 주입(중복·비결정 전파
   방지, 리뷰 Gemini I1·GPT I3). 응답 수신(이 시점 C SQL 미완).
4. `drain()`: await → quiescence → traceId 일치 라인만 `SqlLogParser.parse(...)` → 순서 보존 반환.

## 8. SqlLogParser — 다중 ORM 자동 감지(사용자 입력 0) + traceId 추출

라인별로 모든 패턴을 시도해 형식이 스스로 식별되게 한다. ORM 선택 플래그 없음.

**trace-mode 독립**: 이 자동 감지(H5 축약/풀·H6·MyBatis)와 H5 BasicBinder 로그레벨 주입은 `SqlLogParser`/공유
주입 코드의 속성이므로 **로그를 파싱하는 모든 경로 — `none`, `sleuth`, `otel`의 빈-trace 폴백 — 에 공통 적용**된다.
`otel`의 1차 OTLP-span 경로만 bind를 span 속성에서 얻어(ORM 버전 무관) 로그 형식 감지를 거치지 않으나, 그
경로는 감지가 불필요한 구조라 누락이 없다.

- **SQL 텍스트**: `org.hibernate.SQL`(H5/H6 공통) / MyBatis `==> Preparing:`
- **bind**:
  - H6: `org.hibernate.orm.jdbc.bind ... binding parameter (1:VARCHAR) <- [v]`
  - H5: `...BasicBinder ... binding parameter [1] as [VARCHAR] - [v]` — **로거명 축약/풀네임 모두** 매칭.
  - MyBatis: `==> Parameters: v(T), ...`
- **traceId 추출 규칙(정밀화, 리뷰 GPT I4)**:
  - 라인의 **로그 prefix 영역만** 검사(로거명 `:` 구분자 이전). SQL 본문·bind 값에서는 찾지 않음(hex 오탐 방지).
  - MDC 덤프(`key=value`)면 키 `traceId`/`X-B3-TraceId`를 **정확히** 추출.
  - Sleuth 브래킷이면 **두 번째 comma 토큰**만 traceId 후보. 3-field `[service,traceId,spanId]`(Sleuth 3.x)
    와 **4-field `[service,traceId,spanId,exportable]`(Sleuth 1.x/2.x — Java8 레거시 기본)** 모두 지원.
    MDC 키 매칭은 좌측 경계로 `myTraceId=`류 접미 오탐을 막고, 32 초과 토큰은 거부(묵음 절단 금지). (리뷰 반영)
  - 매칭은 **대소문자 무관**, **full(32 hex) 또는 우측 64-bit(16 hex)** 둘 다 허용(§11 R3).

**로그레벨 자동 주입**: `org.hibernate.SQL=DEBUG`, `org.hibernate.orm.jdbc.bind=TRACE`(H6),
`org.hibernate.type.descriptor.sql.BasicBinder=TRACE`(H5)를 `--capture-services` 전 서비스에 항상 주입 —
없는 로거는 무시되어 버전 무관. MyBatis는 고정 로거가 없어(매퍼 네임스페이스별) 완전 자동 주입 불가 — 파서
감지는 자동, 매퍼 로깅 활성화는 best-effort로 두고 한계 문서화(레거시 대상은 H5라 영향 적음).

## 9. await/quiescence, 타임아웃, 인코딩 (리뷰 I6·Gemini I2·GPT I5)

- `OtelSpanCapture`의 await+quiescence **패턴** 재사용, 상수는 Tram 비동기 지연 고려해 재설정:
  `FIRST_MATCH_TIMEOUT`(첫 일치 대기, 기본 ≈3s, 구성 가능), `OVERALL_TIMEOUT`(≈15s), `QUIESCENCE_MILLIS`(≈300ms),
  `POLL_MILLIS`(≈50ms).
- **SQL 없는 요청 최적화**: 400/검증실패/캐시 GET 등은 일치 라인이 없다. `FIRST_MATCH_TIMEOUT` 내 첫 일치가
  없으면 **즉시 빈 결과**(전 요청에 `OVERALL_TIMEOUT`이면 퍼저가 치명적으로 느려짐). GET 일괄 스킵 같은
  휴리스틱은 GET도 비동기 쓰기를 유발할 수 있어 미채택 — 타임아웃 구성으로 일반화.
- **타임아웃**: 경고 로깅 후 **빈 결과**. 조용한 성공 위장 금지. offset-window 폴백은 인프라 노이즈를 다시
  들이므로 미채택(YAGNI).
- **인코딩**: `sleuth` 모드 기본 `JAVA_TOOL_OPTIONS`에 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8` **병합**
  (기존 `-javaagent`/jacoco 보존). 병합 지점은 `BuilderCli` 옵션 생성 단계, `SutProcess`·
  `OverrideComposeGenerator` 양쪽에 병합 보존 테스트 추가.

## 10. 테스트 / E2E 수용 기준

double-loop(프로젝트 규칙): **바깥(수용) = 합성 픽스처 out-of-process 블랙박스, 안쪽 = 단위 TDD.**
*DoD(인라인, 리뷰 GPT I6)*: 명시한 단위+통합(합성)이 전부 green이고 기존 회귀 green이면 이 spec 완료.
라이브 E2E는 Spec 1에서 green.

- **단위(SqlLogParser)**: H5(축약/풀)·H6·MyBatis 혼재 → 형식 자동 감지·바인딩·순서; traceId 추출(MDC키 vs
  Sleuth 브래킷, 64/128bit, 대소문자); SQL/bind 값의 hex가 traceId로 **오탐되지 않음**; 비-UTF8/한글.
- **단위(B3TraceId)**: B3 포맷(32/16 hex), `TraceParent` 위임 결정성, run 유일성 시드.
- **단위(헤더 우선순위)**: doSend가 사용자 B3/traceparent 제거 후 backend 헤더만 전송.
- **통합(SleuthLogCapture)**: 가짜 수집 로그에 traceId 태깅 H5 라인 + 인프라 라인 + 다른 traceId 라인을 비동기
  append → 해당 traceId 라인만 순서 보존 회수, 인프라/타 요청 배제. **타이밍(리뷰 Sonnet I8)**: 첫 일치가
  일찍 와도 quiescence 윈도우 내 추가 라인까지 기다려 **둘 다** 반환. SQL 없는 요청은 `FIRST_MATCH_TIMEOUT`
  후 빈 결과·조기 반환.
- **통합(멀티서비스 logsCommand)**: `--capture-services a,b,c`가 다중 서비스 tail 커맨드 생성.
- **회귀(개명)**: `--trace-mode otel`이 기존 `--sql-capture otel`과 동등 동작; `--trace-mode none`이 기존 `log`와
  동등; 잘못된 값 거부.
- **수용(라이브, 보류 → Spec 1 게이트)**: Eventuate 샘플 기동 → A에 B3 주입 → `sleuth` 모드로 C의 H5 SQL을
  요청별 캡처, 인프라 노이즈 배제, **주입 traceId의 A→B→C 동일 전파** 실증.

## 11. 리스크 / 미해결

- **R1 (치명)**: Tram 너머 traceId 동일 전파 미확인 → Spec 1 실증 전 가정. 실패 시 probe/window 회귀.
- **R2**: SUT가 logback traceId 패턴 미제공 시 `sleuth` 동작 불가(전제, §12).
- **R3**: traceId 포맷(64 vs 128bit) 편차 → 생성은 128bit(Brave/Sleuth 기본), 필터는 full 또는 우측 16 hex
  **둘 다 허용**, 대소문자 무관.
- **R4**: 동시 in-flight 요청 시 quiescence 경합 → `sleuth`는 trace-id로 구분 가능하나, 본 spec은 기존 직렬
  탐색 전제 유지(병렬 탐색 활용은 후속). `none`은 직렬 강제.
- **R5 (리뷰 GPT I7)**: `TraceParent` 시드가 `sutId[:commitSha]`라 **동일 커밋 동시 실행** 시 traceId sequence
  재생→충돌→캡처 교차오염 → `B3TraceId` 시드에 **per-run nonce** 포함(재현성보다 상관 정확성 우선,
  nonce 외부 주입으로 테스트 결정성 유지).

## 12. 전제 조건 (정밀화, 리뷰 Sonnet I7)

- **(sleuth 모드)** SUT logback의 appender 패턴에 `%X{traceId}`(또는 동등 MDC 키)가 포함되어야 함 — **유일한
  하드 요구**. 커스텀 logback은 Spring `logging.pattern.*` 주입을 무시하므로 SUT 제공자 책임.
  `logging.level.*` 주입(`SPRING_APPLICATION_JSON`)은 커스텀 logback 유무와 무관하게 동작. attach 요구사항으로
  문서화.
- **(sleuth 모드)** B3 traceId가 Tram 메시지 경계를 동일 값으로 전파(R1) — Spec 1 검증 대상.

## 13. Out of Scope

샘플 스택(Spec 1), probe/payload 상관, OTEL 경로 변경, MyBatis 매퍼 로깅 완전 자동화, 비-attach(jar) 경로,
병렬 탐색 활용(현재 직렬 유지).
