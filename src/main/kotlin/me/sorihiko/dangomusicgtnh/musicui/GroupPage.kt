package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.module.impls.music.NearbyModule
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.multiplayer.GroupInvite
import me.sorihiko.dangomusicgtnh.multiplayer.GroupSummary
import me.sorihiko.dangomusicgtnh.multiplayer.GroupUi
import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import me.sorihiko.dangomusicgtnh.network.NearbyClient
import me.sorihiko.dangomusicgtnh.platform.Keys
import me.sorihiko.dangomusicgtnh.ui.GlassStyle
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Music group page.
 *
 * Treats a group as a small "room": a hero card for the current room, a compact
 * invite list for the host, and a two-column lobby (create + invite / open groups)
 * when the player is not in one.
 */
internal class GroupPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    var editing = false
        private set
    /** Visible content area; used by MusicScreen for wheel routing. */
    var area = LayoutBounds()
    private var scroll = 0f
    private var maxScroll = 0f
    private var nameText = ""
    private var nameRect = LayoutBounds()
    private var leaveArmedUntil = 0L
    private var lastTyped = '\u0000'
    private var lastTypedAt = 0L
    private val buttonHover = HashMap<String, Spring>()

    private val cardGlass = GlassStyle(
        radius = 14f,
        tint = Color(1f, 1f, 1f, 0.055f),
        maxLuma = 0.36f,
        saturation = 1.4f,
        frost = 0.62f,
        refraction = 16f,
        bevel = 18f,
        dispersion = 0.28f,
        highlight = 0.95f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.12f), 10f, Vec2(0f, 4f)),
        fallback = Theme.CARD,
    )

    private val buttonGlass = GlassStyle(
        radius = 10f,
        tint = Color(1f, 1f, 1f, 0.10f),
        maxLuma = 0.44f,
        saturation = 1.5f,
        frost = 0.35f,
        refraction = 8f,
        bevel = 10f,
        dispersion = 0.30f,
        highlight = 1.1f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 6f, Vec2(0f, 2f)),
        fallback = Theme.CARD_HOVER,
    )

    private fun nearbyModule(): NearbyModule? = try {
        DangoMusic.instance.moduleManager.get(NearbyModule::class.java)
    } catch (_: Exception) {
        null
    }

    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!editing) return false
        when {
            keyCode == Keys.ESCAPE -> {
                lastTyped = '\u0000'
                editing = false
            }
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> createGroup()
            keyCode == Keys.BACK -> {
                lastTyped = '\u0000'
                if (nameText.isNotEmpty()) nameText = nameText.dropLast(1)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> {
                lastTyped = '\u0000'
                val clip = DangoClient.platform.clipboard
                    .replace(Regex("[\\r\\n]"), " ")
                    .filter { Keys.isAllowedChar(it) }
                nameText = (nameText + clip).take(24)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> {
                lastTyped = '\u0000'
                nameText = ""
            }
            Keys.isAllowedChar(typedChar) -> {
                // Some IMEs deliver one committed SDL text character twice in the
                // same frame. Ignore an exact duplicate within 10 ms; normal key
                // repeat is much slower.
                val now = System.nanoTime()
                if (typedChar == lastTyped && now - lastTypedAt in 0L..10_000_000L) return true
                lastTyped = typedChar
                lastTypedAt = now
                nameText = (nameText + typedChar).take(24)
            }
        }
        return true
    }

    fun clearFocus() {
        editing = false
    }

    fun draw(page: LayoutBounds) {
        ui.headerAnim {
            ui.card(LayoutBounds(page.x, page.y + 1f, 30f, 30f), Theme.ACCENT.copy(a = 0.16f), 10f)
            ui.icon("♬", page.x + 15f, page.y + 16f, 16f, Theme.ACCENT)
            ui.text(I18n.text("Music group"), page.x + 40f, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(
                I18n.text("Listen with friends, no distance limit"),
                page.x + 40f, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD,
            )
        }

        val data = NearbyClient.groupUi()
        if (data == null) {
            area = LayoutBounds()
            scroll = 0f
            maxScroll = 0f
            drawUnavailable(page)
            return
        }

        val view = LayoutBounds(page.x, page.y + 56f, page.width, page.bottom - (page.y + 56f))
        area = view
        val selected = NearbyClient.selectedGroupId()
        val active = data.group?.takeIf { selected.isNotEmpty() && it.id == selected }
        val joined = data.groupsJoined
        val activeId = selected
        val contentTop = view.y - scroll

        ui.beginClip(view)
        val contentBottom = try {
            drawContent(data, active, joined, activeId, contentTop, page)
        } finally {
            ui.endClip()
        }
        val contentHeight = max(0f, contentBottom - view.y + scroll)
        maxScroll = max(0f, contentHeight - view.height + ui.bottomInset)
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    /** “我的听歌房”里非当前房间的离开按钮：点一次变成确认，3 秒内再点才离开 */
    private val rowLeaveArmed = HashMap<String, Long>()

    fun scrollBy(wheel: Int) {
        if (wheel == 0 || maxScroll <= 0f) return
        scroll = (scroll - wheel * 28f).coerceIn(0f, maxScroll)
    }

    private fun drawContent(
        data: GroupUi,
        active: GroupView?,
        joined: List<GroupView>,
        activeId: String,
        top: Float,
        page: LayoutBounds,
    ): Float {
        var listTop = enter(0) {
            if (active != null) drawCurrentGroup(data, top, page) + 12f
            else drawCreate(top, page.x, page.width) + 12f
        }
        if (joined.isNotEmpty()) {
            listTop = enter(1) { drawMyGroups(data, listTop, page.x, page.width, activeId) + 12f }
        }
        return enter(2) { drawLists(data, active, joined, listTop, page) }
    }

    /** 打开页面时各块依次淡入、从下面升起（和其他页面同一套动画：每块晚 70ms） */
    private fun <T> enter(index: Int, block: () -> T): T {
        val p = ui.pageProgress(80f + index * 70f, 380f).coerceIn(0f, 1f)
        if (p >= 1f) return block()
        // 用 Ui.shifted：它同时移动中文文字层，文字不会和卡片错开
        var result: T? = null
        ui.shifted(0f, (1f - p) * 18f, p) { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun drawLists(data: GroupUi, active: GroupView?, joined: List<GroupView>, listTop: Float, page: LayoutBounds): Float {

        if (active == null && joined.isEmpty()) {
            return if (data.invites.isNotEmpty()) {
                val bottom = drawInvites(data, listTop, page.x, page.width)
                drawOpenGroups(data, bottom + 12f, page.x, page.width)
            } else {
                drawOpenGroups(data, listTop, page.x, page.width)
            }
        }
        if (active == null && data.invites.isEmpty()) {
            return drawOpenGroups(data, listTop, page.x, page.width)
        }

        if (page.width >= 500f) {
            val gap = 14f
            val col = (page.width - gap) / 2f
            val left = if (data.invites.isNotEmpty()) {
                drawInvites(data, listTop, page.x, col)
            } else if (active != null) {
                drawInvitePlayers(data, active, listTop, page.x, col)
            } else {
                listTop
            }
            val right = drawOpenGroups(data, listTop, page.x + col + gap, col)
            return max(left, right)
        }

        var y = listTop
        if (data.invites.isNotEmpty()) {
            y = drawInvites(data, y, page.x, page.width) + 12f
        } else if (active != null) {
            y = drawInvitePlayers(data, active, y, page.x, page.width) + 12f
        }
        return drawOpenGroups(data, y, page.x, page.width)
    }

    // ---------------------------------------------------------------- no group

    private fun drawCreate(top: Float, x: Float, width: Float): Float {
        val card = LayoutBounds(x, top, width, 124f)
        cardBox(card)
        ui.text(I18n.text("Create a group"), card.x + 20f, card.y + 16f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("Create a group and invite friends"), card.x + 20f, card.y + 38f, 10f, Theme.TEXT_SECONDARY)

        val buttonW = 88f
        nameRect = LayoutBounds(card.x + 20f, card.y + 60f, (card.width - 40f - buttonW - 8f).coerceAtLeast(80f), 30f)
        if (editing) ui.card(nameRect.expand(1.5f, 1.5f), Theme.ACCENT.copy(a = 0.55f), 9f)
        ui.card(nameRect, Color(0f, 0f, 0f, 0.28f), 8f)
        val placeholder = nameText.isEmpty() && !editing
        val shown = if (placeholder) I18n.text("Group name") else nameText
        val caret = if (editing && caretVisible()) "|" else ""
        ui.text(
            ui.trim(shown + caret, nameRect.width - 16f, 11.5f),
            nameRect.x + 9f, nameRect.y + 9f, 11.5f,
            if (placeholder) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY,
        )
        ui.hits += nameRect to { editing = true }

        val create = LayoutBounds(card.right - 20f - buttonW, card.y + 60f, buttonW, 30f)
        button(create, I18n.text("Create"), if (nameText.isBlank()) ButtonKind.Disabled else ButtonKind.Primary) {
            createGroup()
        }
        ui.text(I18n.text("Everyone can open one room; friends join it from the list below"), card.x + 20f, card.y + 100f, 9.5f, Theme.TEXT_TERTIARY)
        return card.bottom
    }

    private fun drawInvites(data: GroupUi, top: Float, x: Float, width: Float): Float {
        val invites = data.invites.take(3)
        val card = LayoutBounds(x, top, width, 44f + invites.size.coerceAtLeast(1) * 34f)
        cardBox(card)
        sectionTitle(I18n.text("Invitations"), card.x + 20f, card.y + 15f, card.width - 40f)
        if (invites.isEmpty()) {
            emptyState(card.x + 20f, card.y + 44f, I18n.text("No invitations"))
        } else {
            invites.forEachIndexed { i, invite ->
                inviteRow(invite, card.x + 12f, card.y + 40f + i * 34f, card.width - 24f)
            }
        }
        return card.bottom
    }

    private fun inviteRow(invite: GroupInvite, x: Float, y: Float, width: Float) {
        val row = LayoutBounds(x, y, width, 30f)
        if (ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
        avatar(invite.groupName, x + 4f, y + 4f, 24f)
        ui.text(ui.trim(invite.groupName, width - 150f, 11f, Theme.FONT_SEMIBOLD), x + 36f, y + 3f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(I18n.format("From %s", invite.from), width - 150f, 9f), x + 36f, y + 17f, 9f, Theme.TEXT_TERTIARY)
        val decline = LayoutBounds(x + width - 112f, y + 5f, 52f, 22f)
        button(decline, I18n.text("Decline"), ButtonKind.Ghost) { NearbyClient.groupAction("decline", invite.groupId) }
        val accept = LayoutBounds(x + width - 56f, y + 5f, 52f, 22f)
        button(accept, I18n.text("Accept"), ButtonKind.Primary) { NearbyClient.groupAction("accept", invite.groupId) }
    }

    private fun drawOpenGroups(data: GroupUi, top: Float, x: Float, width: Float): Float {
        val groups = data.groups.take(3)
        val card = LayoutBounds(x, top, width, 44f + groups.size.coerceAtLeast(1) * 34f)
        cardBox(card)
        sectionTitle(I18n.text("Open groups"), card.x + 20f, card.y + 15f, card.width - 40f)
        if (groups.isEmpty()) {
            emptyState(card.x + 20f, card.y + 44f, I18n.text("No open groups"))
        } else {
            groups.forEachIndexed { i, summary ->
                openGroupRow(summary, card.x + 12f, card.y + 40f + i * 34f, card.width - 24f)
            }
        }
        return card.bottom
    }

    private fun openGroupRow(summary: GroupSummary, x: Float, y: Float, width: Float) {
        val row = LayoutBounds(x, y, width, 30f)
        if (ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
        avatar(summary.name, x + 4f, y + 4f, 24f)
        ui.text(ui.trim(summary.name, width - 160f, 11f, Theme.FONT_SEMIBOLD), x + 36f, y + 3f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(
            ui.trim(I18n.format("%s · %d/%d", summary.host, summary.members, summary.max), width - 160f, 9f),
            x + 36f, y + 17f, 9f, Theme.TEXT_TERTIARY,
        )
        val full = summary.members >= summary.max
        val join = LayoutBounds(x + width - 62f, y + 5f, 58f, 22f)
        button(join, I18n.text(if (full) "Full" else "Join"), if (full) ButtonKind.Disabled else ButtonKind.Primary) {
            NearbyClient.groupAction("join", summary.name)
        }
    }

    // ---------------------------------------------------------------- in group

    private fun drawCurrentGroup(data: GroupUi, top: Float, page: LayoutBounds): Float {
        val group = data.group ?: return top
        val host = group.host
        val speaker = group.speaker.ifEmpty { if (!group.idle) group.host else "" }
        val members = group.members.take(16)
        val chipWidths = members.map { member ->
            val label = buildString {
                if (member == host) append("♛ ")
                if (member == speaker) append("♬ ")
                append(member)
            }
            label to (ui.width(label, 10f, Theme.FONT_SEMIBOLD) + 18f).coerceAtMost(page.width - 52f)
        }
        val chipArea = page.width - 40f
        var chipRows = 1
        var rowX = 0f
        chipWidths.forEach { (_, w) ->
            if (rowX > 0f && rowX + w > chipArea) {
                chipRows++
                rowX = 0f
            }
            rowX += w + 6f
        }

        val cardH = 92f + chipRows * 24f
        val card = LayoutBounds(page.x, top, page.width, cardH)
        cardBox(card)
        val maxMembers = data.groups.firstOrNull { it.id == group.id }?.max ?: group.members.size
        ui.text(ui.trim(group.name, card.width - 220f, 19f, Theme.FONT_DISPLAY),
            card.x + 20f, card.y + 15f, 19f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        ui.text(
            ui.trim(I18n.format("Host: %s", host), card.width - 220f, 10.5f),
            card.x + 20f, card.y + 41f, 10.5f, Theme.TEXT_SECONDARY,
        )

        val count = LayoutBounds(card.right - 196f, card.y + 15f, 98f, 24f)
        ui.card(count, Theme.CARD_HOVER, 12f)
        val countLabel = I18n.format("%d / %d members", group.members.size, maxMembers)
        val countW = ui.width(countLabel, 9.5f, Theme.FONT_SEMIBOLD)
        ui.text(countLabel, count.x + (count.width - countW) / 2f, count.y + 7f, 9.5f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)

        val armed = System.currentTimeMillis() < leaveArmedUntil
        val leave = LayoutBounds(card.right - 88f, card.y + 15f, 68f, 24f)
        button(leave, if (armed) I18n.text("Confirm leave") else I18n.text("Leave"),
            if (armed) ButtonKind.Danger else ButtonKind.Ghost) {
            if (armed) {
                leaveArmedUntil = 0L
                NearbyClient.groupAction("leave", group.id)
            } else {
                leaveArmedUntil = System.currentTimeMillis() + 3_000L
            }
        }

        var cx = card.x + 20f
        var cy = card.y + 64f
        chipWidths.forEachIndexed { i, (label, w) ->
            if (cx > card.x + 20f && cx + w > card.right - 20f) {
                cx = card.x + 20f
                cy += 24f
            }
            val member = members[i]
            val memberIsSpeaker = member == speaker
            ui.card(
                LayoutBounds(cx, cy, w, 20f),
                when {
                    memberIsSpeaker -> Theme.GREEN.copy(a = 0.18f)
                    member == host -> Theme.ACCENT.copy(a = 0.20f)
                    else -> Theme.CARD_HOVER
                },
                10f,
            )
            ui.text(ui.trim(label, w - 12f, 10f), cx + 6f, cy + 4f, 10f,
                if (memberIsSpeaker || member == host) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
            cx += w + 6f
        }

        val statusY = card.y + 64f + chipRows * 24f + 1f
        val speakerActive = speaker.isNotEmpty()
        val youAreSpeaker = speakerActive && speaker == DangoClient.platform.playerName
        val statusText = when {
            !speakerActive -> I18n.text("No one is sharing music")
            youAreSpeaker -> I18n.text("You are sharing music")
            else -> I18n.format("Sharing: %s", speaker)
        }
        ui.card(LayoutBounds(card.x + 20f, statusY + 5f, 5f, 5f), if (speakerActive) Theme.GREEN else Theme.TEXT_TERTIARY, 2.5f)
        ui.text(ui.trim(statusText, card.width - 60f, 9.5f), card.x + 31f, statusY, 9.5f,
            if (speakerActive) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)

        return card.bottom
    }

    private fun drawMyGroups(data: GroupUi, top: Float, x: Float, width: Float, activeId: String): Float {
        val groups = data.groupsJoined.take(16)
        val card = LayoutBounds(x, top, width, 38f + groups.size.coerceAtLeast(1) * 30f)
        cardBox(card)
        sectionTitle(I18n.text("My groups"), card.x + 20f, card.y + 15f, card.width - 40f)
        if (groups.isEmpty()) {
            emptyState(card.x + 20f, card.y + 44f, I18n.text("You have not joined a group"))
        } else {
            groups.forEachIndexed { i, group ->
                val row = LayoutBounds(card.x + 12f, card.y + 34f + i * 30f, card.width - 24f, 28f)
                val isActive = group.id == activeId
                if (isActive) ui.card(row, Theme.ACCENT.copy(a = 0.12f), 8f)
                else if (ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
                ui.card(
                    LayoutBounds(row.x + 8f, row.y + 12f, 5f, 5f),
                    if (isActive) Theme.GREEN else Theme.TEXT_TERTIARY,
                    2.5f,
                )
                ui.text(ui.trim(group.name, row.width - 180f, 11f, Theme.FONT_SEMIBOLD),
                    row.x + 20f, row.y + 3f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                ui.text(
                    ui.trim(I18n.format("Host: %s", group.host), row.width - 180f, 9f),
                    row.x + 20f, row.y + 17f, 9f, Theme.TEXT_TERTIARY,
                )
                val select = LayoutBounds(row.right - 116f, row.y + 4f, 58f, 22f)
                val side = LayoutBounds(row.right - 54f, row.y + 4f, 48f, 22f)
                if (isActive) {
                    // 正在收听的房间：离开按钮只在上面的房间卡片里出现一次；这里给“停止收听”
                    button(select, I18n.text("Current"), ButtonKind.Disabled) {}
                    button(side, I18n.text("Stop"), ButtonKind.Ghost) { NearbyClient.selectGroup("") }
                } else {
                    button(select, I18n.text("Listen"), ButtonKind.Primary) {
                        NearbyClient.selectGroup(group.id)
                    }
                    val armed = rowLeaveArmed[group.id]?.let { System.currentTimeMillis() < it } == true
                    button(side, if (armed) I18n.text("Confirm") else I18n.text("Leave"),
                        if (armed) ButtonKind.Danger else ButtonKind.Ghost) {
                        if (armed) {
                            rowLeaveArmed.remove(group.id)
                            NearbyClient.groupAction("leave", group.id)
                        } else {
                            rowLeaveArmed[group.id] = System.currentTimeMillis() + 3_000L
                        }
                    }
                }
            }
        }
        return card.bottom
    }

    private fun drawInvitePlayers(data: GroupUi, group: GroupView, top: Float, x: Float, width: Float): Float {
        val others = data.players.filter { it.name !in group.members }.take(3)
        val card = LayoutBounds(x, top, width, 44f + others.size.coerceAtLeast(1) * 34f)
        cardBox(card)
        sectionTitle(I18n.text("Invite players"), card.x + 20f, card.y + 15f, card.width - 40f)
        if (others.isEmpty()) {
            emptyState(card.x + 20f, card.y + 46f, I18n.text("No online players to invite"))
        } else {
            others.forEachIndexed { i, player ->
                val row = LayoutBounds(card.x + 12f, card.y + 40f + i * 34f, card.width - 24f, 30f)
                if (ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
                avatar(player.name, row.x + 4f, row.y + 3f, 24f)
                ui.text(ui.trim(player.name, row.width - 120f, 11f), row.x + 36f, row.y + 8f, 11f, Theme.TEXT_PRIMARY)
                val invite = LayoutBounds(row.right - 70f, row.y + 4f, 66f, 22f)
                button(invite, I18n.text("Invite"), ButtonKind.Ghost) {
                    NearbyClient.groupAction("invite", target = player.name)
                }
            }
        }
        return card.bottom
    }

    // ---------------------------------------------------------------- shared pieces

    private fun drawUnavailable(page: LayoutBounds) {
        val card = LayoutBounds(page.x, page.y + 56f, page.width, 100f)
        cardBox(card)
        ui.icon("♬", card.x + 30f, card.y + 32f, 22f, Theme.TEXT_TERTIARY)
        ui.text(I18n.text("The server does not have DangoMusic sharing enabled"),
            card.x + 56f, card.y + 18f, 13f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("Install the same mod on the server to use groups"),
            card.x + 56f, card.y + 42f, 10f, Theme.TEXT_TERTIARY)
        ui.text(I18n.text("Groups need the optional server component"), card.x + 56f, card.y + 60f, 9.5f, Theme.TEXT_TERTIARY)
    }

    private fun cardBox(b: LayoutBounds) {
        ui.ctx.drawGlass(b, cardGlass.copy(radius = 14f))
        ui.card(LayoutBounds(b.x + 1f, b.y + 14f, 3f, b.height - 28f), Theme.ACCENT.copy(a = 0.55f), 1.5f)
    }

    private fun sectionTitle(label: String, x: Float, y: Float, width: Float) {
        ui.text(label, x, y, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.card(LayoutBounds(x, y + 18f, min(28f, width), 2f), Theme.ACCENT.copy(a = 0.55f), 1f)
    }

    private fun emptyState(x: Float, y: Float, label: String) {
        ui.icon("·", x + 5f, y + 6f, 14f, Theme.TEXT_TERTIARY)
        ui.text(label, x + 16f, y, 10f, Theme.TEXT_TERTIARY)
    }

    private fun avatar(name: String, x: Float, y: Float, size: Float) {
        ui.card(LayoutBounds(x, y, size, size), Theme.ACCENT.copy(a = 0.16f), size / 2f)
        val initial = name.trim().take(1).ifEmpty { "?" }
        val w = ui.width(initial, 10f, Theme.FONT_SEMIBOLD)
        ui.text(initial, x + (size - w) / 2f, y + (size - 10f) / 2f - 1f, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
    }

    private enum class ButtonKind { Primary, Ghost, Danger, Disabled }

    private fun button(bounds: LayoutBounds, label: String, kind: ButtonKind, action: () -> Unit) {
        val base = when (kind) {
            ButtonKind.Primary -> Theme.ACCENT.copy(a = 0.82f)
            ButtonKind.Ghost -> Theme.CARD_HOVER
            ButtonKind.Danger -> RED.copy(a = 0.78f)
            ButtonKind.Disabled -> Theme.TRACK
        }
        val target = if (kind != ButtonKind.Disabled && ui.hover(bounds)) 1f else 0f
        val key = "${kind.name}|$label|${bounds.x.toInt()}:${bounds.y.toInt()}"
        val hover = buttonHover.getOrPut(key) { Spring(420f, 23f, target) }.tick(target)
        // Small scale spring on hover, like the settings controls.
        val drawB = bounds.expand(bounds.width * 0.022f * hover, bounds.height * 0.07f * hover)
        if (kind == ButtonKind.Disabled) {
            ui.card(drawB, base, drawB.height / 2f)
        } else {
            ui.ctx.drawGlass(
                drawB,
                buttonGlass.copy(
                    radius = drawB.height / 2f,
                    tint = base.copy(a = (base.a + 0.08f * hover).coerceAtMost(1f)),
                    fallback = if (hover > 0.5f) base.copy(a = (base.a + 0.10f).coerceAtMost(1f)) else base,
                ),
            )
        }
        val color = when (kind) {
            ButtonKind.Disabled -> Theme.TEXT_TERTIARY
            ButtonKind.Ghost -> Theme.TEXT_PRIMARY
            else -> Color(1f, 1f, 1f, 0.98f)
        }
        val w = ui.width(label, 10f, Theme.FONT_SEMIBOLD)
        ui.text(label, drawB.x + (drawB.width - w) / 2f, drawB.y + (drawB.height - 10f) / 2f - 0.5f,
            10f, color, Theme.FONT_SEMIBOLD)
        if (kind != ButtonKind.Disabled) ui.hits += drawB to action
    }

    private fun createGroup() {
        val name = nameText.trim()
        if (name.isEmpty()) return
        NearbyClient.groupAction("create", name)
        nameText = ""
        editing = false
        lastTyped = '\u0000'
    }
}
