package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val text: String,
    val timestamp: Long,
    val dateString: String,
    val voiceName: String
)

object HistoryManager {
    private const val FILE_NAME = "synthesis_history.json"
    private const val MAX_ITEMS = 10

    fun saveItem(context: Context, text: String, voiceName: String) {
        val list = loadHistory(context).toMutableList()
        
        // Remove duplicate text if exists to bump it to top
        list.removeAll { it.text == text }
        
        val timestamp = System.currentTimeMillis()
        val dateString = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
        
        list.add(0, HistoryItem(text, timestamp, dateString, voiceName))
        
        if (list.size > MAX_ITEMS) {
            list.removeAt(list.size - 1)
        }
        
        saveList(context, list)
    }

    fun loadHistory(context: Context): List<HistoryItem> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        val items = mutableListOf<HistoryItem>()
        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(HistoryItem(
                    text = obj.getString("text"),
                    timestamp = obj.getLong("timestamp"),
                    dateString = obj.optString("dateString", ""),
                    voiceName = obj.optString("voiceName", "Unknown")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }
    
    fun clearHistory(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }

    private fun saveList(context: Context, list: List<HistoryItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("text", item.text)
                obj.put("timestamp", item.timestamp)
                obj.put("dateString", item.dateString)
                obj.put("voiceName", item.voiceName)
                jsonArray.put(obj)
            }
            
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
