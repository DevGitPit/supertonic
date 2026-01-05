package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.LexiconItem
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import android.os.RemoteException
import java.io.File

class LexiconActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var addRuleFab: ExtendedFloatingActionButton
    private lateinit var adapter: LexiconAdapter
    private val rules = mutableListOf<LexiconItem>()

    private var playbackService: com.brahmadeo.supertonic.tts.service.IPlaybackService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = com.brahmadeo.supertonic.tts.service.IPlaybackService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lexicon)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.lexiconToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.lexiconRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        addRuleFab = findViewById(R.id.addRuleFab)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadRules()

        addRuleFab.setOnClickListener {
            showEditDialog(null)
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun loadRules() {
        rules.clear()
        rules.addAll(LexiconManager.load(this))
        adapter = LexiconAdapter(rules, 
            onEdit = { showEditDialog(it) },
            onDelete = { deleteRule(it) }
        )
        recyclerView.adapter = adapter
        updateEmptyState()
    }

    private fun updateEmptyState() {
        emptyStateText.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(item: LexiconItem?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_lexicon, null)
        val editTerm = dialogView.findViewById<TextInputEditText>(R.id.editTerm)
        val editReplacement = dialogView.findViewById<TextInputEditText>(R.id.editReplacement)
        val switchIgnoreCase = dialogView.findViewById<MaterialSwitch>(R.id.switchIgnoreCase)
        val testBtn = dialogView.findViewById<Button>(R.id.testRuleBtn)
        val saveBtn = dialogView.findViewById<Button>(R.id.saveRuleBtn)

        item?.let {
            editTerm.setText(it.term)
            editReplacement.setText(it.replacement)
            switchIgnoreCase.isChecked = it.ignoreCase
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        testBtn.setOnClickListener {
            val replacement = editReplacement.text.toString()
            if (replacement.isNotEmpty()) {
                testPronunciation(replacement)
            } else {
                Toast.makeText(this, "Enter replacement to test", Toast.LENGTH_SHORT).show()
            }
        }

        saveBtn.setOnClickListener {
            val term = editTerm.text.toString().trim()
            val replacement = editReplacement.text.toString().trim()
            
            if (term.isEmpty() || replacement.isEmpty()) {
                Toast.makeText(this, "Term and Replacement cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (item != null) {
                item.term = term
                item.replacement = replacement
                item.ignoreCase = switchIgnoreCase.isChecked
            } else {
                rules.add(LexiconItem(term = term, replacement = replacement, ignoreCase = switchIgnoreCase.isChecked))
            }
            
            LexiconManager.save(this, rules)
            LexiconManager.reload(this) // Ensure normalizer gets updated rules
            loadRules()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun testPronunciation(text: String) {
        if (!isBound || playbackService == null) {
            Toast.makeText(this, "Engine not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val voiceFile = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        val stylePath = File(filesDir, "voice_styles/$voiceFile").absolutePath
        val steps = prefs.getInt("diffusion_steps", 5)

        try {
            playbackService?.stop()
            playbackService?.synthesizeAndPlay(text, stylePath, 1.0f, steps, 0)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun deleteRule(item: LexiconItem) {
        rules.remove(item)
        LexiconManager.save(this, rules)
        LexiconManager.reload(this)
        loadRules()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    class LexiconAdapter(
        private val items: List<LexiconItem>,
        private val onEdit: (LexiconItem) -> Unit,
        private val onDelete: (LexiconItem) -> Unit
    ) : RecyclerView.Adapter<LexiconAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val term: TextView = view.findViewById(R.id.termText)
            val replacement: TextView = view.findViewById(R.id.replacementText)
            val deleteBtn: ImageButton = view.findViewById(R.id.deleteRuleBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lexicon_rule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.term.text = item.term
            holder.replacement.text = item.replacement
            holder.itemView.setOnClickListener { onEdit(item) }
            holder.deleteBtn.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = items.size
    }
}
