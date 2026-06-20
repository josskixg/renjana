package com.fesu.renjana.virtual

import android.content.SharedPreferences
import com.fesu.renjana.utils.RenjanaLog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual SharedPreferences implementation that stores preferences in instance-specific XML files.
 * Provides complete SharedPreferences interface with file-based persistence.
 */
class VirtualSharedPreferences(
    private val file: File,
    private val name: String
) : SharedPreferences {

    companion object {
        private const val TAG = "VirtualSP"
        private const val XML_ROOT_TAG = "map"
        private const val XML_ENCODING = "utf-8"
        private const val TYPE_STRING = "string"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_STRING_SET = "set"
        private const val ATTR_NAME = "name"
        private const val ATTR_VALUE = "value"
    }

    private val data = ConcurrentHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val lock = Any()

    init {
        loadFromDisk()
    }

    /**
     * Load preferences from XML file
     */
    private fun loadFromDisk() {
        if (!file.exists()) {
            RenjanaLog.d(TAG, "Preferences file does not exist, starting empty: ${file.absolutePath}")
            return
        }

        try {
            FileInputStream(file).use { inputStream ->
                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = false
                val parser = factory.newPullParser()
                parser.setInput(inputStream, XML_ENCODING)

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        parseTag(parser)
                    }
                    eventType = parser.next()
                }
            }
            RenjanaLog.d(TAG, "Loaded preferences from ${file.absolutePath}, ${data.size} entries")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to load preferences from ${file.absolutePath}", e)
        }
    }

    /**
     * Parse a single XML tag and add to data map
     */
    private fun parseTag(parser: XmlPullParser) {
        val tagName = parser.name
        val key = parser.getAttributeValue(null, ATTR_NAME) ?: return

        when (tagName) {
            TYPE_STRING -> {
                val value = parser.getAttributeValue(null, ATTR_VALUE)
                data[key] = value
            }
            TYPE_INT -> {
                val value = parser.getAttributeValue(null, ATTR_VALUE)?.toIntOrNull()
                data[key] = value
            }
            TYPE_LONG -> {
                val value = parser.getAttributeValue(null, ATTR_VALUE)?.toLongOrNull()
                data[key] = value
            }
            TYPE_FLOAT -> {
                val value = parser.getAttributeValue(null, ATTR_VALUE)?.toFloatOrNull()
                data[key] = value
            }
            TYPE_BOOLEAN -> {
                val value = parser.getAttributeValue(null, ATTR_VALUE)?.toBooleanStrictOrNull()
                data[key] = value
            }
            TYPE_STRING_SET -> {
                val valueStr = parser.getAttributeValue(null, ATTR_VALUE)
                val value = if (valueStr != null) {
                    valueStr.split(",").filter { it.isNotEmpty() }.toSet()
                } else {
                    emptySet<String>()
                }
                data[key] = value
            }
        }
    }

    /**
     * Save preferences to XML file
     */
    private fun saveToDisk() {
        try {
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            FileOutputStream(file).use { outputStream ->
                val factory = XmlPullParserFactory.newInstance()
                val serializer: XmlSerializer = factory.newSerializer()
                serializer.setOutput(outputStream, XML_ENCODING)
                serializer.startDocument(XML_ENCODING, true)
                serializer.startTag(null, XML_ROOT_TAG)

                data.forEach { (key, value) ->
                    when (value) {
                        is String -> {
                            serializer.startTag(null, TYPE_STRING)
                            serializer.attribute(null, ATTR_NAME, key)
                            serializer.attribute(null, ATTR_VALUE, value)
                            serializer.endTag(null, TYPE_STRING)
                        }
                        is Int -> {
                            serializer.startTag(null, TYPE_INT)
                            serializer.attribute(null, ATTR_NAME, key)
                            serializer.attribute(null, ATTR_VALUE, value.toString())
                            serializer.endTag(null, TYPE_INT)
                        }
                        is Long -> {
                            serializer.startTag(null, TYPE_LONG)
                            serializer.attribute(null, ATTR_NAME, key)
                            serializer.attribute(null, ATTR_VALUE, value.toString())
                            serializer.endTag(null, TYPE_LONG)
                        }
                        is Float -> {
                            serializer.startTag(null, TYPE_FLOAT)
                            serializer.attribute(null, ATTR_NAME, key)
                            serializer.attribute(null, ATTR_VALUE, value.toString())
                            serializer.endTag(null, TYPE_FLOAT)
                        }
                        is Boolean -> {
                            serializer.startTag(null, TYPE_BOOLEAN)
                            serializer.attribute(null, ATTR_NAME, key)
                            serializer.attribute(null, ATTR_VALUE, value.toString())
                            serializer.endTag(null, TYPE_BOOLEAN)
                        }
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val stringSet = value as? Set<String>
                            if (stringSet != null) {
                                serializer.startTag(null, TYPE_STRING_SET)
                                serializer.attribute(null, ATTR_NAME, key)
                                serializer.attribute(null, ATTR_VALUE, stringSet.joinToString(","))
                                serializer.endTag(null, TYPE_STRING_SET)
                            }
                        }
                        null -> {
                            RenjanaLog.w(TAG, "Skipping null value for key: $key")
                        }
                    }
                }

                serializer.endTag(null, XML_ROOT_TAG)
                serializer.endDocument()
                serializer.flush()
            }

            RenjanaLog.d(TAG, "Saved preferences to ${file.absolutePath}")
        } catch (e: IOException) {
            RenjanaLog.e(TAG, "Failed to save preferences to ${file.absolutePath}", e)
        }
    }

    override fun getAll(): MutableMap<String, *> {
        return data.toMutableMap()
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        val value = data[key]
        return if (value is String) value else defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        if (key == null) return defValues
        val value = data[key]
        return if (value is Set<*> && value.all { it is String }) {
            @Suppress("UNCHECKED_CAST")
            (value as Set<String>).toMutableSet()
        } else {
            defValues
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue
        val value = data[key]
        return if (value is Int) value else defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue
        val value = data[key]
        return if (value is Long) value else defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null) return defValue
        val value = data[key]
        return if (value is Float) value else defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue
        val value = data[key]
        return if (value is Boolean) value else defValue
    }

    override fun contains(key: String?): Boolean {
        if (key == null) return false
        return data.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return VirtualEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            synchronized(listeners) {
                listeners.add(listener)
            }
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }
    }

    /**
     * Notify listeners of preference changes
     */
    private fun notifyListeners(key: String) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onSharedPreferenceChanged(this, key)
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error notifying preference listener", e)
                }
            }
        }
    }

    /**
     * Editor implementation for VirtualSharedPreferences
     */
    private inner class VirtualEditor : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = value
            }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = values?.toSet()
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = value
            }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = value
            }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = value
            }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) {
                modifications[key] = value
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            return try {
                apply()
                true
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to commit preferences", e)
                false
            }
        }

        override fun apply() {
            synchronized(lock) {
                val changedKeys = mutableSetOf<String>()

                if (clearAll) {
                    val oldKeys = data.keys.toSet()
                    data.clear()
                    changedKeys.addAll(oldKeys)
                }

                removals.forEach { key ->
                    if (data.containsKey(key)) {
                        data.remove(key)
                        changedKeys.add(key)
                    }
                }

                modifications.forEach { (key, value) ->
                    val oldValue = data[key]
                    if (oldValue != value) {
                        if (value != null) {
                            data[key] = value
                        } else {
                            data.remove(key)
                        }
                        changedKeys.add(key)
                    }
                }

                saveToDisk()

                changedKeys.forEach { key ->
                    notifyListeners(key)
                }
            }
        }
    }
}
