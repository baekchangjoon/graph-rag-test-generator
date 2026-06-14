# 탐색 백엔드 전략: out-of-process 관측 + in-process 입력 오라클

작성일: 2026-06-14
관련: `docs/05-branch-exploration.md`, `docs/23-input-generation-flow.md`,
`docs/decisions/explorer-engines.md`

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
