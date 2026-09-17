package moe.meowrealms.noir.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2FloatMap
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap
import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.model.ModelManager
import moe.meowrealms.noir.network.data.DispatchServerDrivenProperty
import moe.meowrealms.noir.utils.FastutilMapAdapterFactory
import org.bukkit.entity.Player

class PlayerData (
    @SerializedName("selected_model_id")
    public var selectedModelId: String = ModelManager.getDefaultModelConfig(
        NoirConstants.ModelDefaults.DEFAULT_MODEL_ID,
        NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
    ).left,
    @SerializedName("selected_model_texture")
    public var selectedModelTexture: String = ModelManager.getDefaultModelConfig(
        NoirConstants.ModelDefaults.DEFAULT_MODEL_ID,
        NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
    ).right,
    @SerializedName("mandatory")
    public var mandatory: Boolean = false,
    @SerializedName("disabled")
    public var disabled: Boolean = false,
    // 齿轮设置的唯一权威存储：按模型 ID 保存，模型 ID 在所有子服之间稳定，
    // 不依赖模型文件内容 hash，也不会因为切换模型而被清空。
    @SerializedName("molang_model_variables")
    public var molangModelVariables: MutableMap<String, Object2FloatMap<String>>? = LinkedHashMap(),
    // 以下两组字段只为兼容 1.3.2 及更早的存档保留，读取时迁移，写入时同步维护。
    @SerializedName("molang_datastorage")
    public var molangVariables: Int2ObjectMap<Object2FloatMap<String>>? = Int2ObjectOpenHashMap(),
    @SerializedName("molang_current_model_id")
    public var molangCurrentModelId: String? = null,
    @SerializedName("molang_current_variables")
    public var molangCurrentVariables: Object2FloatMap<String>? = null,
    @SerializedName("stared_models")
    public var staredModels: MutableSet<String> = HashSet(),
    // 使用可空字段兼容旧 JSON：字段缺失时 Gson 会留下 null，业务层将其解释为默认开启。
    // alternate 兼容 1.2.3 曾使用的临时字段名。
    @SerializedName(value = "broadcast_reception_enabled", alternate = ["personal_announcements_enabled"])
    private var broadcastReceptionEnabled: Boolean? = true
){
    @Transient
    lateinit var owner: Player
    @Transient
    lateinit var animationData: DispatchServerDrivenProperty

    companion object {
        const val MAX_SAVED_MOLANG_VARIABLES_PER_MODEL = 256

        // 每个玩家最多保留多少个模型的齿轮设置，防止存档和 MySQL 行无限增长。
        const val MAX_SAVED_MOLANG_MODELS = 64

        val GSON: Gson = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapterFactory(FastutilMapAdapterFactory())
            .create()
    }

    @Volatile
    @Transient
    private var dirty: Boolean = false

    fun initSubComponents(owner: Player) {
        this.owner = owner
        this.animationData = this.createModelState(this.owner)
    }

    fun markDirty() {
        this.dirty = true
    }

    fun isDirty(): Boolean{
        return this.dirty
    }

    fun markClean() {
        this.dirty = false
    }

    /** 旧玩家数据没有此字段时，默认接收全服 YSM 状态广播。 */
    fun isBroadcastReceptionEnabled(): Boolean = this.broadcastReceptionEnabled != false

    /** 只切换该玩家自己的接收状态；调用方负责标记脏数据并异步保存。 */
    fun toggleBroadcastReception(): Boolean {
        val newValue = !this.isBroadcastReceptionEnabled()
        this.broadcastReceptionEnabled = newValue
        return newValue
    }

    /**
     * 合并客户端上传的齿轮变量增量。
     * 存储键是当前选中的模型 ID，因此退出重进和切换子服都能读回同一份设置。
     */
    fun mergeMolangVariables(modelHashId: Int, variables: Object2FloatMap<String>): Boolean {
        val modelId = this.selectedModelId
        val storage = this.ensureModelVariableStorage()
        val stored = storage[modelId]

        val existingKeys = stored?.keys ?: emptySet()
        val newKeyCount = variables.keys.count { it !in existingKeys }
        if ((stored?.size ?: 0) + newKeyCount > MAX_SAVED_MOLANG_VARIABLES_PER_MODEL) {
            return false
        }

        val merged = if (stored == null) {
            Object2FloatOpenHashMap(variables)
        } else {
            stored.also { it.putAll(variables) }
        }

        // 先移除再放入，让 LinkedHashMap 的迭代顺序代表最近使用顺序，淘汰时优先丢最久未用的模型。
        storage.remove(modelId)
        storage[modelId] = merged
        this.enforceModelStorageLimit(modelId)

        this.syncLegacyMolangFields(modelHashId, merged)
        this.markDirty()
        return true
    }

    /**
     * 读取当前选中模型的齿轮设置；没有任何记录时返回 null，让客户端使用模型默认值。
     * 旧版本存档在这里一次性迁移到按模型 ID 的新结构。
     */
    fun savedMolangVariables(modelHashId: Int): Object2FloatMap<String>? {
        val modelId = this.selectedModelId
        val storage = this.ensureModelVariableStorage()
        storage[modelId]?.let { return Object2FloatOpenHashMap(it) }

        // 迁移 1.3.2 的 molang_current_* 字段，再回退到更早按模型内容 hash 保存的数据。
        val legacyCurrent = if (this.molangCurrentModelId == modelId) this.molangCurrentVariables else null
        val legacy = legacyCurrent ?: this.ensureMolangStorage()[modelHashId] ?: return null

        val migrated = Object2FloatOpenHashMap(legacy)
        storage[modelId] = migrated
        this.enforceModelStorageLimit(modelId)
        this.syncLegacyMolangFields(modelHashId, migrated)
        this.markDirty()
        return Object2FloatOpenHashMap(migrated)
    }

    /**
     * 取出要下发给客户端的齿轮变量，永远返回非 null。
     *
     * 没有任何存档时返回空表，这一个"种子包"不能省：客户端只有收到全量 molang 变量
     * 才会建立 RoamingStruct，而 RoamingStruct 是玩家齿轮改动回传服务端的唯一出口。
     * 跳过发包会让"服务端没存过所以不发、客户端没收到所以不报"互相卡死，第一份设置永远存不进来。
     */
    fun molangVariablesForRestore(modelHashId: Int): Object2FloatMap<String> {
        return this.savedMolangVariables(modelHashId) ?: Object2FloatOpenHashMap()
    }

    /**
     * 玩家切换到另一个模型：不删除任何模型的齿轮设置。
     * 新模型使用它自己保存过的设置，从未用过的模型才回到模型默认值，切回原模型时自动恢复。
     * 这里只重建兼容旧版本的当前模型指针字段。
     */
    fun onModelSwitched(previousModelId: String) {
        if (previousModelId == this.selectedModelId) {
            return
        }

        val restored = this.ensureModelVariableStorage()[this.selectedModelId]
        this.molangCurrentModelId = if (restored == null) null else this.selectedModelId
        this.molangCurrentVariables = restored?.let { Object2FloatOpenHashMap(it) }
        this.markDirty()
    }

    /** 同步写回旧字段，保证降级或尚未升级的子服仍能读到当前模型的设置。 */
    private fun syncLegacyMolangFields(modelHashId: Int, variables: Object2FloatMap<String>) {
        this.molangCurrentModelId = this.selectedModelId
        this.molangCurrentVariables = Object2FloatOpenHashMap(variables)

        val legacyStorage = this.ensureMolangStorage()
        legacyStorage[modelHashId] = Object2FloatOpenHashMap(variables)

        // 旧字段只是兼容镜像，同样要有上限；权威数据在 molangModelVariables 中。
        while (legacyStorage.size > MAX_SAVED_MOLANG_MODELS) {
            val evicted = legacyStorage.keys.firstOrNull { it != modelHashId } ?: break
            legacyStorage.remove(evicted)
        }
    }

    /** 超出上限时淘汰最久未使用的模型，当前正在使用的模型永远保留。 */
    private fun enforceModelStorageLimit(protectedModelId: String) {
        val storage = this.molangModelVariables ?: return
        while (storage.size > MAX_SAVED_MOLANG_MODELS) {
            val eldest = storage.keys.firstOrNull { it != protectedModelId } ?: break
            storage.remove(eldest)
        }
    }

    private fun ensureModelVariableStorage(): MutableMap<String, Object2FloatMap<String>> {
        val current = this.molangModelVariables
        if (current != null) return current
        return LinkedHashMap<String, Object2FloatMap<String>>().also {
            this.molangModelVariables = it
        }
    }

    private fun ensureMolangStorage(): Int2ObjectMap<Object2FloatMap<String>> {
        val current = this.molangVariables
        if (current != null) return current
        return Int2ObjectOpenHashMap<Object2FloatMap<String>>().also {
            this.molangVariables = it
        }
    }

    /** 在玩家实体上下文创建纯数据副本，异步存储线程不直接读取实时玩家状态。 */
    fun persistenceCopy(): PlayerData {
        return GSON.fromJson(GSON.toJson(this), PlayerData::class.java)
    }

    fun validateAndCorrectModelSelection(defaultModelIdFallback: String, defaultTextureFallback: String) {
        val validateResult = ModelManager.validateSelectedModel(this.selectedModelId, this.selectedModelTexture)
        val defaultModelConfig = ModelManager.getDefaultModelConfig(defaultModelIdFallback, defaultTextureFallback)

        // model not found
        if (!validateResult.left) {
            this.selectedModelId = defaultModelConfig.left
            this.selectedModelTexture = defaultModelConfig.right

            this.markDirty()

        // else: texture not found
        } else if (!validateResult.right) {
            this.selectedModelTexture = defaultModelConfig.right

            this.markDirty()
        }
    }

    fun validateModelSelection(): Boolean {
        val validateResult = ModelManager.validateSelectedModel(this.selectedModelId, this.selectedModelTexture)

        return validateResult.left && validateResult.right
    }


    fun createModelState(player: Player): DispatchServerDrivenProperty {
        val instanced = DispatchServerDrivenProperty(player.entityId)

        instanced.clear(player.entityId)

        return instanced
    }
}
