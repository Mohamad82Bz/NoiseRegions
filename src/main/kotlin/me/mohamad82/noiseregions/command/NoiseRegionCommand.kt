package me.mohamad82.noiseregions.command

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector2
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion
import me.mohamad82.noiseregion.noise.FastNoiseLite
import me.mohamad82.noiseregion.utils.CharAnimation
import me.mohamad82.noiseregion.utils.ProgressBar
import me.mohamad82.noiseregion.utils.YamlConfig
import me.mohamad82.noiseregions.NoiseRegions
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Particle.DustOptions
import org.bukkit.block.Biome
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt


class NoiseRegionCommand: CommandExecutor {

    private val THREAD_FACTORY = ThreadFactoryBuilder().setNameFormat("noiseregions-async-thread-%d").build()

    val config = YamlConfig(NoiseRegions.PLUGIN.dataFolder, "config.yml")

    private val asyncExecutor = Executors.newFixedThreadPool(config.config.getInt("max_threads", 10), THREAD_FACTORY)

    val regions = mutableMapOf<Int, MutableSet<BlockVector2>>()
    val polygons = mutableMapOf<ProtectedPolygonalRegion, Set<BlockVector2>>()

    var runnable: BukkitTask? = null

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("noiseregions.admin")) {
            sender.sendMessage("${ChatColor.DARK_RED}You don't have permission to perform this command!")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("You must be a player to use this command")
            return true
        }

        when (args[0]) {
            "clear", "delete" -> {
                runnable?.cancel()
                runnable = null
                regions.clear()
                polygons.clear()

                sender.sendMessage("In-memory (Unsaved) regions have been deleted.")
            }
            "reload" -> {
                config.reloadConfig()

                sender.sendMessage("Config has been reloaded.")
            }
            "gen", "generate" -> {
                val random = ThreadLocalRandom.current().nextInt()
                val noiseGen = FastNoiseLite(random)
                noiseGen.SetNoiseType(FastNoiseLite.NoiseType.Cellular)
                noiseGen.SetRotationType3D(FastNoiseLite.RotationType3D.None)
                noiseGen.SetFrequency(config.config.getDouble("frequency").toFloat())

                noiseGen.SetFractalType(FastNoiseLite.FractalType.None)

                noiseGen.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Hybrid)
                noiseGen.SetCellularReturnType(FastNoiseLite.CellularReturnType.CellValue)
                noiseGen.SetCellularJitter(config.config.getDouble("jitter", 0.5).toFloat())

                val minX = config.config.getInt("min_x")
                val maxX = config.config.getInt("max_x")
                val minZ = config.config.getInt("min_z")
                val maxZ = config.config.getInt("max_z")

                val totalProgress = (maxX - minX) * (maxZ - minZ)
                val progress = AtomicInteger(0)
                val animation = CharAnimation(CharAnimation.Style.SQUARE_BLOCK)

                object: BukkitRunnable() {
                    override fun run() {
                        if (progress.get() >= totalProgress) {
                            sender.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', "&aCOMPLETED")))
                            cancel()
                            return
                        }
                        sender.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', "&6" + animation.get() + " &bProgress: " + ProgressBar.progressBar(
                            progress.get(),
                            totalProgress,
                            20,
                            "&4▮",
                            "&d▯"
                        ))))
                    }
                }.runTaskTimer(NoiseRegions.PLUGIN, 0, 4)

                object: BukkitRunnable() {
                    override fun run() {
                        for (x in minX..maxX) {
                            for (z in minZ..maxZ) {
                                val noise = noiseGen.noise(x.toFloat(), z.toFloat())

                                val regionNumber: Int = ((noise * 1000000.0).toInt() + 1000000)
                                if (!regions.contains(regionNumber)) {
                                    regions[regionNumber] = mutableSetOf()
                                }
                                regions[regionNumber]!!.add(BlockVector2.at(x, z))

                                progress.addAndGet(1)
                            }
                        }
                    }
                }.runTaskAsynchronously(NoiseRegions.PLUGIN)

            }
            "create" -> {
                polygons.clear()
                val biomes = buildMap<String, List<Biome>> {
                    val config = YamlConfig(NoiseRegions.PLUGIN.dataFolder, "config.yml").config
                    val section = config.getConfigurationSection("types")!!
                    for (type in section.getKeys(false)) {
                        put(type, buildList {
                            for (biomeName in section.getStringList(type)) {
                                add(Biome.valueOf(biomeName.uppercase()))
                            }
                        })
                    }
                }

                val totalProgress = regions.size
                val progress = AtomicInteger(0)
                val biomeRegionNames = mutableMapOf<String, AtomicInteger>()
                val animation = CharAnimation(CharAnimation.Style.SQUARE_BLOCK)

                Thread {
                    regions.forEach { (regionNumber, blocks) ->
                        asyncExecutor.submit {
                            println("Checking region $regionNumber with ${blocks.size} blocks, First location: ${blocks.first()}}")
                            val clusters = try { getClusters(blocks.toList(), 5) } catch (e: StackOverflowError) {
                                listOf(blocks)
                            }
                            for (cluster in clusters) {
                                val removedBlocks = mutableSetOf<BlockVector2>()
                                for (block in cluster) {
                                    val biome = sender.world.getBiome(block.x, block.z)
                                    if (oceanBiomes.contains(biome)) {
                                        removedBlocks.add(block)
                                    }
                                }
                                cluster.removeAll(removedBlocks)

                                if (cluster.size < config.config.getInt("small_cluster_skip", 2000)) {
                                    println("Skipping a small cluster. 2d size was: ${cluster.size}")
                                    continue
                                }

                                val biomeScores = mutableMapOf<String, Int>()
                                for (biome in biomes.keys) {
                                    biomeScores[biome] = 0
                                }

                                for (block in cluster) {
                                    val blockBiome = sender.world.getHighestBlockAt(block.x, block.z).biome
                                    for (biome in biomes) {
                                        if (biome.value.contains(blockBiome)) {
                                            biomeScores[biome.key]!!.plus(1)
                                        }
                                    }
                                }

                                var biomeName: String? = null
                                var highestScore = Int.MIN_VALUE
                                for (biomeScore in biomeScores) {
                                    if (biomeScore.value > highestScore) {
                                        biomeName = biomeScore.key
                                        highestScore = biomeScore.value
                                    }
                                }

                                val regionNumberAtomic: AtomicInteger

                                if (biomeRegionNames.contains(biomeName!!)) {
                                    regionNumberAtomic = biomeRegionNames[biomeName]!!
                                } else {
                                    regionNumberAtomic = AtomicInteger(0)
                                    biomeRegionNames[biomeName] = regionNumberAtomic
                                }

                                println("Creating polygon for a cluster with size: ${cluster.size}")

                                polygons[ProtectedPolygonalRegion(
                                    "$biomeName-${regionNumberAtomic.addAndGet(1)}",
                                    getBorders(cluster.toMutableSet()).toList(),
                                    sender.world.minHeight,
                                    sender.world.maxHeight
                                )] = cluster.toSet()
                            }
                            progress.addAndGet(1)
                        }
                    }
                }.start()

                object: BukkitRunnable() {
                    override fun run() {
                        if (progress.get() >= totalProgress) {
                            sender.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', "&aCOMPLETED")))
                            cancel()
                            return
                        }
                        sender.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', "&6" + animation.get() + " &bProgress: " + ProgressBar.progressBar(
                            progress.get(),
                            totalProgress,
                            20,
                            "&4▮",
                            "&d▯"
                        ))))
                    }
                }.runTaskTimer(NoiseRegions.PLUGIN, 0, 4)
            }
            "visualizer", "vis" -> {
                if (runnable != null) {
                    sender.sendMessage("Visualizer was already activated, deactivating first..")
                    runnable!!.cancel()
                    runnable = null
                }
                runnable = object: BukkitRunnable() {
                    override fun run() {
                        for (polygon in polygons.keys) {
                            if (polygon.contains(BlockVector2.at(sender.location.blockX, sender.location.blockZ))) {
                                for (block in polygons[polygon]!!) {
                                    sender.world.spawnParticle(
                                        Particle.REDSTONE,
                                        sender.world.getBlockAt(block.x, sender.location.blockY - 1, block.z).location,
                                        1,
                                        DustOptions(
                                            Color.RED,
                                            2f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.runTaskTimer(NoiseRegions.PLUGIN, 0, 5)
                sender.sendMessage("Visualizer activated.")
            }
            "cancel" -> {
                if (runnable != null) {
                    sender.sendMessage("Visualizer has been stopped.")
                    runnable!!.cancel()
                    runnable = null
                } else {
                    sender.sendMessage("Visualizer is not running.")
                }
            }
            "save" -> {
                val container = WorldGuard.getInstance().platform.regionContainer
                val regions = container[BukkitAdapter.adapt(sender.world)]!!
                for (polygon in polygons.keys) {
                    regions.addRegion(polygon)
                }
                regions.save()
                regions.saveChanges()
                sender.sendMessage("Regions have been saved to WorldGuard.")
            }
            /*"checkregion" -> {
                sender.sendMessage("Polygons: ${polygons.size}")
                sender.sendMessage("Regions: ${regions.size}")
                for (polygon in polygons.keys) {
                    if (polygon.contains(BlockVector2.at(sender.location.blockX, sender.location.blockZ))) {
                        sender.sendMessage("You are in ${polygon.id}")
                    }
                }
            }
            "check" -> {
                val polygons = mutableListOf<ProtectedPolygonalRegion>()
                val block = BlockVector2.at(sender.location.blockX, sender.location.blockZ)
                regions.forEach { (i, blocks) ->
                    if (blocks.contains(block)) {
                        sender.sendMessage("You are in region $i")

                        val clusters = getClusters(blocks.toList(), 2)
                        for (cluster in clusters) {
                            polygons.add(ProtectedPolygonalRegion(
                                "region$i-${ThreadLocalRandom.current().nextInt(Int.MAX_VALUE)}",
                                getBorders(cluster.toMutableSet()).toList(),
                                0,
                                255
                                )
                            )
                        }
                    }
                }
            }*/
        }

        return true
    }

    fun getBorders(blocks: MutableSet<BlockVector2>): MutableSet<BlockVector2> {
        val borders = mutableSetOf<BlockVector2>()
        for (block in blocks) {
            if (isBorder(block, blocks)) {
                borders.add(block)
            }
        }
        return borders
    }

    fun isBorder(block: BlockVector2, blocks: MutableSet<BlockVector2>): Boolean {
        val x = block.x
        val z = block.z
        return !blocks.contains(BlockVector2.at(x + 1, z + 1)) ||
                !blocks.contains(BlockVector2.at(x + 1, z)) ||
                !blocks.contains(BlockVector2.at(x + 1, z - 1)) ||
                !blocks.contains(BlockVector2.at(x, z + 1)) ||
                !blocks.contains(BlockVector2.at(x, z - 1)) ||
                !blocks.contains(BlockVector2.at(x - 1, z + 1)) ||
                !blocks.contains(BlockVector2.at(x - 1, z)) ||
                !blocks.contains(BlockVector2.at(x - 1, z - 1))
    }

    fun isFourWayBorder(block: BlockVector2, blocks: MutableSet<BlockVector2>): Boolean {
        val x = block.x
        val z = block.z
        return !blocks.contains(BlockVector2.at(x + 1, z + 1)) ||
                !blocks.contains(BlockVector2.at(x + 1, z - 1)) ||
                !blocks.contains(BlockVector2.at(x - 1, z + 1)) ||
                !blocks.contains(BlockVector2.at(x - 1, z - 1))
    }

    fun getClusters(locations: List<BlockVector2>, radius: Int): List<MutableList<BlockVector2>> {
        val visited = BooleanArray(locations.size)
        val clusters = mutableListOf<MutableList<BlockVector2>>()

        for (i in locations.indices) {
            if (!visited[i]) {
                val cluster = mutableListOf<BlockVector2>()
                dfs(i, locations, radius, visited, cluster)
                clusters.add(cluster)
            }
        }

        return clusters
    }

    fun dfs(
        current: Int,
        locations: List<BlockVector2>,
        radius: Int,
        visited: BooleanArray,
        cluster: MutableList<BlockVector2>,
    ) {
        visited[current] = true
        cluster.add(locations[current])

        for (i in locations.indices) {
            if (!visited[i] && distance(locations[current], locations[i]) <= radius) {
                dfs(i, locations, radius, visited, cluster)
            }
        }
    }

    fun distance(location1: BlockVector2, location2: BlockVector2): Double {
        val dx = location1.x - location2.x
        val dy = location1.z - location2.z
        return sqrt((dx * dx + dy * dy).toDouble())
    }

    companion object {
        val oceanBiomes = listOf(Biome.OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.FROZEN_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.WARM_OCEAN)
    }

}