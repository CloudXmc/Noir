package moe.meowrealms.noir

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap
import moe.meowrealms.noir.data.PlayerData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelGearSettingsTest {

    private fun newPlayerData(): PlayerData = PlayerData.GSON.fromJson("{}", PlayerData::class.java)

    private fun roundTrip(data: PlayerData): PlayerData =
        PlayerData.GSON.fromJson(PlayerData.GSON.toJson(data), PlayerData::class.java)

    private fun variables(vararg pairs: Pair<String, Float>) = Object2FloatOpenHashMap<String>().apply {
        pairs.forEach { put(it.first, it.second) }
    }

    /** 退出重进、跨子服重新加载存档后，当前模型的齿轮设置必须原样恢复。 */
    @Test
    fun `gear settings survive a persistence round trip`() {
        val data = newPlayerData()
        data.selectedModelId = "model-a"

        assertTrue(data.mergeMolangVariables(101, variables("hat_visible" to 1.0f)))
        assertTrue(data.mergeMolangVariables(101, variables("face_type" to 3.0f)))

        val restored = roundTrip(data)
        restored.selectedModelId = "model-a"

        assertEquals(1.0f, restored.savedMolangVariables(101)?.getFloat("hat_visible"))
        assertEquals(3.0f, restored.savedMolangVariables(101)?.getFloat("face_type"))
    }

    /** 核心需求：切换模型只影响新模型，原模型设置必须保留，切回时自动恢复。 */
    @Test
    fun `switching model keeps the previous model settings and restores them on switch back`() {
        val data = newPlayerData()
        data.selectedModelId = "model-a"
        assertTrue(data.mergeMolangVariables(101, variables("hat_visible" to 1.0f, "face_type" to 3.0f)))

        val previousModelId = data.selectedModelId
        data.selectedModelId = "model-b"
        data.onModelSwitched(previousModelId)

        // 新模型没有任何历史设置，回到模型默认值。
        assertNull(data.savedMolangVariables(202))

        assertTrue(data.mergeMolangVariables(202, variables("wing_visible" to 1.0f)))

        // 中途退出重进，再切回原模型。
        val restored = roundTrip(data)
        val beforeSwitchBack = restored.selectedModelId
        restored.selectedModelId = "model-a"
        restored.onModelSwitched(beforeSwitchBack)

        val modelA = restored.savedMolangVariables(101)
        assertNotNull(modelA)
        assertEquals(1.0f, modelA?.getFloat("hat_visible"))
        assertEquals(3.0f, modelA?.getFloat("face_type"))

        // model-b 的设置同样没有被切换破坏。
        val afterModelA = restored.selectedModelId
        restored.selectedModelId = "model-b"
        restored.onModelSwitched(afterModelA)
        assertEquals(1.0f, restored.savedMolangVariables(202)?.getFloat("wing_visible"))
    }

    /** 同一个模型内改动材质或重复选择同一模型，不得触发任何重置。 */
    @Test
    fun `reselecting the same model does not reset its gear settings`() {
        val data = newPlayerData()
        data.selectedModelId = "model-a"
        assertTrue(data.mergeMolangVariables(101, variables("hat_visible" to 1.0f)))

        data.onModelSwitched("model-a")

        assertEquals(1.0f, data.savedMolangVariables(101)?.getFloat("hat_visible"))
    }

    /** 1.3.2 存档只有 molang_current_* 字段，升级后必须自动迁移而不是丢失。 */
    @Test
    fun `legacy current model fields are migrated`() {
        val legacyJson = """
            {
              "selected_model_id": "model-a",
              "molang_current_model_id": "model-a",
              "molang_current_variables": { "hat_visible": 1.0 }
            }
        """.trimIndent()

        val data = PlayerData.GSON.fromJson(legacyJson, PlayerData::class.java)
        assertEquals(1.0f, data.savedMolangVariables(101)?.getFloat("hat_visible"))

        // 迁移结果必须写进新的按模型 ID 存储结构，切走再切回仍然存在。
        data.selectedModelId = "model-b"
        data.onModelSwitched("model-a")
        data.selectedModelId = "model-a"
        data.onModelSwitched("model-b")
        assertEquals(1.0f, data.savedMolangVariables(101)?.getFloat("hat_visible"))
    }

    /** 更早版本按模型内容 hash 保存的存档同样需要迁移。 */
    @Test
    fun `legacy hash keyed storage is migrated`() {
        val legacyJson = """
            {
              "selected_model_id": "model-a",
              "molang_datastorage": { "101": { "hat_visible": 1.0 } }
            }
        """.trimIndent()

        val data = PlayerData.GSON.fromJson(legacyJson, PlayerData::class.java)
        assertEquals(1.0f, data.savedMolangVariables(101)?.getFloat("hat_visible"))
    }

    /** 单个模型的变量数量必须有上限，避免恶意客户端把存档撑爆。 */
    @Test
    fun `variable count per model is bounded`() {
        val data = newPlayerData()
        data.selectedModelId = "model-a"

        val tooMany = Object2FloatOpenHashMap<String>().apply {
            repeat(PlayerData.MAX_SAVED_MOLANG_VARIABLES_PER_MODEL + 1) { put("var_$it", it.toFloat()) }
        }
        assertFalse(data.mergeMolangVariables(101, tooMany))
        assertNull(data.savedMolangVariables(101))
    }

    /**
     * 首次使用某个模型时也必须下发一份变量表（哪怕是空的）。
     *
     * 客户端只有在收到全量 molang 变量后才会建立 RoamingStruct，而 RoamingStruct 是
     * 玩家齿轮改动回传服务端的唯一出口。如果这里返回 null 让调用方跳过发包，就会形成死锁：
     * 服务端因为没存过所以不下发，客户端因为没收到所以不上报，第一份设置永远存不进来。
     */
    @Test
    fun `first use of a model still yields a seed variable table`() {
        val data = newPlayerData()
        data.selectedModelId = "wine_fox/22_elf"

        val seed = data.molangVariablesForRestore(101)

        assertTrue(seed.isEmpty(), "没有存档时必须返回空表作为种子，而不是让调用方跳过发包")
    }

    /** 已有存档时下发的必须是保存过的那一份，种子逻辑不能覆盖真实数据。 */
    @Test
    fun `restore prefers the saved variables over the empty seed`() {
        val data = newPlayerData()
        data.selectedModelId = "wine_fox/22_elf"
        assertTrue(data.mergeMolangVariables(101, variables("hat_visible" to 1.0f)))

        val restored = roundTrip(data)
        restored.selectedModelId = "wine_fox/22_elf"

        val toSend = restored.molangVariablesForRestore(101)

        assertEquals(1, toSend.size)
        assertEquals(1.0f, toSend.getFloat("hat_visible"))
    }

    /** 保存的模型数量必须有上限，并且当前模型不会被淘汰。 */
    @Test
    fun `stored model count is bounded and the current model is kept`() {
        val data = newPlayerData()

        repeat(PlayerData.MAX_SAVED_MOLANG_MODELS + 8) { index ->
            data.selectedModelId = "model-$index"
            assertTrue(data.mergeMolangVariables(index, variables("hat_visible" to index.toFloat())))
        }

        assertTrue((data.molangModelVariables?.size ?: 0) <= PlayerData.MAX_SAVED_MOLANG_MODELS)
        assertTrue((data.molangVariables?.size ?: 0) <= PlayerData.MAX_SAVED_MOLANG_MODELS)

        val lastIndex = PlayerData.MAX_SAVED_MOLANG_MODELS + 7
        data.selectedModelId = "model-$lastIndex"
        assertEquals(lastIndex.toFloat(), data.savedMolangVariables(lastIndex)?.getFloat("hat_visible"))
    }
}
