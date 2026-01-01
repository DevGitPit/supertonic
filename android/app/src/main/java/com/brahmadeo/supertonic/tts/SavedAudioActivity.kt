package com.brahmadeo.supertonic.tts

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedAudioActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SavedAudioAdapter
    private var files = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_audio)

        recyclerView = findViewById(R.id.savedAudioRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadFiles()
    }

    private fun loadFiles() {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val appDir = File(musicDir, "Supertonic Audio")
        
        if (appDir.exists()) {
            files = appDir.listFiles { _, name -> name.endsWith(".wav") }
                ?.sortedByDescending { it.lastModified() }
                ?.toMutableList() ?: mutableListOf()
        }
        
        adapter = SavedAudioAdapter(files, 
            onPlay = { file -> playAudio(file) },
            onDelete = { file -> confirmDelete(file) }
        )
        recyclerView.adapter = adapter
    }

    private fun playAudio(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "audio/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            val chooser = Intent.createChooser(intent, "Play with...")
            startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Audio")
            .setMessage("Are you sure you want to delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    loadFiles()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class SavedAudioAdapter(
        private val files: List<File>,
        private val onPlay: (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<SavedAudioAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val filename: TextView = view.findViewById(R.id.audioFilename)
            val date: TextView = view.findViewById(R.id.audioDate)
            val playBtn: ImageButton = view.findViewById(R.id.playAudioBtn)
            val deleteBtn: ImageButton = view.findViewById(R.id.deleteAudioBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_audio, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.filename.text = file.name
            
            val date = Date(file.lastModified())
            holder.audioDate.text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
            
            holder.playBtn.setOnClickListener { onPlay(file) }
            holder.deleteBtn.setOnClickListener { onDelete(file) }
        }

        override fun getItemCount() = files.size
    }
}