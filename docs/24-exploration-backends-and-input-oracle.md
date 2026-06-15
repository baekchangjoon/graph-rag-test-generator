# 탐색 백엔드 전략: out-of-process 관측 + in-process 입력 오라클

작성일: 2026-06-14
관련: `docs/23-input-generation-flow.md`, `docs/decisions/explorer-engines.md`

## 배경

graph-rag-builder는 **out-of-process**로 동작한다: SUT를 외부 JVM 프로세스로 띄우고
HTTP로 입력을 fuzzing하면서 JaCoCo로 커버리지를 얻어 `GraphAsset`(엔드포인트, ExploredPath,
캡처 SQL/HTTP, 시드, 스키마, 커버리지 리포트)을 만든다. test-generator는 이 그래프를 읽어
JUnit 통합 테스트를 생성한다. 빌더와 제너레이터는 **그래프 포맷으로만 결합**돼 있다.

입력 생성의 정적분석 신호(handler/서비스 비교식·문자열 동치·Bean Validation)는 얕다 — 소스에
리터럴로 박힌 값만 환류한다(`docs/23`). 더 깊은 분기(파생/복합/다변수)를 열려면 심볼릭/콘콜릭/
탐색기반 도구가 필요하지만, 이들은 **in-process·유닛 레벨** 전제라 우리 아키텍처와 안 맞는다
(콘콜릭 JPF 기각 사유와 동일 — `docs/decisions/explorer-engines.md`).

## 발상과 그 함정

**발상**: 기존 out-of-process를 옵션 B로 두고, in-process로 EvoSuite/심볼릭을 써서 커버리지를
얻고 그래프를 만드는 옵션 A를 추가한다. 두 옵션의 **산출물 포맷만 같으면** test-generator는
무관하게 소비한다.

**맞는 부분**: test-generator가 포맷-불가지론적인 건 사실이고, 빌더엔 이미 입력 엔진을 갈아끼우는
`PathExplorer` SPI가 있다(`docs/05`). 백엔드 분리 발상의 절반은 이미 깔려 있다.

**함정**: `GraphAsset` 포맷은 **중립 컨테이너가 아니라 HTTP-통합 사실을 인코딩**한다.
`ExploredPath`는 `sampleInput`=HTTP 바디, `expectedStatus`=HTTP 상태, `capturedSql`=실제
실행된 SQL, `capturedHttpCalls`=아웃바운드 외부 HTTP, `seeds`=필요한 DB 행을 담는다. 이는
**진짜를 HTTP로 실행해야만 나오는 관측값**이다.

EvoSuite/심볼릭은 유닛 레벨이다 — `controller.create(req)` / `service.classify(...)`를 in-JVM
직접 호출하고 의존성은 목으로 대체한다. 그래서 HTTP 바디·상태·실제 SQL·seed·아웃바운드 HTTP를
**원천적으로 채울 수 없다**. 따라서 "옵션 A가 같은 포맷을 만든다"는 게 가장 어려운 일이며, 억지로
채우면 SQL/seed/httpCall이 빈 저품질 그래프가 되어 test-generator가 유닛-ish 약한 테스트를 뱉어
통합테스트 목적이 반감된다.

## 결론: in-process는 "그래프 생성기"가 아니라 "입력 오라클"

in-process 도구의 고유 가치는 **분기를 여는 입력 값을 찾아내는 것**(우리 얕은 정적분석이 놓치는
`amount==7` 같은 마법값)이지 커버리지 측정·그래프 생성이 아니다(커버리지는 JaCoCo로 이미 함).
따라서 역할을 분리한다:

```
[옵션 A: in-process 입력 오라클]
   EvoSuite/심볼릭을 컨트롤러·서비스 클래스에 돌려
   분기 커버하는 DTO 입력값 수확  (예: CreateOrderRequest(amount=7, ...))
        │  DTO → JSON 직렬화
        ▼
[옵션 B: 기존 out-of-process 관측]
   그 바디를 떠 있는 SUT에 HTTP로 replay
   → 실제 status/SQL/seed/외부HTTP 관측 → GraphAsset (동일 포맷, 자연스럽게)
```

- **관측 파이프라인 1개, 포맷 1개** → 포맷 변환 문제 소멸.
- 꽂히는 자리는 새 백엔드가 아니라 **새 `PathExplorer`**(입력을 *제안*만; 실행·관측·그래프화는
  기존 코드 재사용). 아키텍처 변경 최소.
- EvoSuite 스위트 스폿(순수 로직 POJO=**서비스 계층**)이 분기가 사는 곳과 일치.

즉 "A/B 두 독립 백엔드"가 아니라 **"A=입력 발견(in-process) → B=관측(기존)으로 합성"** 이 옳은
분해다. A 단독으로 그래프까지 만들게 하면 저품질 그래프를 떠안는다.

## 마찰 (도입 시 예산)

- EvoSuite는 유닛/in-JVM — SUT 클래스를 classpath에 올려 계측. 부팅 Spring이 아니라 클래스 단위
  실행(컨트롤러는 목, 서비스는 순수라 잘 됨).
- 목 기반이라 유닛에서 찾은 입력이 실제 DB/HTTP에선 다른 경로일 수 있음 → **replay + 재관측**이
  필수(기존 파이프라인이 수행). 발산하는 입력은 자연히 걸러지거나 4xx로 관측됨.
- **비결정성**: EvoSuite는 난수/시간 사용 → `docs/04` 결정성과 충돌. 시드 고정 필요.
- 수확 방법: EvoSuite 생성 JUnit을 파싱해 DTO 인자 추출(brittle) vs 심볼릭(SPF)이 경로별 입력값을
  더 깔끔히 제공(단 JPF 제약 — 부팅 Spring이 아닌 순수 서비스엔 적용 여지).
- 빌드/런타임 비용, 유지보수.

## 적용 범위 게이트

`docs/23` 부록의 실측: 현재 코퍼스(order-service + petclinic + 8 MSA)는 입력 필드 기반 분기가
사실상 0건이다. 따라서 오라클의 실익은 **그런 분기가 유의미하게 존재하는 SUT에서만** 난다.
도입 판단은 `ExplorationReport.solverRelevantMissed` 누적치로 게이트한다(콘콜릭 복귀 트리거와 동일
기준, `docs/decisions/explorer-engines.md`).

## 구현 현황 (2026-06-14)

- **arm-level 커버리지 수정 완료** — 누적 exec data 분석(probe OR)으로 전환. count-union의
  arm-blind 한계(이진 분기 ~50% 캡) 해소. order-service create 8/16→16/16.
- **교체가능 인터페이스 `InputOracle` 도입** — `analyze(SutCode) → InputCandidates`(필드별 numeric/
  string 후보). 구현 2종:
  - `StaticLiteralOracle` — 기존 Spoon 비교식/문자열동치 추출 흡수(소스 srcDir).
  - `ConcolicOracle` — **ASM 심볼릭 스캔 + Z3**(bootJar 바이트코드). 입력 파생 정수 선형식을
    추적해 각 분기 경계 `coeff*field+const==0`을 Z3로 풀어 **소스에 없는 값**(예: `score*2==84`→42)을
    도출. 도구는 `org.ow2.asm`(JDK 추적)·`tools.aqua:z3-turnkey`(native 번들) — 버전 rot 없음.
- **배선** — BuilderCli가 두 오라클을 merge해 constraintDirected로 환류. **실증**: order-service
  promo에 파생 분기 `score*2==84` 추가 → concolic이 42 도출 → promo handler **10/10**(소스 리터럴로는
  불가능한 arm 커버). 전 단위 + BuilderE2eTest green.

## arm-aware path 보존 (완료 2026-06-14)

path 식별을 `status + arm-blind 분기집합` → `status + 요청별 probe 지문`(`CoverageFingerprint`,
SUT 자체 클래스 한정으로 프레임워크 노이즈 제거)으로 강화. true/false arm은 서로 다른 probe라
**발견 입력이 distinct path로 보존**된다. 지문이 없으면(테스트 fake) 분기집합으로 폴백.

실증: order-service promo가 **7개 distinct path**로 보존 — score=7(lucky)/**42(answer, Z3 도출)**/
99(jackpot)/tier=gold/vip/happy. 즉 concolic이 찾은 비-리터럴 값 42가 실제 생성 테스트가 된다.
4개 SUT 회귀 GREEN(무회귀), order-service app 44/48.

전 과정 검증 완료: **in-process 발견(static+concolic) → out-of-process HTTP 확정·관측 →
arm-aware 보존 → 각 발견 입력이 distinct 테스트.**

## 관측 대상 확장: HTTP 외 이벤트/메시지 핸들러 (완료 2026-06-15)

out-of-process 관측이 HTTP 엔드포인트에만 머물지 않는다. 이벤트 구동 SUT의 커버리지를 정확히 잡으려면
**관측 파이프라인이 비-HTTP 진입점도 같은 JaCoCo dump 모델로 다뤄야 한다**:

- **@KafkaListener consumer** — `KafkaCaptureRunner`가 토픽에 유효 이벤트를 발행하고, 발행 직전
  baseline dump(boot/seed 컷) + consumer 실행 후 dump delta로 핸들러 커버를 캡처한다. SQL을 안 쓰는
  consumer(예: Redis 기록)도 핸들러 분기가 잡힌다. consumer 루프는 HTTP 탐색보다 **먼저** 실행 →
  consumer가 쓴 행을 read 엔드포인트가 관측(read 보너스).
- **STOMP/WS 핸들러** — `WsCaptureRunner`도 교환별 dump delta로 핸들러 커버를 캡처.
- **집계** — Kafka/WS/HTTP의 누적 exec를 모두 `runWideExec`에 OR-병합하고, exploration 커버리지
  지표는 **전 루프 종료 후 1회** 산출한다. `exploration-report.json`의 `coveredAppClasses`(≥1 분기
  covered된 app 클래스)에 consumer/WS 핸들러 클래스가 포함된다.
- **실증**: HTTP-only 집계 → 전체 집계 전환으로 notification(Redis consumer) line 4→33%,
  analytics(kafka consumer) line 12→48%·branch 25→100%. consumer 없는 SUT(petclinic 등)는 무회귀.
  근본 원인·검증은 `docs/superpowers/plans/2026-06-15-kafka-consumer-and-constraint-input.md` 참조.

## 단계별 입력 발견 진행 (Stage 0–3b, 2026-06-15)

InputOracle(ASM+Z3) 위에, 더 흔한 분기 종류를 여는 단계들을 쌓았다. 각 단계는 동일 jar A/B로 실측.

- **Stage 0 — 유효 happy 합성**: `SampleInputSynthesizer`/`ReadInputSynthesizer`가 enum→첫 상수
  (`EnumConstantExtractor`), `LocalDate`→ISO, `*email`→유효값 합성. 역직렬화 실패(400)를 검증 진입으로
  전환. petclinic 33→47/253.
- **Stage 1/2 — conjunction + joint/enum 변이**: `ConstraintExtractor.extractConjunctions`(메서드 내
  `&&` 다필드 가드) + `InputMutator.enumValues`(enum 상수별)·`joint`(원자 동시 만족). 변이 우선순위를
  firstOrder 앞으로. petclinic 47→69/253, `tier==VIP && loyalty<500` true-arm 도달. (temporal/enum은
  Z3 불요 — 직접 값; ConcolicOracle은 요청 파생 산술에 계속 사용, 불변.)
- **Stage 3 — by-id 진입**: 비-GET by-id의 path-id + 리소스 시드(`happyInput` 병합), boolean 파라미터
  유효 합성, `extractEnumColumns`(가드 유래 enum 컬럼 시드, 읽기 500 방지). petclinic 69→113/253.
- **Stage 3b — mutating by-id 정합성**: 요청별 시드 리셋(`resetSeeds`)으로 상태 누적 제거 + 결정성 인지
  구체 어설션. **생성 by-id 테스트가 빈 DB에서 재현**(petclinic 16/16).
- **회귀 보호**: order-service에 **Booking 리소스**를 추가해 위 전 단계를 CI(e2e)가 라이브로 검증
  (e2e 22→45 tests). 비목표: 상태 의존 가드 양 arm concolic 변종(stale 과거날짜, capacity) = 향후.

## ConcolicOracle 지원 범위 (확장 중)

ASM 심볼릭 스캔(intra-method, 단일 필드)으로 입력 파생식을 추적, Z3로 경계를 푼다. 각 증분마다
order-service에 해당 분기를 추가해 distinct 테스트로 보존됨을 실증:

| 분기 형태 | 도출 값 (소스 리터럴 아님) | 실증 (order-service promo) |
|---|---|---|
| 정수 선형 등치/비교 `score*2==84` | score=42 | `score=42 → answer` |
| long 산술 `bonus*2==10000000000` | bonus=5000000000 (int 범위 밖) | `bonus=5e9 → -whale` |
| 문자열 길이 `code.length()==5` | "xxxxx" (길이5 문자열) | `code=xxxxx → -c5` |
| **2-필드 선형 inter-field** `loyaltyPoints==nights*600+7` | 튜플 `{loyaltyPoints:607, nights:1}` | `bookings 201`(필드별 변이로는 불가) |

`Sym`은 정수 선형식 `Σ(coeff·field)+const`를 **최대 2개 필드**까지 추적한다(3개째·진짜 곱 `x·y`는 top으로
bail). 비교 opcode(EQ/NE/LT/LE/GT/GE)를 threading해, 단일 필드는 경계 ±1, **2-필드는 `solveTuple`**로
동시충족 정수 튜플을 Z3 Optimize(합 최소화 → 작은·in-range 값)로 푼다. `InputMutator.interField`가 한
atomic 변이로 적용. 결정적 in-repo 승리: `InputCandidates.tuples` 채널은 additive(단일 필드 무회귀).

구현 노트: `INVOKEDYNAMIC`(문자열 concat) 처리, `LCMP`→a-b, 미처리 opcode는 깔끔히 bail(앞선
기록 보존). 미지원/보류(향후): 문자열 동치/접두사(Z3 string theory), **float rational 계수·enum 메서드
grounding**(petclinic `deposit*1.1<nights*rate`는 best-effort), 3+변수 동시해, enum ordinal, interprocedural 전파.
