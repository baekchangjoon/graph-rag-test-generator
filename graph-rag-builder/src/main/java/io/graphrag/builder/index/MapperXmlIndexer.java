package io.graphrag.builder.index;

import io.graphrag.model.MapperStatement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** MyBatis XML mapper 인덱싱 (L1, roadmap 1.3). */
public class MapperXmlIndexer {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> DYNAMIC_TAGS = Set.of("if", "choose", "foreach", "where", "set", "trim", "bind");

    public List<MapperStatement> index(Path srcDir) {
        List<MapperStatement> statements = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcDir)) {
            files.filter(p -> p.toString().endsWith(".xml"))
                    .sorted()
                    .forEach(p -> statements.addAll(parseFile(p)));
        } catch (Exception e) {
            throw new IllegalStateException("mapper indexing failed under " + srcDir, e);
        }
        return statements;
    }

    private List<MapperStatement> parseFile(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // mybatis DTD를 네트워크에서 받지 않도록 외부 엔티티 비활성
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = factory.newDocumentBuilder().parse(file.toFile());
            Element root = doc.getDocumentElement();
            if (!"mapper".equals(root.getTagName()) || root.getAttribute("namespace").isBlank()) {
                return List.of();
            }
            String namespace = root.getAttribute("namespace");
            String mapperName = namespace.substring(namespace.lastIndexOf('.') + 1).toLowerCase();

            List<MapperStatement> statements = new ArrayList<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element element)
                        || !STATEMENT_TAGS.contains(element.getTagName())) {
                    continue;
                }
                String statementId = element.getAttribute("id");
                statements.add(new MapperStatement(
                        "mapper-" + mapperName + "-" + statementId,
                        namespace,
                        statementId,
                        element.getTagName().toUpperCase(),
                        containsDynamicTag(element),
                        serialize(element)));
            }
            return statements;
        } catch (Exception e) {
            // mapper가 아닌 일반 XML(logback 등)은 무시
            return List.of();
        }
    }

    private static boolean containsDynamicTag(Element element) {
        NodeList all = element.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (DYNAMIC_TAGS.contains(((Element) all.item(i)).getTagName())) {
                return true;
            }
        }
        return false;
    }

    private static String serialize(Node node) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return writer.toString();
    }
}
