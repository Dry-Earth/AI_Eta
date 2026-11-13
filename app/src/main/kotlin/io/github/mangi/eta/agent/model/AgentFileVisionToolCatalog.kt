package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "读取用户指定路径或系统相册 URI 中的一张图片。默认把图片作为视觉输入交给对话模型；启用本地识图后，图片只发往本机识图服务，返回文字描述。可用 question 说明这次想从图中了解什么。同一轮最多调用一次；需要查看多张图片时，等待当前图片返回并观察后，再在下一轮读取下一张。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "任意绝对图片路径、file URI 或系统相册 content URI；本机路径由 Root 读取"),
                        ),
                    )
                    .put(
                        "question",
                        JSONObject()
                            .put("type", "string")
                            .put("maxLength", 500)
                            .put("description", "这次想从图片里了解什么，例如“报错内容是什么”或“左上角按钮是什么颜色”。省略时按客观描述处理。"),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
