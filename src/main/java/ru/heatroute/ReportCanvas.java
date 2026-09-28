package ru.heatroute;

import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * One drawing vocabulary with two back ends: SVG text for the HTML report and Java2D for the PNG.
 * Coordinates are screen pixels. Inside a marker the frame is local (0,0 = anchor) and keeps a
 * fixed screen size when the HTML map is zoomed; outside markers strokes do not scale with zoom
 * either.
 */
abstract class ReportCanvas {
  abstract void group(String id, String cssClass, String variant);

  abstract void end();

  /** fill/stroke: "#rrggbb", null for none, or "hatch" for the railway hatch. */
  abstract void path(
      Shape s, String fill, String stroke, float width, float[] dash, double opacity, String tip);

  /** Invisible wide hover target (HTML only). */
  abstract void hit(Shape s, float width, String tip);

  /**
   * Opens a local frame at (x,y); cssClass "dn" marks labels that the HTML map hides when zoomed
   * far out.
   */
  abstract void marker(double x, double y, double angle, String tip, String cssClass);

  void marker(double x, double y, double angle, String tip) {
    marker(x, y, angle, tip, null);
  }

  abstract void endMarker();

  abstract void text(
      String s,
      double x,
      double y,
      float size,
      String color,
      boolean bold,
      String anchor,
      String halo);

  void circle(double x, double y, double r, String fill, String stroke, float width) {
    path(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r), fill, stroke, width, null, 1, null);
  }

  void line(double x1, double y1, double x2, double y2, String stroke, float width) {
    path(new Line2D.Double(x1, y1, x2, y2), null, stroke, width, null, 1, null);
  }

  static Shape polygon(double... xy) {
    Path2D p = new Path2D.Double();
    p.moveTo(xy[0], xy[1]);
    for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
    p.closePath();
    return p;
  }

  static String esc(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "&#10;");
  }

  static String n(double v) {
    long r = Math.round(v * 10);
    return r % 10 == 0 ? Long.toString(r / 10) : String.format(Locale.ROOT, "%.1f", r / 10.0);
  }

  /** SVG back end. */
  static final class Svg extends ReportCanvas {
    final StringBuilder out = new StringBuilder(1 << 20);
    private int markerDepth;

    @Override
    void group(String id, String cssClass, String variant) {
      out.append("<g");
      if (id != null) out.append(" id=\"").append(esc(id)).append('"');
      if (cssClass != null) out.append(" class=\"").append(cssClass).append('"');
      if (variant != null) out.append(" data-v=\"").append(esc(variant)).append('"');
      out.append(">\n");
    }

    @Override
    void end() {
      out.append("</g>\n");
    }

    @Override
    void path(
        Shape s,
        String fill,
        String stroke,
        float width,
        float[] dash,
        double opacity,
        String tip) {
      out.append("<path d=\"").append(d(s)).append('"');
      out.append(" fill=\"")
          .append(fill == null ? "none" : fill.equals("hatch") ? "url(#hatch)" : fill)
          .append('"');
      if (stroke != null) {
        out.append(" stroke=\"")
            .append(stroke)
            .append("\" stroke-width=\"")
            .append(n(width))
            .append('"');
        if (dash != null) {
          out.append(" stroke-dasharray=\"");
          for (int i = 0; i < dash.length; i++) out.append(i > 0 ? " " : "").append(n(dash[i]));
          out.append('"');
        }
        if (markerDepth == 0) out.append(" vector-effect=\"non-scaling-stroke\"");
      }
      if (opacity < 1)
        out.append(" opacity=\"").append(String.format(Locale.ROOT, "%.2f", opacity)).append('"');
      if (tip != null) out.append(" data-t=\"").append(esc(tip)).append('"');
      out.append("/>\n");
    }

    @Override
    void hit(Shape s, float width, String tip) {
      out.append("<path class=\"hit\" d=\"")
          .append(d(s))
          .append("\" stroke-width=\"")
          .append(n(width))
          .append("\" vector-effect=\"non-scaling-stroke\" data-t=\"")
          .append(esc(tip))
          .append("\"/>\n");
    }

    @Override
    void marker(double x, double y, double angle, String tip, String cssClass) {
      markerDepth++;
      out.append("<g class=\"mk")
          .append(cssClass == null ? "" : " " + cssClass)
          .append("\" data-x=\"")
          .append(n(x))
          .append("\" data-y=\"")
          .append(n(y))
          .append('"');
      if (angle != 0) out.append(" data-a=\"").append(n(angle)).append('"');
      out.append(" transform=\"translate(").append(n(x)).append(' ').append(n(y)).append(')');
      if (angle != 0) out.append(" rotate(").append(n(angle)).append(')');
      out.append('"');
      if (tip != null) out.append(" data-t=\"").append(esc(tip)).append('"');
      out.append(">\n");
    }

    @Override
    void endMarker() {
      markerDepth--;
      out.append("</g>\n");
    }

    @Override
    void text(
        String s,
        double x,
        double y,
        float size,
        String color,
        boolean bold,
        String anchor,
        String halo) {
      out.append("<text x=\"")
          .append(n(x))
          .append("\" y=\"")
          .append(n(y))
          .append("\" font-size=\"")
          .append(n(size))
          .append("\" fill=\"")
          .append(color)
          .append('"');
      if (bold) out.append(" font-weight=\"700\"");
      if (!"start".equals(anchor)) out.append(" text-anchor=\"").append(anchor).append('"');
      if (halo != null)
        out.append(" stroke=\"")
            .append(halo)
            .append("\" stroke-width=\"3\" paint-order=\"stroke\" stroke-linejoin=\"round\"");
      out.append('>').append(esc(s)).append("</text>\n");
    }

    static String d(Shape s) {
      StringBuilder b = new StringBuilder();
      double[] c = new double[6];
      for (PathIterator it = s.getPathIterator(null, 0.25); !it.isDone(); it.next()) {
        int type = it.currentSegment(c);
        if (type == PathIterator.SEG_MOVETO)
          b.append('M').append(n(c[0])).append(' ').append(n(c[1]));
        else if (type == PathIterator.SEG_LINETO)
          b.append('L').append(n(c[0])).append(' ').append(n(c[1]));
        else if (type == PathIterator.SEG_CLOSE) b.append('Z');
      }
      return b.toString();
    }
  }

  /** Java2D back end for the PNG. */
  static final class Awt extends ReportCanvas {
    final Graphics2D g;
    final String family;
    private final Deque<AffineTransform> frames = new ArrayDeque<>();
    private final Paint hatch;

    Awt(Graphics2D g, String family) {
      this.g = g;
      this.family = family;
      BufferedImage tile = new BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB);
      Graphics2D t = tile.createGraphics();
      t.setColor(Color.decode("#d6d1c8"));
      t.fillRect(0, 0, 6, 6);
      t.setColor(Color.decode("#8f887c"));
      t.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      t.setStroke(new BasicStroke(1.2f));
      t.drawLine(0, 6, 6, 0);
      t.drawLine(-1, 1, 1, -1);
      t.drawLine(5, 7, 7, 5);
      t.dispose();
      hatch = new TexturePaint(tile, new Rectangle(0, 0, 6, 6));
    }

    static Color color(String hex) {
      return Color.decode(hex);
    }

    @Override
    void group(String id, String cssClass, String variant) {}

    @Override
    void end() {}

    @Override
    void path(
        Shape s,
        String fill,
        String stroke,
        float width,
        float[] dash,
        double opacity,
        String tip) {
      Composite old = g.getComposite();
      if (opacity < 1)
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity));
      if (fill != null) {
        g.setPaint(fill.equals("hatch") ? hatch : color(fill));
        g.fill(s);
      }
      if (stroke != null) {
        g.setPaint(color(stroke));
        g.setStroke(
            dash == null
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(
                    width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, dash, 0));
        g.draw(s);
      }
      g.setComposite(old);
    }

    @Override
    void hit(Shape s, float width, String tip) {}

    @Override
    void marker(double x, double y, double angle, String tip, String cssClass) {
      frames.push(g.getTransform());
      g.translate(x, y);
      if (angle != 0) g.rotate(Math.toRadians(angle));
    }

    @Override
    void endMarker() {
      g.setTransform(frames.pop());
    }

    @Override
    void text(
        String s,
        double x,
        double y,
        float size,
        String color,
        boolean bold,
        String anchor,
        String halo) {
      if (s.isEmpty()) return;
      Font font = new Font(family, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size);
      TextLayout layout = new TextLayout(s.replace(' ', ' '), font, g.getFontRenderContext());
      double w = layout.getAdvance(),
          dx = "middle".equals(anchor) ? -w / 2 : "end".equals(anchor) ? -w : 0;
      Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x + dx, y));
      if (halo != null) {
        g.setPaint(color(halo));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
      }
      g.setPaint(color(color));
      g.fill(outline);
    }

    double width(String s, float size, boolean bold) {
      if (s.isEmpty()) return 0;
      Font font = new Font(family, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size);
      return new TextLayout(s.replace(' ', ' '), font, g.getFontRenderContext()).getAdvance();
    }
  }
}
