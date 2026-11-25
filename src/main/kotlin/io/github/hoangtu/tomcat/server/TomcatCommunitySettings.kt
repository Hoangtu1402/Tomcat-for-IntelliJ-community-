package io.github.hoangtu.tomcat.server

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path

/** Application-level settings: the registered Tomcat installations ("application servers") and global options. */
@Service(Service.Level.APP)
@State(name = "CommunityTomcatServers", storages = [Storage("communityTomcatServers.xml", roamingType = RoamingType.DISABLED)])
class TomcatCommunitySettings : PersistentStateComponent<TomcatCommunitySettings.SettingsState> {
    @Tag("server")
    class ServerEntry {
        @get:Attribute("name")
        var name: String = ""

        @get:Attribute("home")
        var home: String = ""

        fun copy() = ServerEntry().also { it.name = name; it.home = home }
    }

    class SettingsState {
        @get:XCollection(propertyElementName = "servers")
        var servers: MutableList<ServerEntry> = mutableListOf()

        @get:Attribute("autoConfigureWebModules")
        var autoConfigureWebModules: Boolean = true
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state
    override fun loadState(state: SettingsState) {
        this.state = state
    }

    var servers: List<ServerEntry>
        get() = state.servers
        set(value) {
            state.servers = value.map { it.copy() }.toMutableList()
        }

    var autoConfigureWebModules: Boolean
        get() = state.autoConfigureWebModules
        set(value) {
            state.autoConfigureWebModules = value
        }

    fun findServer(name: String?): ServerEntry? = if (name.isNullOrBlank()) null else state.servers.firstOrNull { it.name == name }

    fun findInstallation(name: String?): TomcatInstallation? = findServer(name)?.let { TomcatInstallation(Path.of(it.home)) }

    /** Registers [home] (if not registered yet) and returns the server name. */
    fun register(home: Path): String {
        val normalized = home.toAbsolutePath().normalize().toString()
        state.servers.firstOrNull { Path.of(it.home).toAbsolutePath().normalize().toString() == normalized }?.let { return it.name }
        val base = TomcatInstallation(home).defaultName
        var name = base
        var i = 2
        while (state.servers.any { it.name == name }) name = "$base (${i++})"
        state.servers.add(ServerEntry().also { it.name = name; it.home = normalized })
        return name
    }

    companion object {
        fun getInstance(): TomcatCommunitySettings = service()
    }
}
