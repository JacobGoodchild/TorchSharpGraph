package dev.torchsharpgraph.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.torchsharpgraph.server.GraphsServer
import javax.swing.JComponent

@Service(Service.Level.APP)
@State(name = "TorchSharpGraphSettings", storages = [Storage("torchsharpgraph.xml")])
class GraphsSettings : PersistentStateComponent<GraphsSettings.Data> {
    class Data {
        /** The first port to listen on (127.0.0.1 only). If it is busy the next free one is used. */
        var port: Int = DEFAULT_PORT
        /** A new run of a program replaces the plots of the last run. */
        var clearOnNewRun: Boolean = true
        /** Show the Graphs window when a program starts sending plots. */
        var openOnData: Boolean = true
        /** Listen for programs at all. */
        var listen: Boolean = true
    }

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }

    var port: Int
        get() = data.port.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        set(value) { data.port = value }
    var clearOnNewRun by data::clearOnNewRun
    var openOnData by data::openOnData
    var listen by data::listen

    companion object {
        const val DEFAULT_PORT = 47811
        fun get(): GraphsSettings = ApplicationManager.getApplication().getService(GraphsSettings::class.java)
    }
}

/** Settings > Tools > Graphs for TorchSharp. */
class GraphsConfigurable : Configurable {
    private var panel: DialogPanel? = null
    private val s get() = GraphsSettings.get()

    override fun getDisplayName() = "Graphs for TorchSharp"

    override fun createComponent(): JComponent {
        val settings = s
        val p = panel {
            row {
                checkBox("Show plots from running programs").bindSelected(settings::listen)
                    .comment("Programs using the TorchSharpGraph library send their plots to the Graphs window. Only connections from this computer are accepted.")
            }
            row("Port:") {
                intTextField(1024..65535).bindIntText(settings::port)
                    .comment("If this port is busy, the next free one is used. Programs find it automatically.")
            }
            row { checkBox("Open the Graphs window when a program sends plots").bindSelected(settings::openOnData) }
            row { checkBox("Clear the last run's plots when a program starts again").bindSelected(settings::clearOnNewRun) }
        }
        panel = p
        return p
    }

    override fun isModified() = panel?.isModified() ?: false

    override fun apply() {
        val before = s.port to s.listen
        panel?.apply()
        if ((s.port to s.listen) != before) GraphsServer.get().restart()
    }

    override fun reset() { panel?.reset() }

    override fun disposeUIResources() { panel = null }
}
