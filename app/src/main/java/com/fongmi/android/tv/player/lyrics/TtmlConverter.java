package com.fongmi.android.tv.player.lyrics;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Local TTML -> enhanced LRC conversion, kept so sidecar .ttml lyrics files
 * next to local media remain usable after the online lyrics sources were removed.
 * Conversion code extracted verbatim from the former TtmlClient (its network
 * search parts were removed with the other online lyrics sources).
 */
public class TtmlConverter {

    private static final String TAG = "ttml";
    private static final String TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter";
    private static final String TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata";

    public static String toEnhancedLrc(String ttml) {
        if (TextUtils.isEmpty(ttml)) return "";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            try {
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
            } catch (Exception ignored) {
            }
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(ttml)));
            NodeList paragraphs = document.getElementsByTagNameNS("*", "p");
            long globalOffset = readGlobalOffset(document);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Node node = paragraphs.item(i);
                if (!(node instanceof Element)) continue;
                Element p = (Element) node;
                long lineStart = parseTime(timingAttribute(p, "begin"));
                if (lineStart < 0) lineStart = firstSpanBegin(p);
                if (lineStart < 0) continue;
                lineStart = Math.max(0, lineStart + globalOffset);
                StringBuilder line = new StringBuilder();
                appendWords(line, p, lineStart, globalOffset);
                if (line.length() > 0) builder.append(formatTime(lineStart)).append(line).append('\n');
            }
            return builder.toString();
        } catch (Exception e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "ttml parse failed error=%s", e.getMessage());
            return "";
        }
    }

    private static long readGlobalOffset(Document document) {
        NodeList audios = document.getElementsByTagNameNS("*", "audio");
        for (int i = 0; i < audios.getLength(); i++) {
            Node node = audios.item(i);
            if (node instanceof Element) return parseOffset(attribute((Element) node, "lyricOffset"));
        }
        return 0;
    }

    private static void appendWords(StringBuilder builder, Element parent, long lineStart, long globalOffset) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                builder.append(child.getTextContent());
                continue;
            }
            if (!(child instanceof Element)) continue;
            Element span = (Element) child;
            if (skipRole(span)) continue;
            long begin = parseTime(timingAttribute(span, "begin"));
            long end = parseTime(timingAttribute(span, "end"));
            String text = span.getTextContent();
            if (TextUtils.isEmpty(text)) continue;
            if (begin >= 0 && end > begin) {
                begin += globalOffset;
                end += globalOffset;
                builder.append('<')
                        .append(Math.max(0, begin - lineStart))
                        .append(',')
                        .append(end - begin)
                        .append('>')
                        .append(text);
            } else if (hasElementChild(span)) {
                appendWords(builder, span, lineStart, globalOffset);
            } else {
                builder.append(text);
            }
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
        }
    }

    private static boolean skipRole(Element element) {
        String role = attribute(element, "role");
        return "x-translation".equals(role) || "x-roman".equals(role) || "x-bg".equals(role);
    }

    private static long firstSpanBegin(Element parent) {
        long best = -1;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element element = (Element) child;
            if (!"span".equals(localName(element))) continue;
            long begin = parseTime(timingAttribute(element, "begin"));
            if (begin >= 0 && (best < 0 || begin < best)) best = begin;
        }
        return best;
    }

    private static boolean hasElementChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element) return true;
        return false;
    }

    private static String timingAttribute(Element element, String localName) {
        return attribute(element, localName, TTML_PARAMETER_NS);
    }

    private static String attribute(Element element, String localName, String... namespaces) {
        if (element == null || TextUtils.isEmpty(localName)) return "";
        String direct = element.getAttribute(localName);
        if (!TextUtils.isEmpty(direct)) return direct;
        if (namespaces != null) {
            for (String namespace : namespaces) {
                String value = element.getAttributeNS(namespace, localName);
                if (!TextUtils.isEmpty(value)) return value;
            }
        }
        String metadata = element.getAttributeNS(TTML_METADATA_NS, localName);
        if (!TextUtils.isEmpty(metadata)) return metadata;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node node = attributes.item(i);
            if (node == null) continue;
            String name = node.getLocalName();
            if (TextUtils.isEmpty(name)) name = node.getNodeName();
            if (localName.equals(name) || node.getNodeName().endsWith(":" + localName)) return node.getNodeValue();
        }
        return "";
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        return TextUtils.isEmpty(name) ? element.getNodeName().replaceFirst("^.*:", "") : name;
    }

    private static long parseOffset(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return 0;
        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) value = value.substring(1).trim();
        long parsed = parseTime(value);
        return parsed < 0 ? 0 : negative ? -parsed : parsed;
    }

    private static long parseTime(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return -1;
        try {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.endsWith("ms")) return Math.round(parseDouble(value.substring(0, value.length() - 2)));
            if (lower.endsWith("s")) return Math.round(parseDouble(value.substring(0, value.length() - 1)) * 1000);
            if (lower.endsWith("m")) return Math.round(parseDouble(value.substring(0, value.length() - 1)) * 60_000);
            if (lower.endsWith("h")) return Math.round(parseDouble(value.substring(0, value.length() - 1)) * 3_600_000);
            String[] parts = value.split(":");
            double seconds;
            if (parts.length == 3) {
                seconds = parseDouble(parts[0]) * 3600 + parseDouble(parts[1]) * 60 + parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = parseDouble(parts[0]) * 60 + parseDouble(parts[1]);
            } else {
                seconds = parseDouble(value);
            }
            return seconds < 0 ? -1 : Math.round(seconds * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.trim());
    }

    private static String formatTime(long timeMs) {
        long minute = timeMs / 60000;
        long second = timeMs % 60000;
        return String.format(Locale.US, "[%02d:%02d.%03d]", minute, second / 1000, second % 1000);
    }
}
