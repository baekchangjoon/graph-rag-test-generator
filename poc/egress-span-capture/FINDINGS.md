# PoC 결과 — sleuth/Brave egress span 캡처 (전략 S-c)

## 목적
1순위 개선안(런타임 egress 캡처를 WireMock 리다이렉트 의존에서 분리)의 sleuth-모드 경로를
검증한다. 가설: **sleuth/Brave SUT에서 outbound HTTP CLIENT span을 Brave의 Zipkin export로
받아오면, 우리가 주입한 B3 trace-id에 귀속되어 egress 호출-사이트 + 계약 메타데이터를 얻을 수
있다** (리다이렉트 불요).

## 셋업
- SUT: `samples/legacy-tram/order-web` (Spring Boot 2.7.18, Spring Cloud Sleuth 3.1.9 = Brave, Java 8).
- egress: `OrderController`가 `@Bean RestTemplate`로 `POST {reservation.url}/reservations` 동기 호출
  (Brave가 계측하는 표준 클라이언트 → CLIENT span 생성).
- PoC 변경: order-web 빌드에 `org.springframework.cloud:spring-cloud-sleuth-zipkin` 추가,
  `spring.zipkin.base-url`(env `SPRING_ZIPKIN_BASEURL`)을 호스트 수신기로 지정.
- 트림 부팅: `mysql + order-web`만. reservation/kafka/cdc/ledger는 호스트 측 스텁(`receiver.py`)으로
  대체(egress-span 메커니즘 신호와 무관하므로 제외). 수신기는 동시에 Zipkin v2 sink 역할
  (`POST /api/v2/spans`)도 수행.
- 요청 주입: `B3TraceId.headers()`를 모사한 `X-B3-TraceId(32-hex)/X-B3-SpanId(16-hex)/X-B3-Sampled:1/b3`.

## 결과 — 가설 VALIDATED

| 항목 | 결과 |
|---|---|
| Brave가 egress CLIENT span을 Zipkin v2로 우리 수신기에 export | ✅ |
| 주입한 B3 traceId(128-bit, 32-hex) 보유 | ✅ CLIENT.parentId == 주입 spanId, SERVER.id == 주입 spanId |
| traceId 폭이 기존 `OtlpTraceReceiver` HEX_32 키와 일치 | ✅ 동일 상관 인프라 재사용 가능 |
| SUT sampler=0.0이어도 주입 `X-B3-Sampled:1`만으로 export 강제 | ✅ **SUT 샘플러 override 불필요** |
| `http.method` | ✅ 항상 |
| `http.path` | ✅ 항상 (PATH only — full URL 아님) |
| `http.status_code` | ⚠️ **에러(4xx/5xx)에만 태깅**, 2xx엔 없음(성공은 `error` 태그 부재로 추론) |
| 요청/응답 **body** | ❌ 없음 (예상된 OTEL-동급 한계 — 1순위 범위는 발견+메타데이터-only) |
| 다운스트림 host/port (`remoteEndpoint`) | ⚠️ CLIENT span에서 null — path만. target host는 SUT config로 매핑 |

### 캡처된 Zipkin v2 span 형상 (수신기 설계 근거)
```json
// 2xx 성공
{"traceId":"1234567890abcdef1234567890abcdef","parentId":"1234567890abcdef","id":"2ce7b7475c95811b",
 "kind":"CLIENT","name":"post","timestamp":1782280054177258,"duration":3457,
 "localEndpoint":{"serviceName":"default","ipv4":"172.24.0.3"},
 "tags":{"http.method":"POST","http.path":"/reservations"}}
// 5xx 에러
{"...":"...","kind":"CLIENT","tags":{"error":"500","http.method":"POST","http.path":"/reservations","http.status_code":"500"}}
```
(원본 9개 span: `captured-spans-evidence.ndjson`.)

## 설계 함의
1. **otel 모드와 통일된 정규화.** otel=OTLP CLIENT span, sleuth=Brave Zipkin CLIENT span을 같은
   "egress 레코드(method, path, status?, traceId)"로 환원. sleuth용 **Zipkin v2 수신기**를
   `OtlpTraceReceiver`와 평행하게 신설(traceId 키/HEX_32/per-trace 버퍼/evict 패턴 재사용).
2. **status 비대칭.** Brave 기본은 성공 status를 생략 → 정규화 시 "`error`/`http.status_code` 있으면
   그 값, 없으면 success(2xx류)로 간주". 발견(어느 endpoint를 호출하나)에는 method+path로 충분.
3. **기존 stub API와 정합.** testlib `HttpMockClient.stub(method, urlPath)`가 method+path로 키링 →
   Brave 산출과 1:1. 발견 결과를 그대로 외부 stub 메타로 환류 가능(body는 happy-minimal 유지).
4. **샘플링 주입으로 충분.** 기존 `SqlCaptureBackend.Scope.requestHeaders()` 주입 경로가 B3
   Sampled=1을 실어 나르므로 egress 캡처에 별도 SUT 설정 변경 불요.

## 미해결(설계에서 다룰 것)
- **리포터 주입성(적용성).** 샘플은 기본 상태에 `spring-cloud-sleuth-zipkin`이 **없었음**(우리가 추가).
  실제 sleuth SUT가 리포터를 안 가진 경우, 소스 수정 없이 실행 시점에 리포터를 얹을 수 있는가
  (launcher classpath/loader.path) vs "리포터 존재"를 전제조건으로 둘 것인가 — 설계 결정 필요.
- **다운스트림 host 식별.** path만으로 외부 시스템 구분이 모호할 때 SUT config(`*.url`) 매핑 규칙.
- **비표준/비-HTTP 클라이언트.** 표준 클라이언트 경유만 span 생성(otel·sleuth 공통) — 독자
  프레임워크/소켓은 1순위로 못 잡음(2순위 정적 recognizer 영역).
