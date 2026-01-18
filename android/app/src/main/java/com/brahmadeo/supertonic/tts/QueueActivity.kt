package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.utils.QueueManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Collections

class QueueActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: QueueAdapter
    private lateinit var clearFab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)

        val toolbar = findViewById<MaterialToolbar>(R.id.queueToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        recyclerView = findViewById(R.id.queueList)
        clearFab = findViewById(R.id.clearQueueFab)

        adapter = QueueAdapter(QueueManager.getList().toMutableList()) { viewHolder ->
            itemTouchHelper.startDrag(viewHolder)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        itemTouchHelper.attachToRecyclerView(recyclerView)

        clearFab.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Queue")
                .setMessage("Are you sure you want to remove all items from the queue?")
                .setPositiveButton("Yes") { _, _ ->
                    QueueManager.clear()
                    adapter.updateData(emptyList())
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // Better listener handling
    private val queueListener: (List<com.brahmadeo.supertonic.tts.utils.QueueItem>) -> Unit = { items ->
        runOnUiThread {
            adapter.updateData(items)
        }
    }

    // Override onStart/onStop for listener
    override fun onStart() {
        super.onStart()
        QueueManager.addListener(queueListener)
    }

    override fun onStop() {
        super.onStop()
        QueueManager.removeListener(queueListener)
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPos = viewHolder.adapterPosition
            val toPos = target.adapterPosition

            // Swap in local list
            val list = adapter.getItems()
            Collections.swap(list, fromPos, toPos)
            adapter.notifyItemMoved(fromPos, toPos)

            // Update Manager (We need a method to update the backing list or swap items there)
            // Since QueueManager exposes a list but stores it internally, we should probably add a swap/move method
            // or just set the whole list.
            // For now, let's assume we need to sync the whole list back to QueueManager on clearView?
            // Actually, best to modify QueueManager to support reordering.
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.adapterPosition
            val list = adapter.getItems()

            // Remove from local
            list.removeAt(pos)
            adapter.notifyItemRemoved(pos)

            // Sync with Manager
            QueueManager.replaceAll(list)
        }

        // When drag is finished, we should sync with QueueManager
        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Sync order back to QueueManager
            // This is tricky because QueueManager.queue is private.
            // I need to add methods to QueueManager to support external modification of the list order.
            QueueManager.replaceAll(adapter.getItems())
        }
    })
}
