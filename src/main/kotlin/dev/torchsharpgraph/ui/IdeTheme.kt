package dev.torchsharpgraph.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.torchsharpgraph.render.PlotTheme
import java.awt.Color
import javax.swing.Icon

/** Plot colours and fonts taken from the current IDE theme, so plots match light and dark themes. */
object IdeTheme {
    val logIcon: Icon = IconLoader.getIcon("/icons/logScale.svg", IdeTheme::class.java)
    val gridIcon: Icon = IconLoader.getIcon("/icons/grid.svg", IdeTheme::class.java)

    private var cachedKey: Any? = null
    private var cached: PlotTheme? = null

    fun current(): PlotTheme {
        val bg = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        val font = JBFont.label()
        val key = listOf(bg.rgb, font, JBColor.isBright(), JBUI.scale(1))
        cached?.let { if (key == cachedKey) return it }
        val fg = UIUtil.getLabelForeground()
        val dark = !JBColor.isBright()
        val theme = PlotTheme(
            background = Color(bg.rgb),
            foreground = Color(fg.rgb),
            muted = Color(UIUtil.getContextHelpForeground().rgb),
            grid = ColorUtil.mix(bg, fg, if (dark) 0.09 else 0.07),
            axis = ColorUtil.mix(bg, fg, 0.35),
            palette = if (dark) PlotTheme.DARK_PALETTE else PlotTheme.LIGHT_PALETTE,
            tooltipBackground = Color(JBColor.namedColor("ToolTip.background", UIUtil.getToolTipBackground()).rgb),
            tooltipBorder = Color(JBColor.border().rgb),
            font = font,
            smallFont = JBFont.small(),
            dark = dark,
        )
        cached = theme
        cachedKey = key
        return theme
    }
}

/** Text from programs is shown as plain text, never as HTML. */
object UiText {
    fun plain(s: String): String = if (s.trimStart().startsWith("<html", ignoreCase = true)) "​$s" else s

    fun fileName(s: String): String {
        val cleaned = s.map { c -> if (c.isLetterOrDigit() || c in " -_.") c else '_' }.joinToString("").trim('.', ' ')
        return cleaned.ifEmpty { "plot" }.take(80)
    }
}
