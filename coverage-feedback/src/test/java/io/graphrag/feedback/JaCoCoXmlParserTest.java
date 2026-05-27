package io.graphrag.feedback;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JaCoCoXmlParserTest {

    @Test
    void parses_branch_and_line_coverage_totals() throws Exception {
        Path xml = locateResource("jacoco-sample.xml");
        CoverageReport report = JaCoCoXmlParser.parse(xml);

        // BRANCH: 3 covered, 3 missed → 0.5
        assertThat(report.branchCoverage()).isEqualTo(0.5);
        // LINE: 3 covered, 1 missed → 0.75
        assertThat(report.lineCoverage()).isEqualTo(0.75);
    }

    @Test
    void collects_lines_with_missing_branches_only() throws Exception {
        Path xml = locateResource("jacoco-sample.xml");
        CoverageReport report = JaCoCoXmlParser.parse(xml);

        // Lines 43 (mb=1) and 55 (mb=2) are missing — line 42 (mb=0) and VetService 10 are not.
        assertThat(report.missing()).hasSize(2);
        assertThat(report.missing().get(0).line()).isEqualTo(43);
        assertThat(report.missing().get(0).branchId())
                .isEqualTo("com.example.petclinic.OwnerService:43");
        assertThat(report.missing().get(1).line()).isEqualTo(55);
        assertThat(report.missing().get(1).branchesMissed()).isEqualTo(2);
        assertThat(report.missing().get(1).branchesCovered()).isEqualTo(1);
    }

    @Test
    void rejects_xml_with_doctype_xxe_payload() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE report [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <report><counter type="BRANCH" missed="0" covered="0"/></report>
                """;
        // The OWASP-hardened parser refuses DOCTYPE entirely — better to fail loudly than
        // process a malicious report.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> JaCoCoXmlParser.parseString(xxe));
    }

    @Test
    void returns_full_coverage_when_no_branches_present() throws Exception {
        String empty = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="empty"/>
                """;
        CoverageReport report = JaCoCoXmlParser.parseString(empty);
        assertThat(report.branchCoverage()).isEqualTo(1.0);
        assertThat(report.lineCoverage()).isEqualTo(1.0);
        assertThat(report.missing()).isEmpty();
    }

    private static Path locateResource(String name) {
        try {
            return Path.of(JaCoCoXmlParserTest.class.getClassLoader().getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("missing test resource: " + name, ex);
        }
    }
}
