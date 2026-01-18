package com.brahmadeo.supertonic.tts

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.utils.QueueItem
import java.io.File

class QueueAdapter(
    private var items: MutableList<QueueItem>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.queueText)
        val details: TextView = view.findViewById(R.id.queueDetails)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item.text

        val voiceName = File(item.stylePath).nameWithoutExtension
        holder.details.text = "$voiceName • ${String.format("%.2fx", item.speed)}"

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<QueueItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): MutableList<QueueItem> {
        return items
    }
}