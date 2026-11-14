package io.github.qianyeeew.dshremote.data.image

import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef

/** 现在能不能加图片（protocol.md 第 4 节「图片」，ADR 0014）。 */
enum class ImageSupport {
    /** 当前模型能看图。 */
    YES,

    /** 插件能发图片，但不知道当前模型能不能看图（DSH 没说）：照常可以加，发送被拒绝时再说明。 */
    UNKNOWN,

    /** 还没读到模型列表（刚打开、不在线）：先不让加。 */
    CHECKING,

    /** 旧插件：会忽略图片、只把文字发出去，所以不让加。 */
    OLD_PLUGIN,

    /** 当前模型只能读文字。 */
    TEXT_ONLY_MODEL,
    ;

    val canAdd: Boolean get() = this == YES || this == UNKNOWN

    companion object {
        /**
         * [models] 是读到的模型列表（还没读到时为 null），[model] 是下一条消息会用的模型（null 时用列表的默认模型）。
         */
        fun of(
            models: ModelList?,
            model: ModelRef?,
        ): ImageSupport =
            when {
                models == null -> CHECKING
                !models.imageInput -> OLD_PLUGIN
                else ->
                    when (models.imagesOf(model ?: models.default)) {
                        true -> YES
                        false -> TEXT_ONLY_MODEL
                        null -> UNKNOWN
                    }
            }
    }
}
