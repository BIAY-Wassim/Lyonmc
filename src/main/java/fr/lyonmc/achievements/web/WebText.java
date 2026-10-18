package fr.lyonmc.achievements.web;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Conversion des textes du jeu pour le site (HTML coloré, texte brut, clé de traduction). */
public final class WebText {

    private WebText() {
    }

    /** Texte brut. */
    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Clé de traduction si le texte est traduisible (titres des progrès vanilla), sinon null. */
    public static String key(Component c) {
        return c instanceof TranslatableComponent t ? t.key() : null;
    }

    /** HTML avec couleurs et gras (gère les dégradés MiniMessage). */
    public static String html(Component c) {
        StringBuilder sb = new StringBuilder();
        walk(c, Style.empty(), sb);
        return sb.toString();
    }

    private static void walk(Component c, Style parent, StringBuilder sb) {
        Style style = parent.merge(c.style());
        String content = c instanceof TextComponent t ? t.content() : "";
        if (!content.isEmpty()) {
            TextColor color = style.color();
            boolean bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            boolean italic = style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE;
            if (color == null && !bold && !italic) {
                sb.append(escape(content));
            } else {
                sb.append("<span style=\"");
                if (color != null) sb.append("color:").append(color.asHexString()).append(';');
                if (bold) sb.append("font-weight:700;");
                if (italic) sb.append("font-style:italic;");
                sb.append("\">").append(escape(content)).append("</span>");
            }
        }
        for (Component child : c.children()) walk(child, style, sb);
    }

    public static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
