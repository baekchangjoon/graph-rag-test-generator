package io.graphrag.builder.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-026: {@code .claude/skills/{provenance-analysis, triple-synthesis, trial-loop}/SKILL.md}
 * 3종의 구조 검사 — 결정적 CLI(T1~T3, {@link BuilderCli})와 에이전트 스킬 사이의 계약을 코드가 아닌
 * 문서(SKILL.md)로만 강제하는 조각이므로, 그 문서가 최소 요소를 갖췄는지를 이 unit 테스트가 기계적으로
 * 고정한다. 스킬 본문 자체의 실행 결과(에이전트가 실제로 완주하는지)는 REQ-027(E2E-B1, manual)의 범위다.
 */
class SkillPackagingTest {

    private static final Path REPO_ROOT = findRepoRoot();
    private static final List<String> SKILL_NAMES =
            List.of("provenance-analysis", "triple-synthesis", "trial-loop");

    /** frontmatter는 {@code ---\n...\n---} 블록. */
    private static final Pattern FRONTMATTER = Pattern.compile("(?s)\\A---\\s*\n(.*?)\n---\\s*\n");

    /** 한글(가-힣, 자모)이 하나라도 있으면 description이 한국어로 섞였다고 본다(frontmatter는 영어 관례). */
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u3131-\\u318E]");

    /**
     * {@code .claude/skills}를 담은 디렉토리를 user.dir에서 위로 걸어 올라가며 찾는다 — Gradle이
     * user.dir을 모듈 디렉토리(graph-rag-builder/)로 설정하든 repo root로 설정하든 안전
     * (MultiRootStaticE2E#findMultirootBase와 동일 관례).
     */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i <= 4; i++) {
            if (Files.isDirectory(dir.resolve(".claude").resolve("skills"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException(
                "Cannot locate .claude/skills from user.dir=" + System.getProperty("user.dir"));
    }

    private Path skillFile(String skillName) {
        return REPO_ROOT.resolve(".claude").resolve("skills").resolve(skillName).resolve("SKILL.md");
    }

    private String read(String skillName) throws Exception {
        Path file = skillFile(skillName);
        assertTrue(Files.isRegularFile(file), () -> "missing SKILL.md: " + file);
        return Files.readString(file);
    }

    @Test
    @DisplayName("REQ-026: 3개 SKILL.md 파일이 모두 존재한다 — provenance-analysis/triple-synthesis/trial-loop")
    void allThreeSkillFilesExist() {
        for (String name : SKILL_NAMES) {
            Path file = skillFile(name);
            assertTrue(Files.isRegularFile(file), () -> "missing SKILL.md: " + file);
        }
    }

    @Test
    @DisplayName("REQ-026: 각 SKILL.md의 frontmatter가 name/description을 갖고, description은 영어(트리거 매칭용)다")
    void frontmatterHasNameAndEnglishDescription() throws Exception {
        for (String name : SKILL_NAMES) {
            String content = read(name);
            Matcher m = FRONTMATTER.matcher(content);
            assertTrue(m.find(), () -> name + "/SKILL.md: frontmatter(---...---) 블록이 없음");
            String frontmatter = m.group(1);

            Matcher nameMatcher = Pattern.compile("(?m)^name:\\s*(\\S+)").matcher(frontmatter);
            assertTrue(nameMatcher.find(), () -> name + "/SKILL.md: frontmatter에 name: 필드가 없음");
            assertEquals(name, nameMatcher.group(1).trim(),
                    name + "/SKILL.md: frontmatter name이 디렉토리명과 일치해야 함");

            Matcher descMatcher = Pattern.compile("(?m)^description:\\s*(.+)$").matcher(frontmatter);
            assertTrue(descMatcher.find(), () -> name + "/SKILL.md: frontmatter에 description: 필드가 없음");
            String description = descMatcher.group(1).trim();
            assertFalse(description.isEmpty(), name + "/SKILL.md: description이 비어 있음");
            assertFalse(HANGUL.matcher(description).find(),
                    () -> name + "/SKILL.md: description은 트리거 매칭용 영어여야 하는데 한글 포함: "
                            + description);
        }
    }

    @Test
    @DisplayName("REQ-026: triple-synthesis SKILL.md는 provenance-report가 없으면 "
            + "provenance-analysis 스킬부터 실행하라는 선행 산출물 가드를 포함한다")
    void tripleSynthesisGuardsOnMissingProvenanceReport() throws Exception {
        String content = read("triple-synthesis");
        assertTrue(content.contains("provenance-report"),
                "triple-synthesis/SKILL.md: 선행 산출물(provenance-report) 언급이 없음");
        assertTrue(content.contains("provenance-analysis"),
                "triple-synthesis/SKILL.md: 선행 스킬(provenance-analysis) 지목이 없음");
    }

    @Test
    @DisplayName("REQ-026: trial-loop SKILL.md는 후보(triple candidates)가 없으면 "
            + "triple-synthesis 스킬부터 실행하라는 선행 산출물 가드를 포함한다")
    void trialLoopGuardsOnMissingCandidates() throws Exception {
        String content = read("trial-loop");
        assertTrue(content.contains("triple-synthesis"),
                "trial-loop/SKILL.md: 선행 스킬(triple-synthesis) 지목이 없음");
        assertTrue(content.toLowerCase(java.util.Locale.ROOT).contains("cand-")
                        || content.contains("후보"),
                "trial-loop/SKILL.md: 선행 산출물(후보 디렉토리) 언급이 없음");
    }

    @Test
    @DisplayName("REQ-026: provenance-analysis SKILL.md는 스킬 실행 순서(C1→C2→C3) 안에서 "
            + "자신이 첫 단계임을 명시한다(선행 산출물 없이 시작 가능)")
    void provenanceAnalysisDocumentsItselfAsFirstStep() throws Exception {
        String content = read("provenance-analysis");
        assertTrue(content.contains("triple-synthesis") || content.contains("trial-loop"),
                "provenance-analysis/SKILL.md: 전체 실행 순서(다음 스킬) 언급이 없음");
    }

    @Test
    @DisplayName("REQ-026: 각 SKILL.md가 \"마커만 채워라 — 마커 외 값 수정 금지\" 지시 문구를 포함한다")
    void eachSkillInstructsFillMarkersOnly() throws Exception {
        for (String name : SKILL_NAMES) {
            String content = read(name);
            assertTrue(content.contains("마커만"),
                    () -> name + "/SKILL.md: \"마커만\" 문구가 없음");
            assertTrue(content.contains("수정 금지") || content.contains("변경 금지"),
                    () -> name + "/SKILL.md: 마커 외 값 \"수정 금지\"류 문구가 없음");
        }
    }
}
