package io.graphrag.builder.coverage;

import io.graphrag.model.BranchRef;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * boot jar의 BOOT-INF/classes(SUT 자체 코드)만 분석해 분기 커버리지를 계산한다.
 * 라이브러리(BOOT-INF/lib)는 의도적으로 제외 — "AllBranches"는 SUT 분기 기준 (docs/05).
 */
public class BranchCoverageAnalyzer {

    private static final String CLASSES_PREFIX = "BOOT-INF/classes/";

    private final Path bootJar;

    public BranchCoverageAnalyzer(Path bootJar) {
        this.bootJar = bootJar;
    }

    public BranchCoverage analyze(ExecutionDataStore executionData) {
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
        try (ZipFile zip = new ZipFile(bootJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(CLASSES_PREFIX)
                        || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    analyzer.analyzeClass(in, entry.getName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to analyze boot jar: " + bootJar, e);
        }

        Set<BranchRef> covered = new LinkedHashSet<>();
        Set<BranchRef> missed = new LinkedHashSet<>();
        int total = 0;
        for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
            String classFqn = classCoverage.getName().replace('/', '.');
            for (IMethodCoverage method : classCoverage.getMethods()) {
                for (int line = method.getFirstLine(); line <= method.getLastLine(); line++) {
                    ICounter branches = method.getLine(line).getBranchCounter();
                    if (branches.getTotalCount() == 0) {
                        continue;
                    }
                    total += branches.getTotalCount();
                    for (int k = 0; k < branches.getTotalCount(); k++) {
                        BranchRef ref = new BranchRef(classFqn, method.getName(), line, k);
                        if (k < branches.getCoveredCount()) {
                            covered.add(ref);
                        } else {
                            missed.add(ref);
                        }
                    }
                }
            }
        }
        return new BranchCoverage(covered, missed, total);
    }
}
