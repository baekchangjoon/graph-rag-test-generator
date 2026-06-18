# attach 모드 외부 HTTP 캡처 배선 — 설계

- 일자: 2026-06-18
- 브랜치: feat-attach-external-http

## 문제

attach 모드(사용자 docker-compose로 컨테이너 SUT를 분석)는 **외부 HTTP(downstream) 호출을 캡처하지
못한다**. 근거:

1. `AttachedComposeEnvironment.httpCapture()`가 `null`을 반환한다(`// attach v1: 외부 HTTP 캡처 미지원`).
2. attach는 호스트 `HttpCaptureServer`(임베디드 WireMock)를 띄우지 않는다.
3. `EndpointExplorationRunner`는 `httpCapture == null ? List.of() : httpCapture.drainNewExchanges()`로
   캡처를 건너뛴다.
4. `BuilderCli.runAttached`는 `--external-stubs`/`--sut-env`(특히 `{{wiremock}}` 치환)를
   `AttachedComposeEnvironment`로 전달하지 않는다.

docs/26 "v1 한계 #3"은 그 사유를 *"컨테이너 SUT가 호스트의 임베디드 WireMock에 기본 도달하지 못하므로"*
라고 적었다. 이 네트워킹 장애물은 OTEL SQL 캡처 작업에서 도입한 **`host.docker.internal:host-gateway`**
(컨테이너→호스트 도달)로 이미 해소됐다. 즉 남은 것은 "불가"가 아니라 "미배선"이다.

## 범위

- **포함**: attach 모드에서 SUT의 외부 HTTP 호출을 호스트 임베디드 WireMock으로 받아 캡처
  (`CapturedHttpCall`). `--sql-capture` **log/otel 무관하게 항상** 동작(외부 HTTP 캡처는 SQL 캡처와 독립한
  attach 기본 능력).
- **제외(비목표)**: 호스트 WireMock에 per-run 토큰 인증(단기 mock 서버라 데이터 유출 없음 — 0.0.0.0 노출
  한계는 docs에 명시). 컨테이너측 WireMock 서비스 방식. OTEL http-client span으로의 외부 HTTP 캡처(span에
  요청/응답 body가 없어 WireMock 저널을 대체 불가).

## 설계 (접근 A — OTLP 리시버 배선 미러링)

attach OTEL SQL 캡처에서 호스트 OTLP 리시버를 `host.docker.internal`로 도달시킨 것과 동일한 패턴으로,
호스트 임베디드 WireMock을 컨테이너가 `host.docker.internal:<port>`로 도달하게 한다.

### (A) HttpCaptureServer — host-reachable URL

`io.graphrag.builder.env.HttpCaptureServer`(WireMock `dynamicPort`)는 기본적으로 모든 인터페이스에
bind한다(컨테이너 도달 가능). 추가:
- `int port()` — WireMock 포트.
- `String hostBaseUrl()` — `http://host.docker.internal:<port>` (컨테이너 SUT가 외부 호출 redirect로 쓸 URL).
  기존 `baseUrl()`(loopback)은 호스트 프로세스(analysis)용으로 유지.

(WireMock이 loopback에만 bind하지 않음을 구현에서 확인/보장 — 기본 `WireMockConfiguration.options()`는
전 인터페이스 bind. 필요 시 명시.)

### (B) AttachedComposeEnvironment — 캡처 서버 소유

- 생성자에 nullable `HttpCaptureServer`를 받아 보관(runAttached가 주입; OTLP 리시버 소유 패턴과 동일).
- `httpCapture()`가 그 서버를 반환(현재 `null` → non-null). `explore()`의 `drainNewExchanges()` 경로가
  attach에서도 자동 동작(러너 변경 불필요).
- `close()`에서 서버 stop.

### (C) BuilderCli.runAttached — 배선 + override 주입

OTLP 리시버 배선과 같은 순서(서버를 override YAML 생성 *전*에 시작해 포트를 확정):
1. 호스트 `HttpCaptureServer`를 `config.externalStubsDir()`로 시작(외부 stub 로드).
2. `config.sutEnv()`의 `WIREMOCK_PLACEHOLDER`(`{{wiremock}}`)를 `httpCapture.hostBaseUrl()`로 치환한
   env 맵을 만든다(analysis는 loopback `baseUrl()`로 치환하던 것을 attach는 hostBaseUrl로).
3. 그 env를 OTEL env(otel 모드 시)와 합쳐 `OverrideComposeGenerator.Spec.extraEnv`로 app `environment`에 주입.
4. `addHostGateway=true`를 **attach에서 항상**(현재 otel 전용 → 분리; 외부 HTTP 도달에도 host-gateway 필요).
   Docker<20.10 host-gateway 미지원 경고는 기존 `warnIfHostGatewayUnsupported()` 재사용.
5. 시작한 서버를 `AttachedComposeEnvironment`에 전달(소유·stop·`httpCapture()` 노출).

`OverrideComposeGenerator`는 이미 `extraEnv`를 app `environment`에 병합하고 `addHostGateway`로 `extra_hosts`를
주입하므로 추가 변경 최소. (현재 `addHostGateway`가 otel 모드일 때만 true로 전달되던 호출부를 attach 항상
true로 바꾼다.)

### (D) E2E 수용 테스트

order-service는 EXPRESS 주문 시 `EXTERNAL_INVENTORY_URL`로 inventory를 호출한다(`InventoryClient`).
attach 실행에 `--external-stubs <dir> --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}`를 주면, 컨테이너 SUT의
inventory 호출이 호스트 WireMock(host.docker.internal)으로 가서 캡처돼야 한다.

수용 기준(`e2e/run-attach-ext-http-e2e.sh` 신규 또는 기존 attach e2e 확장):
1. attach 분석이 정상 완료(SUT 부팅·teardown clean).
2. graph.json에 inventory에 대한 **`CapturedHttpCall`**(외부 HTTP)이 ≥1건 — URL/메서드가 inventory 호출과
   일치, (가능하면) 응답 body·SUT가 읽은 응답 필드 보존.
3. host-gateway로 컨테이너→호스트 WireMock 도달 확인(캡처가 0이면 실패).
4. 기존 attach 회귀(`run-attach-e2e.sh`, `run-attach-otel-e2e.sh`) green 유지.

EXPRESS 주문 경로 도달을 위해 외부 stub(inventory minimal 응답)을 `--external-stubs`로 제공한다(e2e의 기존
`e2e/external-stubs` 재사용 가능).

## 영향 범위 / 위험

- `HttpCaptureServer`에 메서드 2개 추가(`port`/`hostBaseUrl`) — 기존 동작 무변경.
- `AttachedComposeEnvironment` 생성자 시그니처에 nullable 인자 추가 → 호출부(runAttached)만 갱신; 기존
  2-arg 생성자/테스트는 유지(편의 생성자).
- `runAttached`가 외부 stub/ sutEnv를 배선 → log 모드 attach도 host-gateway가 주입됨(의도된 변경; 외부
  HTTP 캡처가 항상 동작). host-gateway는 Docker 20.10+ 필요(경고만, 치명 아님).
- 보안: 호스트 WireMock이 0.0.0.0로 노출(런 동안만). 토큰 미적용(비목표) — docs/26에 한계로 명시.
- attach v1 한계: "외부 HTTP(downstream) 캡처 미지원"(docs/26 #3) 제거.

## Definition of Done

- [ ] E2E 수용 1~4 green (attach + `--sut-env {{wiremock}}` → inventory CapturedHttpCall 캡처; 기존 attach
  e2e 회귀 유지).
- [ ] 단위: `OverrideComposeGenerator`가 attach에서 항상 `extra_hosts host-gateway` + 치환된 외부 URL env
  주입; `AttachedComposeEnvironment.httpCapture()` non-null + close 시 stop; `HttpCaptureServer.hostBaseUrl()`.
- [ ] 전체 회귀(`./gradlew test`) green — analysis 모드/기존 attach 무변경.
- [ ] docs/26 갱신(v1 한계 #3 제거, attach 외부 HTTP 캡처 + host.docker.internal + 0.0.0.0 노출 한계 기술).
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.
