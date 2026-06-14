# 의사결정: shared-model 스키마 재정의

날짜: 2026-06-10 / 단계: 0.2

## 배경

docs 03/04/07/08이 참조하는 `SCHEMAS.md`(공통 데이터 모델 + API 계약 원문)가
리포에 존재하지 않는다 (이전 시도에서 유실).

## 결정

docs 03(캡처 사실), 04(GenerationRequest/Result, 규칙), 06(parallel_safety_report),
08(이벤트 유형)의 서술로부터 Phase 0에 필요한 최소 스키마를 Java record로 재정의한다.

- 그래프 사실: `Endpoint`, `EndpointParam`, `ExploredPath`, `CapturedSql`,
  `SqlBinding`(origin: API_PARAM/LITERAL/COMPUTED), `TableSchema`, `ColumnSchema`,
  `ForeignKey`, 묶음 루트 `GraphAsset`
- 도구 2 계약: `GenerationRequest`, `GenerationResult`, `GeneratedFile`,
  `ParallelSafetyReport`, `SerialRequired`, `AuthMode`
- 대시보드 계약: `TestEvent`, `EventType` (docs/08의 9개 이벤트)
- 직렬화: Jackson 단일 `ObjectMapper`(`Json.mapper()`) — unknown 필드 무시
  (전방 호환), ISO-8601 시간

## Phase 0에서 의도적으로 제외한 필드

(갱신 2026-06-14: 아래 대부분이 후속 Phase에서 구현됨. 현재 남은 제외는
`Endpoint.requiredRoles` 하나뿐.)

- ~~`ExploredPath.branches` / path constraint~~ → 구현됨:
  `ExploredPath.branchesTaken`(`List<BranchRef>`) + `ExploredPath.constraints`(`List<String>`)
- ~~`CapturedHttpCall`~~ → 구현됨: 전체 record로, `GraphAsset.httpCalls`에 수록
- ~~`PropagationInfo`(OTEL baggage)~~ → 구현됨: `CapturedHttpCall.baggagePropagated`(boolean)로 실현
- ~~`CapturedSocketIO`~~ → STOMP/WS는 `WsEndpoint` + `WsExchange`로 실현(소켓IO record는 미채택)
- `Endpoint.requiredRoles` — Spring Security 분석 도입 시 (유일한 잔여 제외)

## 영향

- 모든 모듈이 shared-model에만 의존해 계약을 공유한다 (docs/02의 shared/model 역할).
- 스키마 확장은 필드 추가 + 라운드트립 테스트 추가로 진행. unknown-field 무시로
  구버전 그래프 파일과의 전방 호환 확보.
