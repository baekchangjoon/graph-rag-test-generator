package io.graphrag.builder.staticanalysis.domain;

/**
 * High-level role of a Java class in a Spring application. Set by
 * {@link ClassRoleClassifier} from annotation simple names plus
 * {@code extends}/{@code implements} of well-known Spring Data interfaces.
 */
public enum ClassRole {
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    DOMAIN,
    OTHER
}
