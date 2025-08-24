package com.example.texteditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var lineNumbers: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var fileName: TextView

    private var currentFileName: String = "untitled.txt"

    private val CREATE_FILE_REQUEST = 1
    private val OPEN_FILE_REQUEST = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)
        lineNumbers = findViewById(R.id.lineNumbers)
        menuButton = findViewById(R.id.menuButton)
        fileName = findViewById(R.id.fileName)

        // Match line number TextView height with EditText
        lineNumbers.setLineSpacing(editor.lineSpacingExtra, editor.lineSpacingMultiplier)
        lineNumbers.textSize = editor.textSize / resources.displayMetrics.scaledDensity
        lineNumbers.typeface = editor.typeface

        // Function to update line numbers
        fun updateLineNumbers() {
            val lines = editor.lineCount
            val builder = StringBuilder()
            for (i in 1..lines) builder.append(i).append("\n")
            lineNumbers.text = builder.toString()
        }

        // Update line numbers initially and on text change
        editor.viewTreeObserver.addOnGlobalLayoutListener { updateLineNumbers() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateLineNumbers()
                val text = s.toString()
                val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                val chars = text.length
                status.text = "Words: $words | Chars: $chars"
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Set default file name
        fileName.text = currentFileName

        // Hamburger menu
        menuButton.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.menu.add("New File")
            popup.menu.add("Open")
            popup.menu.add("Save")
            popup.menu.add("Find")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "New File" -> newFile()
                    "Save" -> saveFileWithPicker()
                    "Open" -> openFileWithPicker()
                    "Find" -> findTextDialog()
                }
                true
            }
            popup.show()
        }
    }

    // ---------------- File Helpers ----------------

    private fun saveFileWithPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, currentFileName)
        }
        startActivityForResult(intent, CREATE_FILE_REQUEST)
    }

    private fun openFileWithPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, OPEN_FILE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            when (requestCode) {
                CREATE_FILE_REQUEST -> saveToUri(uri)
                OPEN_FILE_REQUEST -> openFromUri(uri)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "untitled.txt"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex("_display_name")
                if (index != -1) {
                    name = cursor.getString(index)
                }
            }
        }
        return name
    }
    
    private fun saveToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(editor.text.toString().toByteArray())
            }
            currentFileName = getFileName(uri)
            fileName.text = currentFileName
            Toast.makeText(this, "Saved file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().use { it.readText() }
                editor.setText(content)
            }
            currentFileName = getFileName(uri)
            fileName.text = currentFileName
            Toast.makeText(this, "File opened", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }
    

    // ---------------- Editor Helpers ----------------

    private fun newFile() {
        editor.setText("")
        currentFileName = "untitled.txt"
        fileName.text = currentFileName
        Toast.makeText(this, "New file created", Toast.LENGTH_SHORT).show()
    }

    private fun findText(query: String) {
        val content = editor.text.toString()
        val start = content.indexOf(query)
        if (start >= 0) {
            editor.setSelection(start, start + query.length)
        } else {
            Toast.makeText(this, "Text not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findTextDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Find Text")
        val input = EditText(this)
        builder.setView(input)
        builder.setPositiveButton("Find") { _, _ ->
            val query = input.text.toString()
            if (query.isNotEmpty()) findText(query)
            else Toast.makeText(this, "Enter text to find", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}
