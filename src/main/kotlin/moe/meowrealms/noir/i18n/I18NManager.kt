package moe.meowrealms.noir.i18n

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

class I18NManager {
    @Volatile
    private var loadedLanguageKeys: Map<String, String> = emptyMap()

    private var languageDirectory: Path? = null

    /** 将内置语言文件释放到 plugins/Noir/lang/，方便服主直接修改。 */
    @Throws(IOException::class)
    fun initialize(pluginDataDirectory: Path, activeLanguageName: String) {
        this.languageDirectory = pluginDataDirectory.resolve("lang")
        Files.createDirectories(this.languageDirectory!!)

        for (languageName in listOf("zh_CN", "en_US")) {
            this.ensureLanguageFile(languageName)
        }

        this.loadLanguageFile(activeLanguageName)
    }

    @Throws(IOException::class)
    fun loadLanguageFile(languageName: String) {
        this.ensureLanguageFile(languageName)
        val externalFile = this.languageDirectory?.resolve("$languageName.lang")
        val externalReader = externalFile?.takeIf { Files.isRegularFile(it) }?.let {
            Files.newBufferedReader(it, StandardCharsets.UTF_8)
        }
        val bundledStream = if (externalReader == null) {
            this.javaClass.classLoader.getResourceAsStream("lang/$languageName.lang")
                ?: throw IOException("Language file not found for $languageName!")
        } else null

        val parsedKeys = HashMap<String, String>()
        val lineReader = externalReader ?: BufferedReader(InputStreamReader(bundledStream, StandardCharsets.UTF_8))
        lineReader.use { reader ->
            var languageLine: String?

            while (reader.readLine().also { languageLine = it } != null) {
                val line = languageLine ?: continue
                if (line.isBlank() || line.startsWith("#")) {
                    continue
                }

                val languageLineSplit = line.split("=", limit = 2)
                if (languageLineSplit.size == 2) {
                    parsedKeys[languageLineSplit[0]] = languageLineSplit[1]
                    continue
                }

                throw IllegalArgumentException("Invalid language file format $line!")
            }
        }

        // 原子替换，避免重载期间出现半套语言键。
        this.loadedLanguageKeys = ConcurrentHashMap(parsedKeys)
    }

    /** 升级时只追加缺失键，绝不覆盖服主已经修改的语言文本。 */
    @Throws(IOException::class)
    private fun ensureLanguageFile(languageName: String) {
        val directory = this.languageDirectory ?: return
        val target = directory.resolve("$languageName.lang")
        val resourceName = "lang/$languageName.lang"

        if (Files.notExists(target)) {
            this.javaClass.classLoader.getResourceAsStream(resourceName).use { input ->
                if (input == null) {
                    throw IOException("Language file not found for $languageName!")
                }
                Files.copy(input, target)
            }
            return
        }

        val existingText = Files.readString(target, StandardCharsets.UTF_8)
        val existingKeys = existingText.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('=') }
            .map { it.substringBefore('=') }
            .toHashSet()

        val missingLines = this.javaClass.classLoader.getResourceAsStream(resourceName).use { input ->
            if (input == null) {
                throw IOException("Language file not found for $languageName!")
            }
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('=') }
                    .filter { it.substringBefore('=') !in existingKeys }
                    .toList()
            }
        }

        if (missingLines.isEmpty()) {
            return
        }

        val separator = if (existingText.isEmpty() || existingText.endsWith('\n')) "" else "\n"
        val addition = separator + "# 新版本自动补充的默认语言键；已有键不会被覆盖。\n" +
            missingLines.joinToString("\n", postfix = "\n")
        Files.writeString(target, addition, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }

    fun i18n(
        key: String,
        subKeys: List<String> = emptyList(),
        args: List<Any> = emptyList()
    ): Component {
        if (subKeys.size != args.size) {
            throw IllegalArgumentException("Subkeys and args must be the same length")
        }

        val languageValue = this.loadedLanguageKeys[key]
            ?: throw IllegalArgumentException("Language key not found: $key")

        // 前缀独立配置；prefix 自身不能再次套用前缀。
        val prefix = this.loadedLanguageKeys["noir.prefix"].orEmpty()
        val text = if (key == "noir.prefix" || prefix.isBlank()) {
            languageValue
        } else {
            prefix + languageValue
        }

        val builtResolvers = ArrayList<TagResolver>()
        for (idx in args.indices) {
            val arg = args[idx]
            builtResolvers.add(
                Placeholder.component(
                    subKeys[idx],
                    arg as? Component ?: Component.text(arg.toString())
                )
            )
        }

        return MiniMessage.miniMessage().deserialize(text, *builtResolvers.toTypedArray())
    }

    /** 生成不附加插件前缀的玩家可见文本，例如踢出原因。 */
    fun i18nRaw(key: String): net.kyori.adventure.text.Component {
        val languageValue = this.loadedLanguageKeys[key]
            ?: throw IllegalArgumentException("Language key not found: $key")
        return MiniMessage.miniMessage().deserialize(languageValue)
    }
}
