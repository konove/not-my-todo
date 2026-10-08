package io.github.konove.notmytodo.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JPanel

/**
 * What a chip says and how it is drawn: with a [line] it is outlined, with a [fill] it is filled,
 * and with neither it is plain text that stands among the chips. No [ink] is the colour of a label.
 */
internal data class ChipSpec(
    val kind: Kind, val text: String, val fill: Color? = null, val line: Color? = null,
    val ink: Color? = null, val bold: Boolean = false, val icon: Icon? = null,
) {
    enum class Kind { PRIORITY, EFFORT, STATUS, ID, FLAG, TAG }
}

/**
 * The chips of an item, the same wherever it is shown. What I set, the priority and the effort, is
 * outlined; what the item is, its status and tags, is filled. A flag says who has the next move.
 */
internal object Chips {
    private val LINE = JBColor(Color(0xC9CCD6), Color(0x5A5D63))
    private val FILL = JBColor(Color(0xEDEEF2), Color(0x43454A))
    private val SOFT = JBColor(Color(0x5A5D6B), Color(0xC3C6CC))

    private val dots = Priority.entries.associateWith { PriorityColors.dot(it, 7) }
    private val user by lazy { small(AllIcons.General.User) }
    private val lightning by lazy { small(AllIcons.Actions.Lightning) }
    private val comment by lazy { small(AllIcons.General.TodoDefault) }

    private fun small(icon: Icon): Icon = IconUtil.scale(icon, null, 0.75f)

    fun priority(p: Priority) = ChipSpec(ChipSpec.Kind.PRIORITY, p.name, line = LINE, icon = dots[p])

    fun effort(e: Effort) = ChipSpec(ChipSpec.Kind.EFFORT, e.name, line = LINE)

    /** In progress is blue and waiting for review is green; a closed item's status is greyed. */
    fun status(s: Status): ChipSpec {
        val (fill, ink) = when (s) {
            Status.OPEN -> FILL to null
            Status.IN_PROGRESS -> JBColor(Color(0xDCE9FB), Color(0x27364D)) to JBColor(Color(0x2457C5), Color(0x6AA8FF))
            Status.FIXED -> JBColor(Color(0xD5EFE8), Color(0x1F3B36)) to JBColor(Color(0x0B6E5F), Color(0x5FBFA8))
            Status.DONE, Status.WONT_FIX -> FILL to SOFT
        }
        return ChipSpec(ChipSpec.Kind.STATUS, s.label, fill = fill, ink = ink, bold = true)
    }

    fun tag(tag: String) = ChipSpec(ChipSpec.Kind.TAG, "#$tag", fill = FILL, ink = SOFT)

    /** Stands for the [count] chips a row had no room for. */
    fun more(count: Int) = ChipSpec(ChipSpec.Kind.TAG, "+$count", fill = FILL, ink = SOFT)

    val needsDecision: ChipSpec
        get() = ChipSpec(ChipSpec.Kind.FLAG, "Needs my decision", line = UIUtil.getLabelForeground(), bold = true, icon = user)
    val agentCanFix: ChipSpec get() = ChipSpec(ChipSpec.Kind.FLAG, "Agent can fix", line = LINE, ink = SOFT, icon = lightning)
    val blocked: ChipSpec get() = ChipSpec(ChipSpec.Kind.FLAG, "Blocked", line = LINE, ink = SOFT)
    val anchorLost: ChipSpec
        get() = ChipSpec(
            ChipSpec.Kind.FLAG, "Anchor lost", fill = JBColor(Color(0xFDE8E8), Color(0x4A2326)),
            ink = JBColor(Color(0xB3202A), Color(0xF58B90)), bold = true,
        )

    /** All there is to say of a TODO comment found in the code. */
    val codeComment: ChipSpec get() = ChipSpec(ChipSpec.Kind.FLAG, "Comment in the code", line = LINE, ink = SOFT, icon = comment)

    /** The id as plain text; an item with no anchor is a note. */
    fun id(item: TodoItem) =
        ChipSpec(ChipSpec.Kind.ID, if (item.anchor == null) "${item.id}  ·  note" else item.id, ink = UIUtil.getContextHelpForeground())

    /** The tags, but for the one a flag stands for. */
    fun tags(item: TodoItem): List<ChipSpec> = (item.tags - Tags.NEEDS_DECISION).map(::tag)

    /** Why nobody can go on with [item], if anything: it waits for me, or for another item when [blocked]. */
    fun waits(item: TodoItem, blocked: Boolean): ChipSpec? = when {
        item.needsDecision -> needsDecision
        blocked -> this.blocked
        else -> null
    }

    /** Everything about [item] in one line: priority, effort, status, id, flags, tags. */
    fun strip(item: TodoItem, blocked: Boolean): List<ChipSpec> {
        val next = waits(item, blocked) ?: agentCanFix.takeIf { item.status == Status.OPEN || item.status == Status.IN_PROGRESS }
        return listOfNotNull(
            priority(item.priority), item.effort?.let(::effort), status(item.status), id(item),
            anchorLost.takeIf { item.anyLost }, next,
        ) + tags(item)
    }
}

/** A small rounded label, drawn as its [spec] says. */
internal class Chip(spec: ChipSpec) : JBLabel() {
    var spec: ChipSpec = spec
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    init {
        iconTextGap = JBUI.scale(4)
        restyle()
    }

    private fun restyle() {
        text = spec.text
        icon = spec.icon
        font = JBUI.Fonts.smallFont().let { if (spec.bold) it.asBold() else it }
        foreground = spec.ink ?: UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(0, if (spec.fill == null && spec.line == null) 3 else 7)
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.height = JBUI.scale(HEIGHT) }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            spec.fill?.let {
                g2.color = it
                g2.fillRoundRect(0, 0, width, height, height, height)
            }
            spec.line?.let {
                g2.color = it
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, height - 1f, height - 1f))
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private companion object {
        const val HEIGHT = 18
    }
}

/**
 * Chips in one line, for a cell of a list row. Those the cell has no room for are counted in a
 * last chip; the first is always shown, cut short when it has to be.
 */
internal class ChipRow : JPanel(null) {
    private val chips = ArrayList<Chip>()
    private val more = Chip(Chips.more(0)).also(::add)
    private var count = 0

    init {
        isOpaque = false
    }

    /** What the row shows, cut or not. */
    val texts: List<String> get() = chips.take(count).map { it.text }

    /** Every chip of the row, for a tooltip, when the last layout had no room for some of them. */
    var tip: String? = null
        private set

    fun show(specs: List<ChipSpec>) {
        while (chips.size < specs.size) chips += Chip(specs[chips.size]).also(::add)
        specs.forEachIndexed { i, spec -> chips[i].spec = spec }
        count = specs.size
        // The same chips in another number are a different layout, and no label has said so.
        invalidate()
    }

    override fun doLayout() {
        val gap = JBUI.scale(4)
        val room = width - gap
        val widths = chips.take(count).map { it.preferredSize.width }

        /** The width the last chip takes, with the gap before it, when [left] chips are not shown. */
        fun moreWidth(left: Int): Int {
            if (left == 0) return 0
            more.spec = Chips.more(left)
            return more.preferredSize.width + gap
        }

        var fit = count
        while (fit > 1 && widths.take(fit).sum() + gap * (fit - 1) + moreWidth(count - fit) > room) fit--
        val left = count - fit
        val moreWidth = moreWidth(left)
        val h = more.preferredSize.height
        val y = (height - h) / 2
        var x = 0
        chips.forEachIndexed { i, chip ->
            chip.isVisible = i < fit
            if (i < fit) {
                val w = minOf(widths[i], maxOf(0, room - moreWidth - x))
                chip.setBounds(x, y, w, h)
                x += w + gap
            }
        }
        more.isVisible = left > 0
        tip = if (left > 0) texts.joinToString("   ") else null
        if (left > 0) more.setBounds(x, y, moreWidth - gap, h)
    }
}
