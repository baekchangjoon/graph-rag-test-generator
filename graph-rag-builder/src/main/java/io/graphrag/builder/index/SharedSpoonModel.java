package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** 정적 인덱서들이 공유하는 단일 Spoon 모델 빌더. Launcher 설정을 한 곳에 모은다. */
public final class SharedSpoonModel {

    private static final AtomicInteger BUILD_COUNT = new AtomicInteger();

    private SharedSpoonModel() {
    }

    public static CtModel build(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();
        BUILD_COUNT.incrementAndGet();
        return model;
    }

    public static int buildCount() {
        return BUILD_COUNT.get();
    }

    public static void resetBuildCount() {
        BUILD_COUNT.set(0);
    }
}
