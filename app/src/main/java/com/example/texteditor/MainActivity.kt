package com.example.texteditor

// =============== Imports ===============
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.*
import java.util.regex.Pattern

// =============== Data Models ===============
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

data class SyntaxRules(
    @SerializedName("languages") val rules: List<LanguageRule>
)

// =============== Main Activity ===============
class MainActivity : AppCompatActivity() {

    // ---------- UI Elements ----------
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var lineNumbers: TextView
    private lateinit var fileName: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var copyButton: ImageButton
    private lateinit var cutButton: ImageButton
    private lateinit var pasteButton: ImageButton
    private lateinit var compileButton: ImageButton

    // ---------- State ----------
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var currentFileUri: Uri? = null
    private var isUpdatingText = false
    private var lastFindIndex = 0

    // ---------- Syntax Highlighting ----------
    private var allRules: SyntaxRules? = null
    private var activeRule: LanguageRule? = null

    // ---------- Activity Results ----------
    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openFile(it) }
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        uri?.let { saveToUri(it) }
    }

    // ========================================
    //  onCreate
    // ========================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSyntaxRules()
        setupEditor()
        setupDrawerMenu()
        setupToolbars()

        fileName.text = "untitled.kt"
    }

    // =============== Initialization ===============
    private fun initViews() {
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
    }

    private fun setupEditor() {
        // Sync line numbers
        lineNumbers.setLineSpacing(editor.lineSpacingExtra, editor.lineSpacingMultiplier)
        lineNumbers.textSize = editor.textSize / resources.displayMetrics.scaledDensity
        lineNumbers.typeface = editor.typeface

        // TextWatcher for Undo/Redo & Highlighting
        editor.addTextChangedListener(object : TextWatcher {
            private var previousText = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUpdatingText) previousText = s.toString()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingText) return
                if (previousText != s.toString()) {
                    undoStack.push(previousText)
                    redoStack.clear()
                }
                updateLineNumbers()
                updateWordCharCount()
                s?.let { activeRule?.let { rule -> applySyntaxHighlighting(it, rule) } }
            }
        })

        editor.viewTreeObserver.addOnGlobalLayoutListener { updateLineNumbers() }
    }

    private fun setupDrawerMenu() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)

        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        navigationView.setNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.nav_new -> newFile()
                R.id.nav_open -> openFileLauncher.launch(arrayOf("*/*"))
                R.id.nav_save -> saveCurrentFile()
                R.id.nav_find -> showFindReplaceDialog()
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun setupToolbars() {
        undoButton.setOnClickListener { undo() }
        redoButton.setOnClickListener { redo() }
        compileButton.setOnClickListener { compileCode() }
        copyButton.setOnClickListener { copy() }
        cutButton.setOnClickListener { cut() }
        pasteButton.setOnClickListener { paste() }
    }

    // =============== Helpers: Editor Status ===============
    private fun updateLineNumbers() {
        val lines = editor.lineCount
        val builder = StringBuilder()
        for (i in 1..lines) builder.append(i).append("\n")
        lineNumbers.text = builder.toString()
    }

    private fun updateWordCharCount() {
        val text = editor.text.toString()
        val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
        val chars = text.length
        status.text = "Words: $words | Chars: $chars"
    }

    // =============== Syntax Highlighting ===============
    private fun loadSyntaxRules() {
        try {
            val jsonString = assets.open("syntax_rules.json").bufferedReader().use { it.readText() }
            allRules = Gson().fromJson(jsonString, SyntaxRules::class.java)
            activeRule = allRules?.rules?.find { it.name == "kotlin" }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading syntax rules: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applySyntaxHighlighting(editable: Editable, rules: LanguageRule) {
        isUpdatingText = true
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach { editable.removeSpan(it) }

        val keywordColor = Color.parseColor(rules.keywordColor)
        val commentColor = Color.parseColor(rules.commentColor)
        val stringColor = Color.parseColor(rules.stringColor)

        // Keywords
        val keywordPattern = Pattern.compile("\\b(${rules.keywords.joinToString("|")})\\b")
        val keywordMatcher = keywordPattern.matcher(editable)
        while (keywordMatcher.find()) {
            editable.setSpan(ForegroundColorSpan(keywordColor), keywordMatcher.start(), keywordMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Strings
        val stringPattern = Pattern.compile("(${rules.stringDelimiter})[^\n]*?(\\1)")
        val stringMatcher = stringPattern.matcher(editable)
        while (stringMatcher.find()) {
            editable.setSpan(ForegroundColorSpan(stringColor), stringMatcher.start(), stringMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Comments
        val commentPattern = Pattern.compile("^\\s*(${rules.commentSymbols.joinToString("|")}).*")
        val commentMatcher = commentPattern.matcher(editable)
        while (commentMatcher.find()) {
            editable.setSpan(ForegroundColorSpan(commentColor), commentMatcher.start(), commentMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        isUpdatingText = false
    }

    // =============== File Operations ===============
    private fun newFile() {
        editor.setText("")
        fileName.text = "untitled.kt"
        currentFileUri = null
        undoStack.clear()
        redoStack.clear()
        setActiveRuleFromExtension("kt")
    }

    private fun openFile(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        editor.setText(text)
        editor.setSelection(editor.text.length)
        currentFileUri = uri

        val name = getFileName(uri)
        fileName.text = name
        setActiveRuleFromExtension(name.substringAfterLast('.', ""))
        undoStack.clear()
        redoStack.clear()
    }

    private fun saveToUri(uri: Uri) {
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(editor.text.toString()) }
        currentFileUri = uri
        val name = getFileName(uri)
        fileName.text = name
        setActiveRuleFromExtension(name.substringAfterLast('.', ""))
    }

    private fun saveCurrentFile() {
        if (currentFileUri != null) saveToUri(currentFileUri!!)
        else saveFileLauncher.launch("untitled.kt")
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

    private fun setActiveRuleFromExtension(extension: String) {
        val ext = extension.lowercase(Locale.getDefault())
        activeRule = allRules?.rules?.find { it.fileExtensions.contains(ext) } ?: allRules?.rules?.find { it.name == "kotlin" }
        editor.text?.let { activeRule?.let { rule -> applySyntaxHighlighting(it, rule) } }
    }

    // =============== Find & Replace ===============
    private fun showFindReplaceDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val findEdit = EditText(this).apply { hint = "Find" }
        val replaceEdit = EditText(this).apply { hint = "Replace" }
        val caseCheck = CheckBox(this).apply { text = "Case Sensitive" }
        val wordCheck = CheckBox(this).apply { text = "Whole Word" }

        layout.addView(findEdit); layout.addView(replaceEdit); layout.addView(caseCheck); layout.addView(wordCheck)

        AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(layout)
            .setPositiveButton("Find Next") { _, _ -> findNext(findEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked) }
            .setNeutralButton("Replace") { _, _ -> replaceCurrent(findEdit.text.toString(), replaceEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked) }
            .setNegativeButton("Replace All") { _, _ -> replaceAll(findEdit.text.toString(), replaceEdit.text.toString(), caseCheck.isChecked, wordCheck.isChecked) }
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
            if (regex.matches(selectedText)) editor.text.replace(selStart, selEnd, replace)
        }
        findNext(find, caseSensitive, wholeWord)
    }

    private fun replaceAll(find: String, replace: String, caseSensitive: Boolean, wholeWord: Boolean) {
        if (find.isEmpty()) return
        val pattern = if (wholeWord) "\\b${Regex.escape(find)}\\b" else Regex.escape(find)
        val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        editor.setText(regex.replace(editor.text.toString(), replace))
    }

    // =============== Undo / Redo ===============
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

    // =============== Copy / Cut / Paste ===============
    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        if (start != end) clipboard.setPrimaryClip(ClipData.newPlainText("text", editor.text.substring(start, end)))
    }

    private fun cut() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        if (start != end) {
            clipboard.setPrimaryClip(ClipData.newPlainText("text", editor.text.substring(start, end)))
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

    // =============== Compile Simulation ===============
    private fun compileCode() {
        val code = editor.text.toString()
        if (code.isBlank()) {
            showCompileResult("No code to compile!", false)
            return
        }

        val currentName = fileName.text.toString().ifEmpty { "Main.kt" }
        val file = File(filesDir, currentName).apply { writeText(code) }

        val fakeOutput = """
            Saved file: ${file.absolutePath}
            -> adb push ${file.absolutePath} /data/local/tmp/$currentName
            -> adb shell kotlinc /data/local/tmp/$currentName -include-runtime -d /data/local/tmp/Main.jar
            -> adb shell java -jar /data/local/tmp/Main.jar
            
            Compilation successful....
        """.trimIndent()

        showCompileResult(fakeOutput, true)
    }

    private fun showCompileResult(message: String, success: Boolean) {
        status.text = message
        status.setTextColor(if (success) Color.GREEN else Color.RED)
        AlertDialog.Builder(this)
            .setTitle(if (success) "Success" else "Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
