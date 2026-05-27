package io.graphrag.feedback;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a JaCoCo {@code jacoco.xml} report into a flat {@link CoverageReport} —
 * enough info for {@link CoverageDeltaCalculator} to compute iteration deltas.
 *
 * <p>JaCoCo 0.8.x XML schema (simplified):
 * <pre>
 *   &lt;report&gt;
 *     &lt;package name="org/foo"&gt;
 *       &lt;sourcefile name="Bar.java"&gt;
 *         &lt;line nr="42" mi="0" ci="3" mb="0" cb="2"/&gt;
 *         &lt;line nr="43" mi="3" ci="0" mb="1" cb="0"/&gt;
 *       &lt;/sourcefile&gt;
 *       &lt;counter type="BRANCH" missed="1" covered="2"/&gt;
 *     &lt;/package&gt;
 *     &lt;counter type="BRANCH" missed="1" covered="2"/&gt;
 *   &lt;/report&gt;
 * </pre>
 *
 * <p>Disables DTD loading + external entity resolution per OWASP XXE guidance.
 */
public final class JaCoCoXmlParser {

    private JaCoCoXmlParser() {}

    public static CoverageReport parse(Path xmlFile) throws IOException {
        try {
            String content = Files.readString(xmlFile);
            return parseString(content);
        } catch (Exception ex) {
            if (ex instanceof IOException io) throw io;
            throw new IOException("could not parse " + xmlFile, ex);
        }
    }

    static CoverageReport parseString(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // XXE hardening.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new InputSource(new StringReader(xml)));

        Element report = doc.getDocumentElement();
        int reportBranchMissed = 0;
        int reportBranchCovered = 0;
        int reportLineMissed = 0;
        int reportLineCovered = 0;

        List<MissingBranch> missing = new ArrayList<>();

        NodeList packages = report.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            Element pkg = (Element) packages.item(i);
            String pkgName = pkg.getAttribute("name").replace('/', '.');
            NodeList sourcefiles = pkg.getElementsByTagName("sourcefile");
            for (int j = 0; j < sourcefiles.getLength(); j++) {
                Element src = (Element) sourcefiles.item(j);
                String srcName = src.getAttribute("name");
                NodeList lines = src.getElementsByTagName("line");
                for (int k = 0; k < lines.getLength(); k++) {
                    Element line = (Element) lines.item(k);
                    int nr = parseIntAttr(line, "nr");
                    int mb = parseIntAttr(line, "mb");
                    int cb = parseIntAttr(line, "cb");
                    if (mb > 0) {
                        missing.add(new MissingBranch(
                                pkgName + "." + stripExt(srcName) + ":" + nr,
                                srcName, nr, mb, cb));
                    }
                }
            }
        }

        // Report-level <counter> children carry the totals.
        for (Node n = report.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"counter".equals(n.getNodeName())) continue;
            Element c = (Element) n;
            String type = c.getAttribute("type");
            if ("BRANCH".equals(type)) {
                reportBranchMissed = parseIntAttr(c, "missed");
                reportBranchCovered = parseIntAttr(c, "covered");
            } else if ("LINE".equals(type)) {
                reportLineMissed = parseIntAttr(c, "missed");
                reportLineCovered = parseIntAttr(c, "covered");
            }
        }
        return new CoverageReport(
                fraction(reportBranchCovered, reportBranchMissed),
                fraction(reportLineCovered, reportLineMissed),
                missing);
    }

    private static int parseIntAttr(Element e, String name) {
        String v = e.getAttribute(name);
        if (v == null || v.isBlank()) return 0;
        return Integer.parseInt(v);
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static double fraction(int covered, int missed) {
        int total = covered + missed;
        if (total == 0) return 1.0;          // no branches → trivially 100% covered
        return ((double) covered) / total;
    }
}
