package com.brahmadeo.supertonic.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.utils.HistoryItem
import com.brahmadeo.supertonic.tts.utils.HistoryManager

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var clearBtn: Button
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.historyRecyclerView)
        clearBtn = findViewById(R.id.clearHistoryBtn)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadHistory()

        clearBtn.setOnClickListener {
            HistoryManager.clearHistory(this)
            loadHistory()
        }
    }

    private fun loadHistory() {
        val items = HistoryManager.loadHistory(this)
        adapter = HistoryAdapter(items) { item ->
            val resultIntent = Intent()
            resultIntent.putExtra("selected_text", item.text)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
        recyclerView.adapter = adapter
    }

    class HistoryAdapter(
        private val items: List<HistoryItem>,
        private val onClick: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.historyText)
            val date: TextView = view.findViewById(R.id.historyDate)
            val voice: TextView = view.findViewById(R.id.historyVoice)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.text
            holder.date.text = item.dateString
            holder.voice.text = item.voiceName
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
