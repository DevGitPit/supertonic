package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.LexiconItem
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { performImport(it) }
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.lexicon_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                performExport()
                true
            }
            R.id.action_import -> {
                importLauncher.launch("application/json")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performExport() {
        if (rules.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_rules_export), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val jsonArray = JSONArray()
            for (rule in rules) {
                val obj = JSONObject()
                obj.put("id", rule.id)
                obj.put("term", rule.term)
                obj.put("replacement", rule.replacement)
                obj.put("ignoreCase", rule.ignoreCase)
                jsonArray.put(obj)
            }

            val fileName = "supertonic_lexicon.json"
            val file = File(cacheDir, fileName)
            file.writeText(jsonArray.toString(2))

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser_title)))

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.export_failed_fmt, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()
            inputStream.close()

            val jsonArray = JSONArray(jsonString)
            val importedItems = mutableListOf<LexiconItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.has("term") && obj.has("replacement")) {
                    importedItems.add(LexiconItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        term = obj.getString("term"),
                        replacement = obj.getString("replacement"),
                        ignoreCase = obj.optBoolean("ignoreCase", true)
                    ))
                }
            }

            if (importedItems.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_valid_rules), Toast.LENGTH_SHORT).show()
                return
            }

            var addedCount = 0
            var updatedCount = 0
            val currentRules = LexiconManager.load(this).toMutableList()

            for (imported in importedItems) {
                val existingIndex = currentRules.indexOfFirst { it.term == imported.term }
                if (existingIndex == -1) {
                    currentRules.add(imported)
                    addedCount++
                } else {
                    val existing = currentRules[existingIndex]
                    if (existing.replacement != imported.replacement || existing.ignoreCase != imported.ignoreCase) {
                        existing.replacement = imported.replacement
                        existing.ignoreCase = imported.ignoreCase
                        updatedCount++
                    }
                }
            }

            if (addedCount > 0 || updatedCount > 0) {
                LexiconManager.save(this, currentRules)
                LexiconManager.reload(this)
                loadRules()
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.import_complete_title))
                    .setMessage(getString(R.string.import_stats_fmt, addedCount, updatedCount))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } else {
                Toast.makeText(this, getString(R.string.import_no_changes), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.import_error), Toast.LENGTH_LONG).show()
        }
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
                Toast.makeText(this, getString(R.string.enter_replacement_msg), Toast.LENGTH_SHORT).show()
            }
        }

        saveBtn.setOnClickListener {
            val term = editTerm.text.toString().trim()
            val replacement = editReplacement.text.toString().trim()
            
            if (term.isEmpty() || replacement.isEmpty()) {
                Toast.makeText(this, getString(R.string.empty_fields_msg), Toast.LENGTH_SHORT).show()
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
            LexiconManager.reload(this)
            loadRules()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun testPronunciation(text: String) {
        if (!isBound || playbackService == null) {
            Toast.makeText(this, getString(R.string.engine_error), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val voiceFile = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        val stylePath = File(filesDir, "voice_styles/$voiceFile").absolutePath
        val steps = prefs.getInt("diffusion_steps", 5)

        try {
            playbackService?.stop()
            playbackService?.synthesizeAndPlay(text, "en", stylePath, 1.0f, steps, 0)
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