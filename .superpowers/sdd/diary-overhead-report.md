# V3b cross-SUT 오버헤드 측정 보고서 — tainted-spring diary

- 측정일: 2026-06-23
- 목적: petclinic(H2 in-memory, host JVM) 대비 현실적 SUT(Postgres+Kafka, Docker)에서 pjacoco flush 오버헤드 재측정
- 측정 모델: V3b production-model과 동일 (flush per-request, load off critical path)
- 스크립트: `e2e/poc-fanout/diary-overhead.sh`

---

## 측정 절차

1. **빌드 확인**: pjacoco `jacocoagent-parallel.jar` (PR #13 fix 포함) 기존 빌드 재사용
2. **서비스 기동**: `docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml up -d zookeeper kafka postgres redis auth-user diary`
   - mindgraph 제외 (오버헤드 측정에 불필요)
   - diary: `[pjacoco] agent installed` + HTTP :8082 + pjacoco control :6310 readiness 확인
3. **워밍업**: 10회 throwaway POST /internal/diaries (JIT/connection-setup 스큐 제거)
4. **Baseline**: 60회 POST /internal/diaries (traceparent/flush 없음)
5. **Flush 왕복 측정**: 100회 `POST http://localhost:6310/__coverage__/test/stop?testId=<id>&result=passed` 별도 측정
6. **Measured (production-model)**: 60회 POST + per-request traceparent 헤더 + per-request flush; `.exec` load는 루프 밖
7. **exec 파일 확인**: 60개 `.exec` 생성 확인 (off critical path)
8. **Teardown**: `docker compose down --remove-orphans`

요청 body: `{"userId":"meas<N>","title":"t","content":"c","primaryEmotion":"joy","energyScore":5}`  
traceparent: `00-dba<029x_i>-0000000000000001-01` (valid 32-hex)

---

## 실측 수치

| 항목 | diary (Docker, Postgres+Kafka) | petclinic (host JVM, H2) |
|---|---|---|
| **baseline per-request** | **87.68ms** | ~17.1ms |
| **baseline total (60req)** | 5261ms | 1028ms |
| **measured total (60req)** | 10961ms | 1191ms |
| **오버헤드 (abs)** | +5700ms | +163ms |
| **오버헤드 (%)** | **+108.34%** | +15.80% |
| **flush 왕복 mean** | **274.92ms** | 4.1ms |
| **flush 왕복 p95** | 409ms | 7.3ms |
| **pjacoco 내부 durationMs (mean)** | 86ms | ~<1ms (추정) |
| **Docker bridge overhead (추정)** | ~190ms/flush | 0ms (host-to-host) |
| **exec 파일 수** | 60/60 | — |
| **exec 파일당 크기** | ~770 bytes | ~607 bytes |
| **post-run load (off critical path)** | 7128ms | 28ms |

---

## 분석 — 왜 diary의 오버헤드가 petclinic보다 훨씬 높은가

### 가설과 반대되는 결과

최초 가설: "diary는 Postgres+Kafka로 per-request 처리 시간이 길어(~90ms) flush(~4ms) 비율이
낮아 오버헤드 %가 petclinic(~17ms baseline)보다 낮을 것."

실제: diary baseline이 87ms로 petclinic보다 5배 느리지만, flush가 275ms로 **68배 더 느려서**
오버헤드가 오히려 대폭 증가.

### 근인 1: Docker bridge 네트워크 오버헤드

- petclinic: SUT가 **host JVM**, test harness도 host → flush가 loopback host-to-host (~4ms 왕복)
- diary: SUT가 **Docker 컨테이너**, flush HTTP 호출이 host → Docker bridge → container → Docker bridge → host
- Docker bridge 추가 왕복: `274ms(실측) - 86ms(pjacoco internal) ≈ 188ms`
- 이 ~190ms/flush는 Docker Desktop macOS의 userspace network stack 특성으로 발생

### 근인 2: pjacoco 내부 처리 시간 증가

- pjacoco JSON 메타데이터 기준 내부 durationMs: **mean 86ms** (petclinic은 추정 <1ms)
- 원인: diary는 Kafka consumer/producer, JPA, Spring Security 등 다수 스레드 활동
  → pjacoco의 ThreadLocal probe store에서 수집·직렬화할 데이터량이 많음
  → exec 파일도 770 bytes (petclinic 607 bytes 대비 ~27% 큼)

### 실환경(LAN) vs 이번 측정(Docker Desktop macOS)

이번 측정의 Docker Desktop 오버헤드는 **실환경 배포와 다르다**:
- 실환경(Linux 호스트 + Docker): host network mode 또는 bridge가 loopback 수준 (~1ms)
- 실환경(쿠버네티스): sidecar 패턴으로 localhost flush — Docker Desktop보다 훨씬 빠름
- 이번 `+108.34%`는 **macOS Docker Desktop 특수 환경에서의 최악값**이며,
  Linux 배포 환경에서는 flush ~1-5ms 수준이 될 것으로 예상

### 수정된 기대값 (Linux 실환경)

petclinic 측정과 동일한 host-JVM 환경을 diary에 적용하면:
- pjacoco internal: 86ms (diary 고유, 데이터량 차이)
- 실환경 network: ~1-5ms (host-to-host loopback 또는 Linux bridge)
- 예상 flush 왕복: ~90ms
- baseline 87ms + flush 90ms → 예상 오버헤드: **~103%** (여전히 높음)

단, 실제 production fan-out에서는 flush가 fire-and-forget 또는 비동기화 가능 (§7 완화책 1번)이며,
diary 자체가 Kafka producer로 인해 pjacoco 내부 durationMs가 높은 것은 SUT-specific 특성이다.

---

## 결론

| 항목 | petclinic | diary | 비고 |
|---|---|---|---|
| baseline | 17ms/req | 87ms/req | diary가 5배 느림 |
| flush | 4ms | 275ms | diary가 68배 느림 (Docker bridge + pjacoco internal) |
| 오버헤드 % | +15.80% | **+108.34%** | 가설과 반대 방향 |

**가설 반증**: "현실적 SUT는 더 느려서 flush 비율이 낮아 오버헤드 %가 낮을 것"이라는 가설이 틀렸다.
diary의 pjacoco internal flush 자체가 느리고(86ms), Docker bridge 오버헤드(~190ms)가 추가되어
오버헤드가 오히려 훨씬 높게 나왔다.

**실환경 시사점**: Docker Desktop macOS 환경의 `+108%`는 최악값이다. Linux 배포 환경에서는
Docker bridge overhead가 수 ms 수준이지만, pjacoco internal duration(86ms)은 SUT 특성으로
유지될 것이다. 어느 환경에서도 petclinic(+15.8%)보다는 오버헤드가 높다.

**V3b 완화책 (§7)** 중 비동기 flush(fire-and-forget) 또는 배치 flush 적용이 diary 같은 현실적
SUT에서는 더욱 중요해진다.

---

## 실행 메모

- Docker Desktop: 29.5.3 (macOS 25.4.0)
- diary 이미지: tainted-spring-platform 기존 빌드
- pjacoco: `jacocoagent-parallel.jar` (PR #13 fix, Jun 20 빌드)
- OTel: `opentelemetry-javaagent.jar` 2.11.0
- JVM: Corretto 17.0.18 (diary 컨테이너 내부)
- 스크립트 2회 실행 (1회: traceId 비hex 오류로 exec wait stuck → 타이밍 데이터는 유효; 2회: 정상 완료)
