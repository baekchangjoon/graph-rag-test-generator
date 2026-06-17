# 테스트 커버리지 개선 추이 (graph-rag-builder)

작성: 2026-06-16

이 문서는 `graph-rag-builder`(SUT를 탐색해 그래프/테스트를 생성하는 도구)의 입력 발견 능력이
단계적으로 향상되며 **벤치마크 SUT의 분기 커버리지**가 어떻게 상승해 왔는지 기록한다.

## 측정 방식

- **벤치마크 SUT**: `spring-petclinic`(REST fork) — 다양한 검증/상태 가드를 가진 표준 벤치마크.
- **지표**: `exploration-report.json`의 **APP-AGGREGATE 분기 커버리지** = `coveredAppBranches / totalAppBranches`.
  분모(`totalAppBranches = 253`)는 SUT 바이트코드로 고정 — 도구가 탐색으로 **연** 분기 수만 변한다.
- **방식**: 도구가 SUT를 직접 부팅(Testcontainers + JaCoCo)하고 분기를 탐색하며 측정. 같은 jar에 대해
  도구 버전만 바꿔 A/B 실측 → 순수하게 "입력 발견 능력"의 기여를 분리한다.

> 데이터 출처: **Stage 0~3**(33→113)은 이전 세션 측정(메모리 `input-discovery-staged-roadmap` 기록),
> **47.0%→57.3%**(119→145)는 2026-06-16 세션에서 **동일 jar에 도구 버전만 바꿔 직접 재측정**한 값이다.
> 측정 시점·SUT 버전 차이로 단계 경계의 절대값은 ±소폭 오차가 있을 수 있으나, **추세와 각 단계의 기여는
> 동일 방식으로 검증**됐다.

## 추이 (petclinic, covered/253 분기)

```
 % │
60 │                                              ● 57.3%  (a #40 / #5 #41)
   │                                        ● 51.8%  (@Controller 폼 #39)
50 │                                  ◦ 47.0%  (이전 세션 종료 baseline)
   │
   │                    ● 44.7%  (Stage 3: by-id 진입 #22)
40 │
   │
30 │           ● 27.3%  (Stage 1+2: conjunction/joint #21)
   │
20 │     ● 18.6%  (Stage 0: 유효 happy #20)
   │  ● 13.0%  (정적 인덱싱만 — happy 입력 없음)
10 │
   └────────────────────────────────────────────────────
     static  S0    S1+2   S3    …auth/S4…  #39   #40/#5
```

| 단계 | covered/253 | % | PR | 핵심 개선 |
|---|---|---|---|---|
| 정적 인덱싱만 | 33 | 13.0% | — | 엔드포인트/스키마만, 유효 입력 없음 |
| **Stage 0** 유효 happy 합성 | 47 | 18.6% | #20 | enum 첫 상수·ISO 날짜·이메일 합성 (`EnumConstantExtractor`) |
| **Stage 1+2** conjunction/joint | 69 | 27.3% | #21 | `&&` 다필드 가드 동시충족 (`extractConjunctions`, joint 변이) |
| **Stage 3** by-id 진입 | 113 | 44.7% | #22 | 리소스 시드로 `/{id}` 엔드포인트 진입, 가드 유래 enum 컬럼 시드 |
| Stage 3b~4 · auth (누적) | ~119 | 47.0% | #23~ | fresh-DB by-id, 상태가드 양-arm, 음성-인증 거부 arm |
| **#3** @Controller 폼 인덱싱 | 131 | 51.8% | **#39** | `@Controller` 웹폼(form-urlencoded) 인덱싱·탐색 (`ParamKind.FORM`) |
| **(a)** 클래스레벨 path 변수 역추출 | 145 | **57.3%** | **#40** | `@ModelAttribute` 헬퍼의 path 변수를 PATH로 역추출 → 폼 핸들러 진입 |
| **#5** 상태머신 다중 전이 | 145 | 57.3% | **#41** | ENUM `==` 가드 다중 변종(petclinic은 해당 가드 부재 → 무변) |

**누적: 13.0% → 57.3% (+44.3pp). 2026-06-16 세션 기여분: 47.0% → 57.3% (+10.3pp).**

## 2026-06-16 세션 상세 (이번 개선의 분리 측정)

동일 petclinic jar에 도구 버전만 바꿔 측정 — 각 PR의 순수 기여:

| 버전 | covered/253 | Δ | 인덱싱 endpoints | 비고 |
|---|---|---|---|---|
| baseline (이전 main) | 119 (47.0%) | — | 16 | REST-only |
| + #39 @Controller 폼 | 131 (51.8%) | **+12** | 23 | `@Controller` 7개 폼 핸들러 신규 인덱싱 |
| + #40 path 변수 역추출 | 145 (57.3%) | **+14** | 23 | `post-owners-ownerid-pets-new` 등 폼 **진입** 해금(0/12→커버) |
| + #5 상태머신 다중 전이 | 145 (57.3%) | +0 | 23 | petclinic에 ENUM `==` 상태 가드 없음(order-service 벤치마크로 검증) |

- **#40이 최대 기여(+14)**: #39로 폼이 인덱싱돼도 클래스레벨 `{ownerId}`(`@ModelAttribute`에서만 해석)를
  PATH로 못 잡아 `findOwner`가 5xx로 막혔던 것을 역추출로 해소 → 폼 바인딩/검증 분기가 대거 열림.
- **REST 엔드포인트 무회귀**: 16개 `@RestController` 엔드포인트의 covered/total 분기는 전 단계에서 **불변**
  (이번 작업이 기존 경로를 깨지 않음을 동일-jar 비교로 확인).
- **#5는 petclinic 무변·order-service에서 실증**: petclinic엔 enum 상태 전이 가드가 없어 APP-AGGREGATE는
  그대로지만, order-service `BookingController.advance`(200/409/410)로 다중 전이 arm 캡처를 결정적으로 검증.

## 보류 작업 (ROI 평가)

| 작업 | 상태 | 사유 |
|---|---|---|
| **#4** float inter-field | 설계+`Rational` 코어 보존(미머지) | 실측 SUT 8종에 순수 float inter-field 가드 부재, enum은 conjunction이 이미 커버 |
| **(b)** entity-Formatter | 설계 노트만 | "폼 커맨드 정확 선택" 선결 + Formatter parse SUT-특화 + petclinic 한정 실효 |

두 작업 모두 3-모델 리뷰로 **큰 코어 비용 대비 실측 실효가 낮음**이 확인되어, 설계를 보존하고 구현을 보류했다.

## 방법론

매 단계는 **동일 jar A/B 실측 + CI 회귀화**로 검증했다: ① 설계 문서 → 3-모델(Sonnet/Gemini/GPT) 리뷰 →
triage → ② double-loop TDD(E2E 수용 먼저 RED) → ③ 전 SUT 회귀 스윕(order-service e2e + petclinic +
MSA 정적/실측) → ④ spec-compliance + code-quality 리뷰 → PR. 커버리지 상승은 항상 **회귀 0**를 동반했다.
