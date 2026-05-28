package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.Set;

/**
 * Maps a {@link ClassOrInterfaceDeclaration} to a {@link ClassRole} using only
 * annotation simple names and {@code extends}/{@code implements} simple names
 * — no FQN resolution required. This matches the conservative approach in
 * {@code path-discovery-static.MappingAnnotation} so behavior stays predictable
 * even when classpath jars are not provided to the symbol solver.
 */
public final class ClassRoleClassifier {

    private static final Set<String> CONTROLLER_ANNOTATIONS =
            Set.of("RestController", "Controller");

    private static final Set<String> SERVICE_ANNOTATIONS =
            Set.of("Service");

    private static final Set<String> REPOSITORY_ANNOTATIONS =
            Set.of("Repository", "Mapper");

    private static final Set<String> DOMAIN_ANNOTATIONS =
            Set.of("Entity", "Embeddable", "MappedSuperclass");

    private static final Set<String> REPOSITORY_BASE_INTERFACES =
            Set.of("JpaRepository", "CrudRepository", "PagingAndSortingRepository", "Repository");

    private ClassRoleClassifier() {}

    public static ClassRole classify(ClassOrInterfaceDeclaration cls) {
        // Order matters: a class with both @Service and @Repository would be ambiguous;
        // we resolve by ranking CONTROLLER > SERVICE > REPOSITORY > DOMAIN > OTHER. This
        // matches typical Spring layering and is documented in the class javadoc above.
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String name = ann.getNameAsString();
            if (CONTROLLER_ANNOTATIONS.contains(name)) return ClassRole.CONTROLLER;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (SERVICE_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.SERVICE;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (REPOSITORY_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
            if (REPOSITORY_BASE_INTERFACES.contains(ext.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (ClassOrInterfaceType imp : cls.getImplementedTypes()) {
            if (REPOSITORY_BASE_INTERFACES.contains(imp.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (DOMAIN_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.DOMAIN;
        }
        return ClassRole.OTHER;
    }
}
