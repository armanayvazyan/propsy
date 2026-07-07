package io.github.armanayvazyan.propsy

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId

/**
 * Isolates the platform query for the optional `.env files support` plugin
 * (`ru.adelf.idea.dotenv`). Keeps the plugin-state call in one testable place;
 * references no `ru.adelf.*` classes.
 */
object DotEnvPlugin {
    const val ID = "ru.adelf.idea.dotenv"

    /**
     * True when the dotenv plugin is present, so its `.env` language support is available.
     *
     * Uses [PluginManager.isPluginInstalled] — the only non-`@Internal` plugin-presence query
     * (`findEnabledPlugin`/`getPlugin`/`getPlugins` are all `@ApiStatus.Internal` as of 262).
     */
    fun isActive(): Boolean =
        PluginManager.isPluginInstalled(PluginId.getId(ID))
}
