package me.sorihiko.dangomusicgtnh.client

import cpw.mods.fml.common.eventhandler.EventPriority
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import me.sorihiko.dangomusicgtnh.music.Dance
import net.minecraft.entity.EntityLivingBase
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.client.event.RenderWorldLastEvent
import org.lwjgl.opengl.GL11
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.sqrt

/**
 * Mob dance（生物跟着节奏弹）在 1.7.10 上的渲染钩子：上游各版本靠 Mixin 改生物渲染，这里用 Forge 的
 * [RenderLivingEvent]：Pre 时压一层矩阵，以生物脚下为中心按 [Dance.pose] 上下拉伸、左右按体积不变反向缩放，
 * 开了 Spin 再绕竖轴转；Post 时弹出。名字牌也在这层里，会跟着一起弹。
 */
internal object DanceRenderHook {
    /** Pre 里压过矩阵、还没等到 Post 的生物（按对象身份） */
    private val pushed: MutableSet<EntityLivingBase> = Collections.newSetFromMap(IdentityHashMap())
    /** 第一次压矩阵前的栈深；Post 没来（被后面的处理器取消或渲染出错）时，帧末据此弹回去 */
    private var baseDepth = -1

    // 最后执行、不处理已取消的 Pre：被别人取消的渲染不会有 Post，这里也就不能压矩阵
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun pre(e: RenderLivingEvent.Pre) {
        val pose = Dance.pose() ?: return
        // 物品栏里的玩家预览等界面渲染在原点画：不让它跟着弹
        if (e.x == 0.0 && e.y == 0.0 && e.z == 0.0) return
        if (!pushed.add(e.entity)) return
        if (baseDepth < 0) baseDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH)
        val stretch = pose[1].coerceIn(0.2f, 3f)
        val squeeze = 1f / sqrt(stretch)
        GL11.glPushMatrix()
        GL11.glTranslated(e.x, e.y, e.z)
        if (pose[0] != 0f) GL11.glRotatef(pose[0], 0f, 1f, 0f)
        GL11.glScalef(squeeze, stretch, squeeze)
        GL11.glTranslated(-e.x, -e.y, -e.z)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun post(e: RenderLivingEvent.Post) {
        if (pushed.remove(e.entity)) GL11.glPopMatrix()
        if (pushed.isEmpty()) baseDepth = -1
    }

    /** 帧末兜底：还有没等到 Post 的，把多压的矩阵弹掉，免得之后画的东西都被缩放 */
    @SubscribeEvent
    fun worldLast(e: RenderWorldLastEvent) {
        if (pushed.isEmpty()) return
        val target = baseDepth
        pushed.clear()
        baseDepth = -1
        if (target < 1) return
        var guard = 64
        while (guard-- > 0 && GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > target) GL11.glPopMatrix()
    }
}
