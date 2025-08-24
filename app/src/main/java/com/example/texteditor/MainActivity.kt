package com.example.texteditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var lineNumbers: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var fileName: TextView
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var copyButton: ImageButton
    private lateinit var cutButton: ImageButton
    private lateinit var pasteButton: ImageButton

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var currentFileUri: Uri? = null

    private var lastFindIndex = 0

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { openFile(it) }
    }
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri: Uri? ->
        uri?.let { saveToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)
        lineNumbers = findViewById(R.id.lineNumbers)
        menuButton = findViewById(R.id.menuButton)
        fileName = findViewById(R.id.fileName)
        undoButton = findViewById(R.id.undoButton)
        redoButton = findViewById(R.id.redoButton)
        copyButton = findViewById(R.id.copyButton)
        cutButton = findViewById(R.id.cutButton)
        pasteButton = findViewById(R.id.pasteButton)

        // Match line numbers with editor
        lineNumbers.setLineSpacing(editor.lineSpacingExtra, editor.lineSpacingMultiplier)
        lineNumbers.textSize = editor.textSize / resources.displayMetrics.scaledDensity
        lineNumbers.typeface = editor.typeface

        // Update line numbers & word/char count
        fun updateLineNumbers() {
            val lines = editor.lineCount
            val builder = StringBuilder()
            for (i in 1..lines) builder.append(i).append("\n")
            lineNumbers.text = builder.toString()
        }

        fun updateWordCharCount() {
            val text = editor.text.toString()
            val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
            val chars = text.length
            status.text = "Words: $words | Chars: $chars"
        }

        // TextWatcher for undo/redo & updates
        editor.addTextChangedListener(object : TextWatcher {
            private var previousText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousText = s.toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (previousText != s.toString()) {
                    undoStack.push(previousText)
                    redoStack.clear()
                }
                updateLineNumbers()
                updateWordCharCount()
            }
        })

        editor.viewTreeObserver.addOnGlobalLayoutListener { updateLineNumbers() }

        // Hamburger menu
        menuButton.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.menu.add("New File")
            popup.menu.add("Open")
            popup.menu.add("Save")
            popup.menu.add("Find/Replace")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "New File" -> newFile()
                    "Open" -> openFileLauncher.launch(arrayOf("*/*"))
                    "Save" -> {
                        if (currentFileUri != null) saveToUri(currentFileUri!!)
                        else saveFileLauncher.launch("untitled.txt")
                    }
                    "Find/Replace" -> showFindReplaceDialog()
                }
                true
            }
            popup.show()
        }

        // Top toolbar
        undoButton.setOnClickListener { undo() }
        redoButton.setOnClickListener { redo() }

        // Bottom toolbar
        copyButton.setOnClickListener { copy() }
        cutButton.setOnClickListener { cut() }
        pasteButton.setOnClickListener { paste() }

        fileName.text = "untitled.txt"
    }

    // Undo / Redo
    private fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.push(editor.text.toString())
            editor.setText(undoStack.pop())
            editor.setSelection(editor.text.length)
        }
    }

    private fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.push(editor.text.toString())
            editor.setText(redoStack.pop())
            editor.setSelection(editor.text.length)
        }
    }

    // Copy / Cut / Paste
    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        if (start != end) {
            val textToCopy = editor.text.substring(start, end)
            clipboard.setPrimaryClip(ClipData.newPlainText("text", textToCopy))
        }
    }

    private fun cut() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        if (start != end) {
            val textToCut = editor.text.substring(start, end)
            clipboard.setPrimaryClip(ClipData.newPlainText("text", textToCut))
            editor.text.replace(start, end, "")
        }
    }

    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        clip?.getItemAt(0)?.text?.let { textToPaste ->
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(0)
            editor.text.replace(start, end, textToPaste)
            editor.setSelection(start + textToPaste.length)
        }
    }

    // File operations
    private fun newFile() {
        editor.setText("")
        fileName.text = "untitled.txt"
        currentFileUri = null
        undoStack.clear()
        redoStack.clear()
    }

    private fun openFile(uri: Uri) {
        contentResolver.openInputStream(uri)?.bufferedReader().use {
            val text = it?.readText() ?: ""
            editor.setText(text)
            editor.setSelection(editor.text.length)
        }
        currentFileUri = uri
        fileName.text = getFileName(uri)
        undoStack.clear()
        redoStack.clear()
    }

    private fun saveToUri(uri: Uri) {
        contentResolver.openOutputStream(uri)?.bufferedWriter().use {
            it?.write(editor.text.toString())
        }
        currentFileUri = uri
        fileName.text = getFileName(uri)
    }

    private fun getFileName(uri: Uri): String {
        var name = "untitled.txt"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    // Find & Replace
    private fun showFindReplaceDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val findEdit = EditText(this).apply { hint = "Find" }
        val replaceEdit = EditText(this).apply { hint = "Replace" }
        val caseCheck = CheckBox(this).apply { text = "Case Sensitive" }
        val wordCheck = CheckBox(this).apply { text = "Whole Word" }
        layout.addView(findEdit)
        layout.addView(replaceEdit)
        layout.addView(caseCheck)
        layout.addView(wordCheck)

        AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(layout)
            .setPositiveButton("Find Next") { _, _ ->
                findNext(findEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked)
            }
            .setNeutralButton("Replace") { _, _ ->
                replaceCurrent(findEdit.text.toString(), replaceEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked)
            }
            .setNegativeButton("Replace All") { _, _ ->
                replaceAll(findEdit.text.toString(), replaceEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked)
            }
            .show()
    }

    private fun findNext(query: String, caseSensitive: Boolean, wholeWord: Boolean) {
        if (query.isEmpty()) return
        val pattern = if (wholeWord) "\\b${Regex.escape(query)}\\b" else Regex.escape(query)
        val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        val match = regex.find(editor.text.toString(), lastFindIndex)
        match?.let {
            editor.setSelection(it.range.first, it.range.last + 1)
            lastFindIndex = it.range.last + 1
        } ?: run { lastFindIndex = 0 }
    }

    private fun replaceCurrent(find: String, replace: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val selStart = editor.selectionStart
        val selEnd = editor.selectionEnd
        if (selStart < selEnd) {
            val selectedText = editor.text.substring(selStart, selEnd)
            val pattern = if (wholeWord) "\\b${Regex.escape(find)}\\b" else Regex.escape(find)
            val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
            if (regex.matches(selectedText)) {
                editor.text.replace(selStart, selEnd, replace)
            }
        }
        findNext(find, caseSensitive, wholeWord)
    }

    private fun replaceAll(find: String, replace: String, caseSensitive: Boolean, wholeWord: Boolean) {
        if (find.isEmpty()) return
        val pattern = if (wholeWord) "\\b${Regex.escape(find)}\\b" else Regex.escape(find)
        val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        editor.setText(regex.replace(editor.text.toString(), replace))
    }
}
