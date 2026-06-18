# OTEL SQL 캡처 — 교차-SUT 검증 결과 (기본값 otel)

- 일자: 2026-06-18
- 대상 변경: SQL 캡처 기본값을 `otel`로 전환 (PR #56, feat-otel-sql-capture)
- 관련 문서: [spec](../specs/2026-06-18-otel-sql-capture-design.md), [plan](../plans/2026-06-18-otel-sql-capture.md)

## 배경

PR의 자동화 수용 테스트와 `run-e2e.sh`는 `order-service`(Postgres) 하나만 대상으로 한다. 기본값을
`otel`로 전환하면 모든 SUT가 OTEL 경로를 쓰므로, plan이 PoC 게이트 조건으로 명시한 **드라이버별 값
노출 여부**(특히 미검증이던 MySQL/MariaDB)를 외부 SUT로 확인했다.

## 방법

worktree 빌더(이 PR 코드)로 외부 SUT를 OTEL 기본 모드로 분석하고, 각 실행에서 다음을 수집했다.

- `OTEL SQL capture: otlp receiver` 로그 — OTEL 모드 활성 여부
- `entry span timeout` 횟수 — 컨테이너/프로세스가 OTLP 리시버에 도달 못 함(또는 agent 미export)
- `OTEL capture may be misconfigured` 횟수 — entry span은 왔지만 OTEL DB span이 0인데 로그엔 SQL이
  있는 경우(무음 폴백 신호, 이 PR에서 추가)
- `graph saved: … N sql` — 캡처된 SQL 수
- exploration coverage — 참고용(인증/시드 없이 `--budget-requests 40`이라 낮음)

SUT 소스/jar는 로컬 가용본, DB·Redis·Kafka는 Testcontainers(빌더 분석 환경)로 기동했다.

## 결과

| SUT | DB | JDK | OTEL 리시버 | entry-span timeout | misconfigured | 캡처 SQL | 분석 |
|---|---|---|---|---|---|---|---|
| order-service | Postgres | 17 | 활성 | 0 | 0 | 47 | OK (PR e2e) |
| petclinic | Postgres | 17 | 활성 | 0 | 0 | 6 | OK |
| community | **MySQL** | 8 | 활성 | 0 | 0 | 11 | OK |
| auth-user | **MySQL** | 17 | 활성 | 0 | 0 | 3 | OK |
| analytics | Postgres | 23 | 활성 | 0 | 0 | 10 | OK |
| mindgraph | Postgres | 11 | 활성 | 0 | 0 | 3 | OK |
| notification | Postgres | 17 | 활성 | 0 | 0 | 5 | OK |
| diary | Postgres | 23 | 활성 | 0 | 0 | — | 실패 (아래 — OTEL 무관) |

### MySQL 바인딩 환원 확인 (community)

OTEL이 SQL 텍스트뿐 아니라 bind 값까지 환원함을 확인했다.

```
INSERT post binds=[sample-category, sample-content, 2026-06-18 08:44:49.834, 0]
SELECT post binds=[probe-id-94143]
SELECT post binds=[14b771f6-a35e-413b-97c5-5765e8893720]
```

## 결론

- 실행된 8개 SUT 전부에서 OTEL-문제 신호가 0이었다(timeout=0, misconfigured=0). 기본값 `otel`이
  **Postgres와 MySQL 양쪽**, Spring Boot/JDK 8·11·17·23, 서로 다른 앱에서 로그 폴백 없이 동작한다.
- MySQL 드라이버에서 OTEL JDBC 캡처가 SQL 텍스트와 bind 값까지 환원한다(community/auth-user). plan의
  "드라이버별 값 노출" 게이트 조건이 MySQL에서 충족된다.
- **MariaDB**는 가용 SUT가 없어 실행하지 못했다(MySQL 호환 드라이버). `DbConfig.Type.MARIADB`는 존재하며,
  필요 시 별도 검증 대상으로 남긴다.

## 미해결 항목

### diary 분석 실패 — 기본전환과 무관

diary는 `rc=1`로 실패했으나 OTEL 신호는 0이었다. 원인은 `POST /internal/diaries`에서 **HTTP 요청 자체가
timeout**(`HttpTimeoutException`)되고 빌더가 이를 처리하지 못해 전체 분석을 중단한 것이다. SQL 캡처
backend는 HTTP 응답 이후에만 관여하므로 OTEL이 원인일 수 없다.

`--sql-capture log`로 재실행해도 같은 지점에서 같은 예외로 실패했다 — 즉 기본전환 이전부터 존재한
빌더 동작이다. 별도 후속으로 정리한다(권장: `doSend`가 특정 엔드포인트의 `HttpTimeoutException`을 전체
중단이 아니라 해당 엔드포인트 skip으로 처리). 함께 관측된 `identity resync skipped … COALESCE types text
and integer cannot be matched` 경고도 OTEL과 무관한 기존 빌더 동작이다.
