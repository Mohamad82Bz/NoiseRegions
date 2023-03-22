package me.mohamad82.noiseregions

import me.mohamad82.noiseregions.command.NoiseRegionCommand
import org.bukkit.plugin.java.JavaPlugin

class NoiseRegions: JavaPlugin() {

    override fun onEnable() {
        PLUGIN = this
        dataFolder.mkdir()

        getCommand("noiseregion")!!.setExecutor(NoiseRegionCommand())
    }

    override fun onDisable() {

    }

    companion object {
        lateinit var PLUGIN: NoiseRegions
    }

}