package com.example.lockdowndpc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Chinese resource parity for every Android string resource shipped by the app.
 *
 * The feature-owned tests were written when English and Hebrew were the only
 * complete locales, so they cannot notice a Chinese key that was never added.
 * This test discovers the files itself: any default file that declares a string
 * or plural must have a values-zh-rCN counterpart with the same translatable
 * keys and the same format arguments.
 */
class ChineseResourcesParityTest {

    private val defaultRoot = File(chineseModuleDir(), "src/main/res/values")
    private val chineseRoot = File(chineseModuleDir(), "src/main/res/values-zh-rCN")

    private val resourceFiles: List<String> = defaultRoot.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "xml" }
        .filter { file ->
            val resources = ChineseResources.read(file)
            resources.strings.isNotEmpty() || resources.plurals.isNotEmpty()
        }
        .map { it.name }
        .sorted()

    @Test
    fun everyTranslatableResourceFileHasAChineseCounterpart() {
        resourceFiles.forEach { name ->
            assertTrue("missing values-zh-rCN/$name", File(chineseRoot, name).isFile)
        }
    }

    @Test
    fun translatableStringKeysMatch() {
        forEachResource { name, english, chinese ->
            assertEquals(
                "translatable string keys differ in $name",
                english.translatableStrings.keys.sorted(),
                chinese.strings.keys.sorted(),
            )
        }
    }

    @Test
    fun untranslatableKeysAreNotCopiedIntoChinese() {
        forEachResource { name, english, chinese ->
            assertEquals(
                "translatable=\"false\" keys must not appear in values-zh-rCN/$name",
                emptyList<String>(),
                english.untranslatableKeys.filter { it in chinese.strings },
            )
        }
    }

    @Test
    fun pluralKeysMatch() {
        forEachResource { name, english, chinese ->
            assertEquals(
                "plural keys differ in $name",
                english.plurals.keys.sorted(),
                chinese.plurals.keys.sorted(),
            )
        }
    }

    @Test
    fun formatPlaceholdersMatch() {
        forEachResource { name, english, chinese ->
            english.translatableStrings.forEach { (key, text) ->
                assertEquals(
                    "format placeholders differ for $name string/$key",
                    chinesePlaceholdersOf(text),
                    chinesePlaceholdersOf(chinese.strings.getValue(key)),
                )
            }
        }
    }

    @Test
    fun pluralPlaceholdersMatch() {
        forEachResource { name, english, chinese ->
            english.plurals.forEach { (key, items) ->
                assertEquals(
                    "format placeholders differ for $name plurals/$key",
                    chineseDeclaredPlaceholders(items),
                    chineseDeclaredPlaceholders(chinese.plurals.getValue(key)),
                )
            }
        }
    }

    @Test
    fun noChineseValueIsBlank() {
        resourceFiles.forEach { name ->
            val chinese = ChineseResources.read(File(chineseRoot, name))
            assertEquals(
                "values-zh-rCN/$name holds blank strings",
                emptySet<String>(),
                chinese.strings.filterValues { it.isBlank() }.keys,
            )
            assertEquals(
                "values-zh-rCN/$name holds blank plural items",
                emptySet<String>(),
                chinese.plurals.filterValues { items -> items.values.any { it.isBlank() } }.keys,
            )
        }
    }

    @Test
    fun proseIsActuallyInChinese() {
        forEachResource { name, english, chinese ->
            val untranslated = chinese.strings
                .filter { (key, text) -> key in english.translatableStrings && chineseHasLetters(text) && !chineseHasChinese(text) }
                .keys + chinese.plurals
                .filterValues { items -> items.values.any { chineseHasLetters(it) && !chineseHasChinese(it) } }
                .keys
            assertEquals(
                "values-zh-rCN/$name still contains untranslated English prose",
                emptySet<String>(),
                untranslated,
            )
        }
    }

    @Test
    fun appNameIsChinese() {
        val strings = ChineseResources.read(File(chineseRoot, "strings.xml"))
        assertEquals("\u8bbe\u5907\u5b88\u62a4", strings.strings.getValue("app_name"))
    }

    private fun forEachResource(action: (String, ChineseResources, ChineseResources) -> Unit) {
        resourceFiles.forEach { name ->
            action(
                name,
                ChineseResources.read(File(defaultRoot, name)),
                ChineseResources.read(File(chineseRoot, name)),
            )
        }
    }
}

private val CHINESE_PLACEHOLDER = Regex("""%(?:\d+\$)?[a-zA-Z]""")
private val CHINESE = Regex("[\\u4e00-\\u9fff]")

private fun chinesePlaceholdersOf(text: String): List<String> =
    CHINESE_PLACEHOLDER.findAll(text).map { it.value }.toList().sorted()

private fun chineseDeclaredPlaceholders(items: Map<String, String>): Set<String> =
    items.values.flatMapTo(sortedSetOf<String>()) { chinesePlaceholdersOf(it) }

private fun chineseHasLetters(text: String): Boolean =
    CHINESE_PLACEHOLDER.replace(text, "").any { it.isLetter() }

private fun chineseHasChinese(text: String): Boolean = CHINESE.containsMatchIn(text)

private fun chineseModuleDir(): File {
    var candidate: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (candidate != null) {
        if (File(candidate, "src/main/res/values/strings.xml").isFile) {
            return candidate
        }
        val module = File(candidate, "app")
        if (File(module, "src/main/res/values/strings.xml").isFile) {
            return module
        }
        candidate = candidate.parentFile
    }
    throw AssertionError("could not locate the app module from ${System.getProperty("user.dir")}")
}

private class ChineseResources(
    val strings: Map<String, String>,
    val untranslatableKeys: Set<String>,
    val plurals: Map<String, Map<String, String>>,
) {
    val translatableStrings: Map<String, String>
        get() = strings.filterKeys { it !in untranslatableKeys }

    companion object {
        fun read(file: File): ChineseResources {
            require(file.isFile) { "missing resource file: $file" }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

            val strings = LinkedHashMap<String, String>()
            val untranslatable = LinkedHashSet<String>()
            val stringNodes = document.getElementsByTagName("string")
            for (index in 0 until stringNodes.length) {
                val element = stringNodes.item(index) as Element
                val name = element.getAttribute("name")
                strings[name] = element.textContent
                if (element.getAttribute("translatable") == "false") {
                    untranslatable += name
                }
            }

            val plurals = LinkedHashMap<String, Map<String, String>>()
            val pluralNodes = document.getElementsByTagName("plurals")
            for (index in 0 until pluralNodes.length) {
                val element = pluralNodes.item(index) as Element
                val items = LinkedHashMap<String, String>()
                val itemNodes = element.getElementsByTagName("item")
                for (item in 0 until itemNodes.length) {
                    val itemElement = itemNodes.item(item) as Element
                    items[itemElement.getAttribute("quantity")] = itemElement.textContent
                }
                plurals[element.getAttribute("name")] = items
            }

            return ChineseResources(strings, untranslatable, plurals)
        }
    }
}