package net.atlantis.artillery.model

import io.reactivex.Observable
import net.atlantis.artillery.ext.getEntityMetadata
import net.atlantis.artillery.ext.getIntMetadata
import net.atlantis.artillery.ext.playSound
import net.atlantis.artillery.ext.random
import net.atlantis.artillery.ext.setIntMetadata
import net.atlantis.artillery.ext.spawnParticle
import net.atlantis.artillery.metadata.MetadataKey
import net.atlantis.artillery.range.SkillRange
import net.atlantis.artillery.range.SkillRectRange
import net.atlantis.artillery.util.LocationUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Container
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.concurrent.TimeUnit

class Bombardment(private val player: Player, private val plugin: JavaPlugin) {
    private val explodeEntities = mutableListOf<Entity>()

    private val range = SkillRectRange(0.5, 0.5, 0.5)

    fun execute() {
        object : BukkitRunnable() {
            override fun run() {
                start()
            }
        }.runTaskLater(plugin, 1)
    }

    fun start() {
        val cannonEntity = player.getEntityMetadata(MetadataKey.ARTILLERY_ENTITY.key) ?: return
        val taskId = cannonEntity.getIntMetadata(MetadataKey.TASK_ID.key)
        if (taskId == null) {
            run(cannonEntity)
        } else {
            plugin.server.scheduler.cancelTask(taskId)
            cannonEntity.removeMetadata(MetadataKey.TASK_ID.key, plugin)
        }
    }

    private fun run(cannonEntity: Entity) {
        val task = object : BukkitRunnable() {
            override fun run() {
                val cannonPart = cannonEntity.getEntityMetadata(ArtilleryEntity.CANNON_3) as ArmorStand? ?: return
                val cannonPart2 = cannonEntity.getEntityMetadata(ArtilleryEntity.CANNON_2) as ArmorStand? ?: return
                val vector = cannonPart.location.subtract(cannonPart2.location).toVector().normalize()
                if (!consume()) {
                    cannonEntity.getIntMetadata(MetadataKey.TASK_ID.key)?.let {
                        plugin.server.scheduler.cancelTask(it)
                        cannonEntity.removeMetadata(MetadataKey.TASK_ID.key, plugin)
                    }
                    return
                }

                val initialLocation = cannonPart.eyeLocation
                initialLocation.add(vector.x * 1.0, vector.y * 1.0, vector.z * 1.0)
                val x = 0.0
                val y = 0.0
                val z = 0.0
                val currentLocation = initialLocation.let { LocationUtil.transform(it, x, y, z) }

                Observable.interval(20, TimeUnit.MILLISECONDS)
                        .take(120)
                        .doOnNext {
                            if (currentLocation.block.type != Material.AIR) {
                                throw SkillResetException()
                            }
                            effect(currentLocation, range)
                            drawEffect(currentLocation, it, vector)
                        }
                        .doOnError {
                            explode(currentLocation)
                        }
                        .doOnComplete {
                            explode(currentLocation)
                        }
                        .subscribe()
            }
        }.runTaskTimer(plugin, 0, 40)
        val taskId = task.taskId
        cannonEntity.setIntMetadata(plugin, MetadataKey.TASK_ID.key, taskId)
    }

    private fun consume(): Boolean {
        val blocks = SkillRectRange(4.0, 2.0, 4.0).getBlocks(player.location)
        blocks.forEach { block ->
            val state = block.state
            if (state is Container) {
                state.inventory.contents.filterNotNull().forEach {
                    if (it.type == Material.EGG) {
                        it.amount -= 1
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun effect(location: Location, range: SkillRange) {
        object : BukkitRunnable() {
            override fun run() {
                val entities = range.getEntities(location).filterNot { it is ArmorStand }
                if (entities.isNotEmpty()) {
                    entities.forEach {
                        explode(location)
                        throw SkillResetException()
                    }
                }
            }
        }.runTaskLater(plugin, 0)
    }

    /**
     * エフェクトを描く

     * @param data data
     */
    private fun drawEffect(currentLocation: Location, data: Long, vector: Vector) {
        if (data == 1.toLong()) {
            currentLocation.playSound(Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.933f)
        }
        val drawVector = vector.clone().multiply(0.4)
        currentLocation.spawnParticle(Particle.CLOUD, 1)
        currentLocation.spawnParticle(Particle.END_ROD, 3)
        if (data % 10 == 0.toLong()) {
            currentLocation.playSound(Sound.ENTITY_CREEPER_PRIMED, 3.0f, 0.933f)
        }
        effect(currentLocation, range)
        currentLocation.add(drawVector)
        currentLocation.add(0.0, -0.00004 * data * data, 0.0)
    }

    private fun explode(location: Location) {
        Observable.interval(20, TimeUnit.MILLISECONDS)
                .take(30)
                .filter { data -> data % 5 == 0.toLong() }
                .doOnNext { drawExplode(location, it) }
                .subscribe()
    }

    private fun drawExplode(location: Location, data: Long) {
        val radius = data / 5
        if (data == 5.toLong()) {
            location.playSound(Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.0f, 1.933f)
        }
        for (i in 0..10) {
            for (j in 0..10) {
                val angle1 = 2.0 * Math.PI * i.toDouble() / 10
                val angle2 = 2.0 * Math.PI * j.toDouble() / 10
                val x = radius * Math.sin(angle1) * Math.cos(angle2)
                val y = radius * Math.sin(angle1) * Math.sin(angle2)
                val z = radius * Math.cos(angle1)
                val currentLocation = location.clone().add(x, y, z)
                if (data % 3 == 0L) {
                    currentLocation.random(0.1, 0.1, 0.1).spawnParticle(Particle.EXPLOSION_LARGE, 1)
                }
                currentLocation.random(0.1, 0.1, 0.1).spawnParticle(Particle.FLAME, 2)
                explodeEffect(currentLocation, range)
            }
        }
    }

    private fun explodeEffect(location: Location, range: SkillRange) {
        object : BukkitRunnable() {
            override fun run() {
                val entities = range.getEntities(location)
                if (entities.isNotEmpty()) {
                    entities.forEach {
                        if (!explodeEntities.contains(it)) {
                            enemyEffect(it)
                            explodeEntities.add(it)
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 0)
    }

    private fun enemyEffect(entity: Entity) {
        if (entity is LivingEntity) {
            entity.damage(17.0)
        }
    }

    class SkillResetException : Exception()
}