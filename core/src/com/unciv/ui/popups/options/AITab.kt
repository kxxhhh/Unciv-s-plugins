package com.unciv.ui.popups.options

import com.unciv.ai.AiContextLevel
import com.unciv.ai.AiControlMode
import com.unciv.ai.AiPersonalityPreset
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * AI 文明的设置页：LLM 接入参数、选项、控制模式。
 */
internal class AITab(optionsPopup: OptionsPopup) : OptionsPopupTab(optionsPopup) {

    override fun lateInitialize() {
        addHeader("AI LLM 玩家")
        addCheckbox("启用 AI 文明", settings.ai::enabled)

        addSeparator()

        addHeader("连接参数")
        addTextFieldRow("API Base URL", settings.ai::baseUrl)
        addApiKeyRow()
        addTextFieldRow("Model", settings.ai::model)

        addSeparator()

        addHeader("生成参数")
        addSlider("温度", settings.ai::temperature, 0f, 2f, 0.1f)
        addMaxTokensRow()

        addSeparator()

        addHeader("行为选项")
        addSelectBox("上下文级别",
            settings.ai::contextLevel,
            AiContextLevel.entries.map { it.name }.toList()
        )
        addSelectBox("人格",
            settings.ai::personality,
            AiPersonalityPreset.entries.map { it.key }.toList()
        )
        addSelectBox("控制模式",
            settings.ai::controlMode,
            AiControlMode.entries.map { it.name }.toList()
        )

        addSeparator()

        addCheckbox("Debug 模式", settings.ai::debugMode)

        addSeparator()

        addTestButton()
    }

    private fun addTextFieldRow(label: String, prop: kotlin.reflect.KMutableProperty0<String>) {
        add(label.tr().toLabel()).left().fillX()
        val field = UncivTextField(label, prop.get()) { prop.set(this.text) }
        add(field).pad(10f).minWidth(rightWidgetMinWidth).right().row()
    }

    private fun addApiKeyRow() {
        add("API Key".tr().toLabel()).left().fillX()
        val field = UncivTextField("API Key", settings.ai.apiKey) { settings.ai.apiKey = this.text }
        add(field).pad(10f).minWidth(rightWidgetMinWidth).right().row()
    }

    private fun addMaxTokensRow() {
        addSlider("最大 Tokens", settings.ai.maxTokens.toFloat(), 256f, 16384f, 256f) { value, _ ->
            settings.ai.maxTokens = value.toInt()
        }
    }

    private fun addTestButton() {
        val button = "Test Connection".tr().toTextButton()
        button.onActivation {
            button.setText("Testing...".tr())
            button.isDisabled = true

            Concurrency.run("AI-Test-Connection") {
                var result: String
                try {
                    val provider = com.unciv.ai.OpenAICompatibleProvider(
                        com.unciv.logic.UncivKtor.client,
                        settings.ai
                    )
                    val res = provider.chat(
                        listOf(com.unciv.ai.ChatMessage("user", "Reply 'OK'")),
                        forceTool = null
                    )
                    result = "OK: ${res.content ?: "(tool calls)"}"
                } catch (e: Exception) {
                    result = "Error: ${e.message?.take(80)}"
                }
                launchOnGLThread {
                    button.setText("Test Connection".tr())
                    button.isDisabled = false
                    button.label.setText(result)
                }
            }
        }

        add(button).colspan(2).center().padTop(10f).row()
    }
}