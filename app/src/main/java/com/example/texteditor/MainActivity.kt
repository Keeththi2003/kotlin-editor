package com.example.texteditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.graphics.Color
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream
import java.util.*
import java.util.regex.Pattern

// Represents a single language's syntax rules
data class LanguageRule(
    @SerializedName("name") val name: String,
    @SerializedName("fileExtensions") val fileExtensions: List<String>,
    @SerializedName("keywordColor") val keywordColor: String,
    @SerializedName("commentColor") val commentColor: String,
    @SerializedName("stringColor") val stringColor: String,
    @SerializedName("keywords") val keywords: List<String>,
    @SerializedName("commentSymbols") val commentSymbols: List<String>,
    val stringDelimiter: String
)

// Represents the top-level structure of the JSON file
data class SyntaxRules(
    @SerializedName("languages") val rules: List<LanguageRule>
)

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
    private lateinit var compileButton: ImageButton

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var currentFileUri: Uri? = null

    // A flag to prevent the TextWatcher from firing when we update the spans.
    private var isUpdatingText = false

    // Store the loaded language rules
    private var allRules: SyntaxRules? = null

    // The currently active language rule
    private var activeRule: LanguageRule? = null

    // Last found index for Find & Replace
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
        compileButton = findViewById(R.id.compileButton)

        // Load the syntax rules from the assets folder.
        loadSyntaxRules()

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
                if (!isUpdatingText) {
                    previousText = s.toString()
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingText) {
                    return
                }

                if (previousText != s.toString()) {
                    undoStack.push(previousText)
                    redoStack.clear()
                }

                updateLineNumbers()
                updateWordCharCount()
                
                // Use the active rule to apply highlighting.
                s?.let { activeRule?.let { rule -> applySyntaxHighlighting(it, rule) } }
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
                        else saveFileLauncher.launch("untitled.kt")
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
        compileButton.setOnClickListener { compileCode() }

        // Bottom toolbar
        copyButton.setOnClickListener { copy() }
        cutButton.setOnClickListener { cut() }
        pasteButton.setOnClickListener { paste() }

        fileName.text = "untitled.kt"
    }

    /**
     * Loads syntax rules from a JSON file in the assets folder.
     */
    private fun loadSyntaxRules() {
        try {
            val inputStream = assets.open("syntax_rules.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            allRules = Gson().fromJson(jsonString, SyntaxRules::class.java)

            // Set Kotlin as the default active language
            activeRule = allRules?.rules?.find { it.name == "kotlin" }
            if (activeRule == null) {
                Toast.makeText(this, "Kotlin rules not found in JSON!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading syntax rules: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    /**
     * Applies syntax highlighting to a given Editable based on the provided rule.
     * This function is now generic and works for any language with rules in the JSON.
     *
     * @param editable The Editable object from the EditText.
     * @param rules The LanguageRule object containing the highlighting definitions.
     */
    private fun applySyntaxHighlighting(editable: Editable, rules: LanguageRule) {
        isUpdatingText = true

        val spans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }

        // Define the colors from the JSON hex strings
        val keywordColor = Color.parseColor(rules.keywordColor)
        val commentColor = Color.parseColor(rules.commentColor)
        val stringColor = Color.parseColor(rules.stringColor)

        // 1. Find and highlight keywords
        val keywordPattern = Pattern.compile("\\b(${rules.keywords.joinToString("|")})\\b")
        val keywordMatcher = keywordPattern.matcher(editable)
        while (keywordMatcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(keywordColor),
                keywordMatcher.start(),
                keywordMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Find and highlight strings
        val stringPattern = Pattern.compile("(${rules.stringDelimiter})[^\n]*?(\\1)")
        val stringMatcher = stringPattern.matcher(editable)
        while (stringMatcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(stringColor),
                stringMatcher.start(),
                stringMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Find and highlight comments
        val commentPattern = Pattern.compile("^\\s*(${rules.commentSymbols.joinToString("|")}).*")
        val commentMatcher = commentPattern.matcher(editable)
        while (commentMatcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(commentColor),
                commentMatcher.start(),
                commentMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        isUpdatingText = false
    }

    // -- File Operations --
    private fun newFile() {
        editor.setText("")
        fileName.text = "untitled.kt"
        currentFileUri = null
        undoStack.clear()
        redoStack.clear()
        // Default to Kotlin highlighting for new files
        activeRule = allRules?.rules?.find { it.name == "kotlin" }
        editor.text?.let { activeRule?.let { rule -> applySyntaxHighlighting(it, rule) } }
    }

    private fun openFile(uri: Uri) {
        contentResolver.openInputStream(uri)?.bufferedReader().use {
            val text = it?.readText() ?: ""
            editor.setText(text)
            editor.setSelection(editor.text.length)
        }
        currentFileUri = uri
        
        // Get the file name to determine the file extension
        val name = getFileName(uri)
        fileName.text = name

        // Extract the file extension
        val fileExtension = name.substringAfterLast('.', "").lowercase(Locale.getDefault())

        // Find the matching rule based on the file extension
        val matchingRule = allRules?.rules?.find { it.fileExtensions.contains(fileExtension) }

        // Set the active rule; default to Kotlin if no match is found
        activeRule = matchingRule ?: allRules?.rules?.find { it.name == "kotlin" }
        
        // Re-apply the highlighting with the new rule
        editor.text?.let { activeRule?.let { rule -> applySyntaxHighlighting(it, rule) } }
        
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
        var name = "untitled.kt"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    // -- Find & Replace --
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

    // -- Undo / Redo --
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

    // -- Copy / Cut / Paste --
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

    private fun compileCode() {
        val code = editor.text.toString()
    
        if (code.isBlank()) {
            showCompileResult("No code to compile!", false)
            return
        }
    
        // Dummy compile: check if it contains 'fun main'
        if ("fun main" in code) {
            showCompileResult("Compilation successful ✅", true)
        } else {
            showCompileResult("Compilation failed: Missing entry point (fun main).", false)
        }
    }
    private fun showCompileResult(message: String, success: Boolean) {
        // Update status TextView
        status.text = message
        status.setTextColor(if (success) Color.GREEN else Color.RED)
    
        // Optional: show alert dialog
        AlertDialog.Builder(this)
            .setTitle(if (success) "Success" else "Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }    

}
