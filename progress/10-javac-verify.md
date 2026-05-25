# Progress: 생성 코드 javac 컴파일 검증

**Date**: 2026-05-25
**Task**: #17 생성 코드 javac 컴파일 검증
**Result**: 단일/멀티-path 합성 결과가 javac로 실 컴파일 통과

## 산출물

```
test-generator/src/main/java/io/graphrag/generator/verify/
├── CompileResult.java        # success + 진단 메시지
└── JavaSourceCompiler.java   # javax.tools.JavaCompiler 호출
```

`testImplementation`에 `libs.restassured` + `libs.hamcrest` 추가 (생성 코드가 참조하는 라이브러리가 컴파일 classpath에 필요).

## 테스트 (4, all GREEN)

- 단순한 valid 클래스 컴파일 OK
- 의도적 syntax error 클래스 → success=false + diagnostics 포함
- `TestSynthesizer.synthesize()` 출력 컴파일 OK
- `TestSynthesizer.synthesizeMulti()` 출력 컴파일 OK

## 의미

도구 2가 생성하는 코드가 **실제 javac로 컴파일** 됨을 자동 검증. 합성 로직 변경 시 회귀 즉시 탐지.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/04 self-check (compile) | OK |
| LLM 없는 결정적 합성 + 자동 검증 | OK |
| 합성 코드 invalidate 회귀 즉시 탐지 | OK |

## 후속 (Phase 1 잔여)

- #15 JaCoCo 커버리지 측정 통합
- #16 Coverage-guided fuzzer
- #18 MyBatis Interceptor 캡처
- #19 Phase 1 종합 검수
