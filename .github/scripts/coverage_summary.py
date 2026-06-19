#!/usr/bin/env python3
"""JaCoCo 집계 XML에서 전체 커버리지를 읽어 PR 코멘트용 마크다운 표를 출력한다.

usage: coverage_summary.py <jacocoAggregatedReport.xml> [scope-label]

XML에 집계 카운터가 없으면(빈/깨진 리포트) "0.0%" 오보 대신 비정상 종료한다.
"""
import sys
import xml.etree.ElementTree as ET

TYPES = ["INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS"]


def pct(cov, mis):
    den = cov + mis
    return (100.0 * cov / den) if den else 0.0


def counters(node):
    out = {}
    for c in node.findall("counter"):
        out[c.get("type")] = (int(c.get("covered")), int(c.get("missed")))
    return out


def main(path, scope=None):
    # 입력은 빌드가 직접 생성한 신뢰된 jacoco XML(공격자 제어 아님)이고, stdlib ElementTree는
    # 기본적으로 외부 DTD/엔티티를 가져오지 않으므로 XXE 위험이 없다(defusedxml 의존성 불필요).
    root = ET.parse(path).getroot()
    overall = counters(root)

    # 빈/깨진 집계(exec 미병합 등) → 그럴듯한 0.0%를 게시하지 말고 실패 신호.
    if "INSTRUCTION" not in overall:
        sys.stderr.write(f"error: no INSTRUCTION counter in {path} (empty/garbage report)\n")
        sys.exit(1)

    lines = ["## 🧪 자체 테스트 커버리지 (제품 모듈 집계)", ""]
    if scope:
        lines += [f"_측정 범위: {scope}_", ""]
    lines.append("| 지표 | 커버리지 | covered/total |")
    lines.append("|---|---|---|")
    for t in TYPES:
        if t in overall:
            cov, mis = overall[t]
            lines.append(f"| {t.capitalize()} | **{pct(cov, mis):.1f}%** | {cov}/{cov + mis} |")

    # 패키지 루트(=Gradle 모듈 그룹) 단위 INSTRUCTION 커버리지 — group이 있으면 사용.
    groups = root.findall("group")
    rows = []
    for g in groups:
        gc = counters(g)
        if "INSTRUCTION" in gc:
            cov, mis = gc["INSTRUCTION"]
            rows.append((g.get("name"), pct(cov, mis), cov, cov + mis))
    if rows:
        lines += ["", "<details><summary>모듈별 Instruction 커버리지</summary>", ""]
        lines.append("| 모듈 | 커버리지 | covered/total |")
        lines.append("|---|---|---|")
        for name, p, cov, tot in sorted(rows):
            lines.append(f"| {name} | {p:.1f}% | {cov}/{tot} |")
        lines.append("</details>")

    lines += ["", "_HTML 상세 리포트는 이 실행의 `coverage-html` 아티팩트에서 받을 수 있다._"]
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
