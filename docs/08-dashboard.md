# 08 — test-state-dashboard

생성된 테스트 실행 중 어떤 testId가 어떤 자원(DB row, HTTP stub, socket session)을 가지고 있는지 추적하는 디버깅용 별도 서비스.

## 책임 경계

```
[testlib] — 자원 등록/해제 시 이벤트 발행 (fire-and-forget)
              ↓ HTTP POST (best-effort, 짧은 timeout)
[test-state-dashboard] — 받아서 상태 유지 + UI/API 제공
              ↓
[사람 / Claude / 다른 도구] — 디버깅용 조회
```

대시보드 장애가 테스트를 실패시키지 않음. `DASHBOARD_URL` 미설정 시 noop reporter 사용.

## 데이터 모델

```yaml
TestRun:
  testId: string
  testClass: string
  testMethod: string
  runId: string                       # batch 식별
  startedAt: timestamp
  endedAt: timestamp | null
  status: ACTIVE | CLEANED | LEAKED | FAILED
  resources:
    db_rows:
      - { table, key_column, key_value, inserted_at }
    http_stubs:
      - { stub_id, url_pattern, scope_baggage_value, created_at }
    socket_sessions:
      - { session_id, mock_port, created_at }
  cleanup_at: timestamp | null
  leak_detected: bool
```

## 이벤트 스키마

이벤트 필드의 근거는 `shared-model/src/main/java/io/graphrag/model/` 의 이벤트 DTO다.

핵심 이벤트:
- `SCOPE_CREATED` / `SCOPE_CLEANED`
- `DB_ROW_INSERTED` / `DB_ROW_DELETED`
- `HTTP_STUB_REGISTERED` / `HTTP_STUB_REMOVED`
- `SOCKET_SESSION_OPENED` / `SOCKET_SESSION_CLOSED`
- `AUTH_TOKEN_ISSUED`

## 누수 감지 (Leak Detector)

```yaml
leak-detector:
  default_ttl: 300s                   # 시작 후 N초 내 cleanup 없으면 의심
  scan_interval: 30s                  # 누수 스캔 주기
  # 아래는 미구현(계획):
  ttl_overrides:
    slow_suite: 900s                  # 특정 suite는 더 길게
  reaper:
    enabled: false                    # 기본 비활성 (안전)
    confirmation_wait: 60s            # LEAKED 표시 후 reaper 까지 대기
```

현 구현(`LeakDetector`)은 TTL 경과한 ACTIVE 스코프를 **LEAKED로 표시만** 한다. Reaper는 의도적으로 미구현.

### LEAKED 판정 흐름

```
1. SCOPE_CREATED 수신 → ACTIVE
2. (TTL 경과 + SCOPE_CLEANED 없음) → LEAKED
3. 대시보드 UI에 빨간색 배지
4. 알람 채널 (콘솔 + Slack webhook 옵션)
```

### Reaper 안전장치 (미구현, 계획)

자동 cleanup을 도입할 경우의 위험성:
- 기본 비활성. 명시적 활성 필요
- 활성 시에도 **scope-reachable 자원만** 처리
- 모든 reaper 액션은 별도 audit 로그
- "dry-run" 모드 가능

## 대시보드 API

스펙의 근거는 `test-state-dashboard/src/main/java/io/graphrag/dashboard/DashboardController.java`다.

읽기:
- `GET /active`                       현재 ACTIVE 테스트
- `GET /leaked`                       누수 의심
- `GET /test/{testId}`               상세

쓰기:
- `POST /events`                      testlib가 이벤트 발행

## Web UI

간단한 단일 페이지로 충분:

```
┌─────────────────────────────────────────────────────────┐
│ 활성 테스트 (3)                                          │
├─────────────────────────────────────────────────────────┤
│ testId         | class           | started  | resources  │
├─────────────────────────────────────────────────────────┤
│ ordpost-a1b2c3 | OrdersPostTest  | 0.5s ago | u(1), s(1) │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 누수 의심 (1)                                            │
├─────────────────────────────────────────────────────────┤
│ testId         | last seen | leaked resources            │
├─────────────────────────────────────────────────────────┤
│ ordpost-x9y8z7 | 35s ago   | users.id=ordpost-x9y8z7-user│
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 테이블별 현재 row (users)                                 │
├─────────────────────────────────────────────────────────┤
│ key                       | owner testId                 │
├─────────────────────────────────────────────────────────┤
│ ordpost-a1b2c3-user       | ordpost-a1b2c3              │
│ ordpost-x9y8z7-user       | ordpost-x9y8z7 (LEAKED?)    │
└─────────────────────────────────────────────────────────┘
```

## 알람 채널

`LEAKED` 진입 시:
- 콘솔 로그 (default)
- `SLACK_WEBHOOK_URL` 설정 시 Slack 발송
- `WEBHOOK_URL` 설정 시 POST
- (SPI 형태로 추가 가능)

```java
interface LeakDetectorAlertChannel {
    void onLeaked(TestRun testRun);
}
```

## 영속화 (미구현, 계획)

현 구현(`TestRunStore`)은 in-memory만 지원하며, 메모리 상한(`MAX_RUNS`) 초과 시 종료 상태(CLEANED/LEAKED) run부터 제거한다. 아래는 계획:
- SQLite (단일 노드)
- PostgreSQL (멀티 노드 운영 시)
- 영속화 활성 시 history 조회 범위 확장

## 실행 모드

```
환경변수: DASHBOARD_URL
  - 설정됨: dashboard-reporter 어댑터 활성
  - 없음: noop-reporter, 아무것도 안 함
```

CI에서 빠른 실행 시 noop, 로컬 디버깅 시 활성. 선택 가능.

## 자원 일관성 검증 (미구현, 계획)

이벤트 손실 가능성 (fire-and-forget):
- 옵션: 일정 주기로 DB row 실제 스캔 + 대시보드 상태와 비교
- 차이 발견 시 재동기화
- 무거우니 디버깅 모드에서만 활성

```yaml
dashboard:
  reconciliation:
    enabled: false
    interval: 60s
    jdbc_url: ${JDBC_URL}             # 대시보드가 직접 DB 조회
```
