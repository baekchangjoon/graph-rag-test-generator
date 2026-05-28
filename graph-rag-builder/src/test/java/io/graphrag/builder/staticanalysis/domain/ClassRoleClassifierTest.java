package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassRoleClassifierTest {

    @Test
    void rest_controller_annotation_marks_controller() {
        ClassOrInterfaceDeclaration c = parseType("@RestController class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.CONTROLLER);
    }

    @Test
    void plain_controller_annotation_marks_controller() {
        ClassOrInterfaceDeclaration c = parseType("@Controller class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.CONTROLLER);
    }

    @Test
    void service_annotation_marks_service() {
        ClassOrInterfaceDeclaration c = parseType("@Service class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.SERVICE);
    }

    @Test
    void repository_annotation_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType("@Repository class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void mapper_annotation_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType("@Mapper class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void jpa_repository_extends_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType(
                "interface A extends JpaRepository<X,Integer> {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void crud_repository_extends_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType(
                "interface A extends CrudRepository<X,Integer> {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void entity_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@Entity class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void embeddable_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@Embeddable class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void mapped_superclass_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@MappedSuperclass class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void no_recognizable_annotation_is_other() {
        ClassOrInterfaceDeclaration c = parseType("class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.OTHER);
    }

    private static ClassOrInterfaceDeclaration parseType(String src) {
        return StaticJavaParser.parse(src).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
