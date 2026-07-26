package com.mockserver.jetbrains

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The **LLM** tool window (JetBrains parity for the VS Code LLM authoring aids):
 *
 * 1. an **LLM expectation builder** form (provider/model/path/completion/usage/stream)
 *    that produces an `httpLlmResponse` expectation and either opens it in an editor
 *    or loads it straight into the running MockServer; and
 * 2. an **agent-run call graph** view that fetches the graph via the MCP
 *    `explain_agent_run` tool and renders it as Mermaid in JCEF (the bundled Chromium),
 *    falling back to the raw Mermaid source when JCEF or the CDN is unavailable.
 */
class LlmToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LlmToolWindowPanel(project)
        Disposer.register(toolWindow.disposable, panel.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, "LLM", false)
        toolWindow.contentManager.addContent(content)
    }
}

/** The LLM tool-window UI: an authoring form on top and a call-graph render area below. */
class LlmToolWindowPanel(private val project: Project) {

    private val root = JPanel(BorderLayout())

    // --- builder form ---
    private val pathField = JBTextField("/v1/chat/completions", 24)
    private val methodField = JBTextField("POST", 6)
    private val providerCombo = JComboBox(LlmCompletion.PROVIDERS.toTypedArray())
    private val modelCombo = JComboBox(LlmCompletion.MODELS.toTypedArray()).apply { isEditable = true }
    private val completionArea = JBTextArea(4, 40)
    private val streamCheck = JCheckBox("Stream (SSE)", false)
    private val promptTokensField = JBTextField("", 6)
    private val completionTokensField = JBTextField("", 6)
    private val finishReasonField = JBTextField("stop", 10)

    private val previewButton = JButton("Open in Editor")
    private val loadButton = JButton("Load Into Server")

    // --- call graph ---
    private val sessionField = JBTextField("", 18)
    private val renderButton = JButton("Render Call Graph")
    private val graphContainer = JPanel(BorderLayout())
    private val graphFallback = JBTextArea().apply { isEditable = false }
    private var browser: JBCefBrowser? = null

    /** Disposable that releases the JCEF native process when the tool window closes. */
    val disposable = com.intellij.openapi.Disposable {
        browser?.let { Disposer.dispose(it) }
        browser = null
    }

    val component: JComponent get() = root

    init {
        buildUi()
        previewButton.addActionListener { buildExpectation()?.let { openInEditor(it) } }
        loadButton.addActionListener { buildExpectation()?.let { loadIntoServer(it) } }
        renderButton.addActionListener { renderCallGraph() }
    }

    private fun buildUi() {
        root.border = JBUI.Borders.empty(8)

        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        form.add(JBLabel("Build an httpLlmResponse expectation:").bold().leftAligned())
        form.add(row("Method:", methodField, JBLabel("Path:"), pathField))
        form.add(row("Provider:", providerCombo, JBLabel("Model:"), modelCombo))
        form.add(JBLabel("Completion text:").leftAligned())
        completionArea.lineWrap = true
        completionArea.wrapStyleWord = true
        form.add(JBScrollPane(completionArea).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(420, 80)
            maximumSize = Dimension(Int.MAX_VALUE, 80)
        })
        // Labels name the fields as the server does (completion.usage.inputTokens / outputTokens,
        // completion.stopReason), so what the form asks for matches what lands in the expectation.
        form.add(row("Input tokens:", promptTokensField, JBLabel("Output tokens:"), completionTokensField))
        form.add(row("Stop reason:", finishReasonField, null, streamCheck))
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(previewButton); add(loadButton)
        })

        val graphHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Agent-run call graph — session id (blank = latest):").bold())
            add(sessionField); add(renderButton)
        }
        form.add(graphHeader)

        root.add(form, BorderLayout.NORTH)

        graphFallback.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, graphFallback.font.size)
        graphContainer.add(JBScrollPane(graphFallback), BorderLayout.CENTER)
        root.add(graphContainer, BorderLayout.CENTER)
    }

    private fun row(label1: String, c1: JComponent, label2: JComponent?, c2: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(label1)); add(c1)
            if (label2 != null) add(label2)
            add(c2)
        }

    private fun JBLabel.bold(): JBLabel = apply { font = font.deriveFont(java.awt.Font.BOLD) }.leftAligned()
    private fun JBLabel.leftAligned(): JBLabel = apply { alignmentX = Component.LEFT_ALIGNMENT }

    // ---- builder ----

    private fun buildExpectation(): String? {
        val form = LlmExpectationBuilder.Form(
            path = pathField.text,
            method = methodField.text,
            provider = (providerCombo.selectedItem as? String) ?: "",
            model = (modelCombo.editor.item as? String) ?: (modelCombo.selectedItem as? String),
            completion = completionArea.text,
            stream = streamCheck.isSelected,
            promptTokens = promptTokensField.text.trim().toIntOrNull(),
            completionTokens = completionTokensField.text.trim().toIntOrNull(),
            finishReason = finishReasonField.text,
        )
        return try {
            LlmExpectationBuilder.build(form)
        } catch (ex: IllegalArgumentException) {
            MockServerNotifier.notify(project, ex.message ?: "Invalid LLM expectation.", NotificationType.WARNING)
            null
        }
    }

    private fun openInEditor(expectationJson: String) {
        MockServerEditors.openJsonInEditor(project, "llm-expectation.mockserver.json", expectationJson)
    }

    private fun loadIntoServer(expectationJson: String) {
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = MockServerRestClient.send(
                    MockServerRestClient.buildLoadExpectationsRequest(baseUrl, expectationJson)
                )
                runOnEdt(project) {
                    if (result.ok) {
                        MockServerNotifier.notify(project, "LLM expectation loaded into MockServer.", NotificationType.INFORMATION)
                    } else {
                        MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR)
                    }
                }
            } catch (ex: Exception) {
                runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
            }
        }
    }

    // ---- call graph ----

    private fun renderCallGraph() {
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())
        val arguments = JsonObject().apply {
            sessionField.text.trim().takeIf { it.isNotEmpty() }?.let { addProperty("sessionId", it) }
        }
        renderButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val graph = try {
                AgentCallGraph.fetchCallGraph(baseUrl, arguments)
            } catch (ex: Exception) {
                runOnEdt(project) {
                    renderButton.isEnabled = true
                    MockServerNotifier.notify(project, "Failed to fetch call graph: ${ex.message}", NotificationType.ERROR)
                }
                return@executeOnPooledThread
            }
            runOnEdt(project) {
                renderButton.isEnabled = true
                if (graph == null || graph.nodes.isEmpty()) {
                    MockServerNotifier.notify(
                        project,
                        "No agent-run call graph available. Proxy or mock an LLM agent run first.",
                        NotificationType.INFORMATION
                    )
                    return@runOnEdt
                }
                showGraph(AgentCallGraph.toMermaid(graph))
            }
        }
    }

    private fun showGraph(mermaidSource: String) {
        if (JBCefApp.isSupported()) {
            val existing = browser
            val cefBrowser = existing ?: JBCefBrowser.createBuilder().build().also { browser = it }
            graphContainer.removeAll()
            graphContainer.add(cefBrowser.component, BorderLayout.CENTER)
            cefBrowser.loadHTML(MermaidRenderer.toHtml(mermaidSource))
        } else {
            // JCEF unavailable — show the raw Mermaid source so it is still useful.
            graphContainer.removeAll()
            graphFallback.text = mermaidSource
            graphContainer.add(JBScrollPane(graphFallback), BorderLayout.CENTER)
        }
        graphContainer.revalidate()
        graphContainer.repaint()
    }
}
