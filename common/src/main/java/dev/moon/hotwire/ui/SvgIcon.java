package dev.moon.hotwire.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class SvgIcon {
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM = Pattern.compile(
            "(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)");
    private static final Pattern PATH_TOKEN = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|" + NUMBER.pattern());
    private static final Map<String, Integer> NAMED = Map.of("black", 0x000000, "white", 0xFFFFFF, "red", 0xFF0000,
            "green", 0x008000, "blue", 0x0000FF, "gray", 0x808080, "grey", 0x808080, "yellow", 0xFFFF00,
            "orange", 0xFFA500, "purple", 0x800080);

    private SvgIcon() {
    }

    public static @Nullable IconPixels render(byte[] svg) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new ByteArrayInputStream(svg));
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("svg")) {
                return null;
            }
            double[] box = parseViewBox(root);
            if (box[2] <= 0 || box[3] <= 0) {
                return null;
            }
            double scale = IconPixels.MAX_SIZE / Math.max(box[2], box[3]);
            int width = Math.max(1, (int) Math.round(box[2] * scale));
            int height = Math.max(1, (int) Math.round(box[3] * scale));
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.scale(scale, scale);
                g.translate(-box[0], -box[1]);
                Map<String, Element> byId = new HashMap<>();
                collectIds(root, byId);
                drawChildren(g, root, new Style().getStyleUnder(root), byId);
            } finally {
                g.dispose();
            }
            return IconPixels.of(width, height, image.getRGB(0, 0, width, height, null, 0, width));
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private record Style(@Nullable String fill, @Nullable String stroke, double strokeWidth, double opacity,
            double fillOpacity, double strokeOpacity, @Nullable String cap, @Nullable String join,
            @Nullable String dashes, double dashOffset, boolean evenOdd) {
        Style() {
            this("black", null, 1, 1, 1, 1, null, null, null, 0, false);
        }

        Style getStyleUnder(Element element) {
            Map<String, String> own = parseAttributes(element);
            return new Style(own.getOrDefault("fill", this.fill), own.getOrDefault("stroke", this.stroke),
                    parseNumber(own.get("stroke-width"), this.strokeWidth),
                    parseNumber(own.get("opacity"), 1) * this.opacity,
                    parseNumber(own.get("fill-opacity"), this.fillOpacity),
                    parseNumber(own.get("stroke-opacity"), this.strokeOpacity),
                    own.getOrDefault("stroke-linecap", this.cap), own.getOrDefault("stroke-linejoin", this.join),
                    own.getOrDefault("stroke-dasharray", this.dashes),
                    parseNumber(own.get("stroke-dashoffset"), this.dashOffset),
                    own.containsKey("fill-rule") ? "evenodd".equals(own.get("fill-rule")) : this.evenOdd);
        }
    }

    private static void drawChildren(Graphics2D g, Element parent, Style style, Map<String, Element> byId) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                draw(g, element, style, byId);
            }
        }
    }

    private static void draw(Graphics2D g, Element element, Style parent, Map<String, Element> byId) {
        String tag = element.getTagName();
        if (tag.equals("defs") || tag.endsWith("Gradient") || tag.equals("clipPath") || tag.equals("mask")
                || tag.equals("symbol") || tag.equals("title") || tag.equals("desc") || tag.equals("style")) {
            return;
        }
        Style style = parent.getStyleUnder(element);
        AffineTransform before = g.getTransform();
        String transform = element.getAttribute("transform");
        if (!transform.isEmpty()) {
            g.transform(transform(transform));
        }
        try {
            if (tag.equals("g") || tag.equals("svg") || tag.equals("a")) {
                drawChildren(g, element, style, byId);
                return;
            }
            Shape shape = parseShape(element);
            if (shape == null) {
                return;
            }
            Color fill = paint(style.fill(), style.opacity() * style.fillOpacity(), byId);
            if (fill != null) {
                Shape filled = shape;
                if (shape instanceof Path2D path) {
                    path.setWindingRule(style.evenOdd() ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                    filled = path;
                }
                g.setColor(fill);
                g.fill(filled);
            }
            Color stroke = paint(style.stroke(), style.opacity() * style.strokeOpacity(), byId);
            if (stroke != null && style.strokeWidth() > 0) {
                g.setColor(stroke);
                g.setStroke(stroke(style));
                g.draw(shape);
            }
        } finally {
            g.setTransform(before);
        }
    }

    private static @Nullable Shape parseShape(Element element) {
        return switch (element.getTagName()) {
            case "path" -> parsePath(element.getAttribute("d"));
            case "rect" -> {
                double x = parseNumber(element, "x");
                double y = parseNumber(element, "y");
                double w = parseNumber(element, "width");
                double h = parseNumber(element, "height");
                double rx = parseNumber(element, "rx");
                double ry = element.hasAttribute("ry") ? parseNumber(element, "ry") : rx;
                rx = element.hasAttribute("rx") ? rx : ry;
                yield rx > 0 || ry > 0 ? new RoundRectangle2D.Double(x, y, w, h, 2 * rx, 2 * ry)
                        : new Rectangle2D.Double(x, y, w, h);
            }
            case "circle" -> {
                double r = parseNumber(element, "r");
                yield new Ellipse2D.Double(parseNumber(element, "cx") - r, parseNumber(element, "cy") - r, 2 * r,
                        2 * r);
            }
            case "ellipse" -> {
                double rx = parseNumber(element, "rx");
                double ry = parseNumber(element, "ry");
                yield new Ellipse2D.Double(parseNumber(element, "cx") - rx, parseNumber(element, "cy") - ry, 2 * rx,
                        2 * ry);
            }
            case "line" -> new Line2D.Double(parseNumber(element, "x1"), parseNumber(element, "y1"),
                    parseNumber(element, "x2"), parseNumber(element, "y2"));
            case "polyline", "polygon" -> {
                double[] points = parseNumbers(element.getAttribute("points"));
                Path2D.Double path = new Path2D.Double();
                for (int i = 0; i + 1 < points.length; i += 2) {
                    if (i == 0) {
                        path.moveTo(points[i], points[i + 1]);
                    } else {
                        path.lineTo(points[i], points[i + 1]);
                    }
                }
                if (element.getTagName().equals("polygon")) {
                    path.closePath();
                }
                yield path;
            }
            default -> null;
        };
    }

    static Path2D.Double parsePath(String data) {
        Path2D.Double path = new Path2D.Double();
        Matcher tokens = PATH_TOKEN.matcher(data);
        List<String> parts = new ArrayList<>();
        while (tokens.find()) {
            parts.add(tokens.group());
        }
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        double controlX = 0;
        double controlY = 0;
        char command = 'M';
        char last = ' ';
        int at = 0;
        while (at < parts.size()) {
            String part = parts.get(at);
            if (Character.isLetter(part.charAt(0))) {
                command = part.charAt(0);
                at++;
                if (command == 'Z' || command == 'z') {
                    path.closePath();
                    x = startX;
                    y = startY;
                    last = command;
                    continue;
                }
            }
            boolean relative = Character.isLowerCase(command);
            double baseX = relative ? x : 0;
            double baseY = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;

                    command = relative ? 'l' : 'L';
                }
                case 'L' -> {
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = baseX + parseValue(parts, at++);
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = baseY + parseValue(parts, at++);
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    double x1 = baseX + parseValue(parts, at++);
                    double y1 = baseY + parseValue(parts, at++);
                    controlX = baseX + parseValue(parts, at++);
                    controlY = baseY + parseValue(parts, at++);
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.curveTo(x1, y1, controlX, controlY, x, y);
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(last) >= 0;
                    double x1 = smooth ? 2 * x - controlX : x;
                    double y1 = smooth ? 2 * y - controlY : y;
                    controlX = baseX + parseValue(parts, at++);
                    controlY = baseY + parseValue(parts, at++);
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.curveTo(x1, y1, controlX, controlY, x, y);
                }
                case 'Q' -> {
                    controlX = baseX + parseValue(parts, at++);
                    controlY = baseY + parseValue(parts, at++);
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.quadTo(controlX, controlY, x, y);
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(last) >= 0;
                    controlX = smooth ? 2 * x - controlX : x;
                    controlY = smooth ? 2 * y - controlY : y;
                    x = baseX + parseValue(parts, at++);
                    y = baseY + parseValue(parts, at++);
                    path.quadTo(controlX, controlY, x, y);
                }
                case 'A' -> {
                    double rx = parseValue(parts, at++);
                    double ry = parseValue(parts, at++);
                    double angle = parseValue(parts, at++);
                    boolean large = parseValue(parts, at++) != 0;
                    boolean sweep = parseValue(parts, at++) != 0;
                    double toX = baseX + parseValue(parts, at++);
                    double toY = baseY + parseValue(parts, at++);
                    addArc(path, x, y, rx, ry, angle, large, sweep, toX, toY);
                    x = toX;
                    y = toY;
                }
                default -> at++;
            }
            last = command;
        }
        return path;
    }

    private static void addArc(Path2D.Double path, double x0, double y0, double rx, double ry, double angle,
            boolean large, boolean sweep, double x, double y) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2;
        double dy = (y0 - y) / 2;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double factor = Math.sqrt(Math.max(0, numerator / (rx * rx * y1 * y1 + ry * ry * x1 * x1)));
        if (large == sweep) {
            factor = -factor;
        }
        double cx1 = factor * rx * y1 / ry;
        double cy1 = -factor * ry * x1 / rx;
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2;
        double start = Math.toDegrees(Math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx));
        double end = Math.toDegrees(Math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx));
        double extent = end - start;
        if (sweep && extent < 0) {
            extent += 360;
        } else if (!sweep && extent > 0) {
            extent -= 360;
        }

        Arc2D.Double arc = new Arc2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry, -start, -extent, Arc2D.OPEN);
        Shape turned = AffineTransform.getRotateInstance(phi, cx, cy).createTransformedShape(arc);
        path.append(turned, true);
    }

    private static BasicStroke stroke(Style style) {
        int cap = switch (style.cap() == null ? "" : style.cap()) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
        int join = switch (style.join() == null ? "" : style.join()) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
        double[] dashes = style.dashes() == null || style.dashes().equals("none") ? new double[0]
                : parseNumbers(style.dashes());
        float[] pattern = new float[dashes.length];
        boolean any = false;
        for (int i = 0; i < dashes.length; i++) {
            pattern[i] = (float) dashes[i];
            any |= dashes[i] > 0;
        }
        return any ? new BasicStroke((float) style.strokeWidth(), cap, join, 4, pattern, (float) style.dashOffset())
                : new BasicStroke((float) style.strokeWidth(), cap, join, 4);
    }

    private static @Nullable Color paint(@Nullable String value, double opacity, Map<String, Element> byId) {
        if (value == null || value.isBlank() || value.equals("none") || value.equals("transparent")) {
            return null;
        }
        String color = value.trim();
        if (color.startsWith("url(")) {
            String id = color.substring(4, color.indexOf(')')).trim().replace("#", "").replace("'", "");
            Element gradient = byId.get(id);
            color = gradient == null ? "black" : findFirstStop(gradient, byId);
        }
        int argb = parseColor(color);
        int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, opacity)));
        return new Color((argb & 0xFFFFFF) | (alpha << 24), true);
    }

    private static String findFirstStop(Element gradient, Map<String, Element> byId) {
        Element at = gradient;
        for (int hops = 0; at != null && hops < 8; hops++) {
            for (Node node = at.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element stop && stop.getTagName().equals("stop")) {
                    return parseAttributes(stop).getOrDefault("stop-color", "black");
                }
            }
            String linked = at.getAttribute("xlink:href");
            at = linked.startsWith("#") ? byId.get(linked.substring(1)) : null;
        }
        return "black";
    }

    static int parseColor(String color) {
        String value = color.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2)
                            + hex.charAt(2);
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
                if (hex.length() == 8) {
                    int rgba = (int) Long.parseLong(hex, 16);
                    return (rgba << 24) | (rgba >>> 8);
                }
            } else if (value.startsWith("rgb")) {
                double[] parts = parseNumbers(value.substring(value.indexOf('(') + 1));
                int alpha = parts.length > 3 ? (int) Math.round(parts[3] * 255) : 255;
                return (alpha << 24) | ((int) parts[0] << 16) | ((int) parts[1] << 8) | (int) parts[2];
            }
        } catch (RuntimeException e) {
            return 0xFF000000;
        }
        return 0xFF000000 | NAMED.getOrDefault(value, 0);
    }

    private static AffineTransform transform(String value) {
        AffineTransform out = new AffineTransform();
        Matcher each = TRANSFORM.matcher(value);
        while (each.find()) {
            double[] n = parseNumbers(each.group(2));
            switch (each.group(1)) {
                case "matrix" -> {
                    if (n.length == 6) {
                        out.concatenate(new AffineTransform(n[0], n[1], n[2], n[3], n[4], n[5]));
                    }
                }
                case "translate" -> out.translate(n.length > 0 ? n[0] : 0, n.length > 1 ? n[1] : 0);
                case "scale" -> {
                    double across = n.length > 0 ? n[0] : 1;
                    out.scale(across, n.length > 1 ? n[1] : across);
                }
                case "rotate" -> {
                    if (n.length >= 3) {
                        out.rotate(Math.toRadians(n[0]), n[1], n[2]);
                    } else if (n.length == 1) {
                        out.rotate(Math.toRadians(n[0]));
                    }
                }
                case "skewX" -> out.shear(Math.tan(Math.toRadians(n.length > 0 ? n[0] : 0)), 0);
                default -> out.shear(0, Math.tan(Math.toRadians(n.length > 0 ? n[0] : 0)));
            }
        }
        return out;
    }

    private static double[] parseViewBox(Element root) {
        double[] box = parseNumbers(root.getAttribute("viewBox"));
        if (box.length == 4) {
            return box;
        }
        return new double[] {0, 0, parseNumber(root, "width"), parseNumber(root, "height")};
    }

    private static Map<String, String> parseAttributes(Element element) {
        Map<String, String> own = new HashMap<>();
        for (String name : new String[] {"fill", "stroke", "stroke-width", "opacity", "fill-opacity",
                "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset",
                "fill-rule", "stop-color"}) {
            if (element.hasAttribute(name)) {
                own.put(name, element.getAttribute(name).trim());
            }
        }
        for (String declaration : element.getAttribute("style").split(";", -1)) {
            int colon = declaration.indexOf(':');
            if (colon > 0) {
                own.put(declaration.substring(0, colon).trim(), declaration.substring(colon + 1).trim());
            }
        }
        own.computeIfPresent("fill", (key, value) -> value.equals("currentColor") ? "black" : value);
        own.computeIfPresent("stroke", (key, value) -> value.equals("currentColor") ? "black" : value);
        return own;
    }

    private static void collectIds(Element element, Map<String, Element> byId) {
        if (element.hasAttribute("id")) {
            byId.put(element.getAttribute("id"), element);
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                collectIds(child, byId);
            }
        }
    }

    private static double parseNumber(Element element, String name) {
        return parseNumber(element.getAttribute(name), 0);
    }

    private static double parseNumber(@Nullable String value, double otherwise) {
        if (value == null) {
            return otherwise;
        }
        Matcher matcher = NUMBER.matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group()) : otherwise;
    }

    private static double[] parseNumbers(String value) {
        Matcher matcher = NUMBER.matcher(value);
        List<Double> out = new ArrayList<>();
        while (matcher.find()) {
            out.add(Double.parseDouble(matcher.group()));
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double parseValue(List<String> parts, int at) {
        return at < parts.size() ? Double.parseDouble(parts.get(at)) : 0;
    }
}
