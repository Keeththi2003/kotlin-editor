package com.example.texteditor

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var lineNumbers: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var fileName: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)
        lineNumbers = findViewById(R.id.lineNumbers)
        menuButton = findViewById(R.id.menuButton)
        fileName = findViewById(R.id.fileName)

        // Update line numbers function
        fun updateLineNumbers() {
            val lines = editor.lineCount
            val builder = StringBuilder()
            for (i in 1..lines) builder.append(i).append("\n")
            lineNumbers.text = builder.toString()
        }

        // Update line numbers initially and when text changes
        editor.viewTreeObserver.addOnGlobalLayoutListener { updateLineNumbers() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateLineNumbers()

                // Update word & char count
                val text = s.toString()
                val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                val chars = text.length
                status.text = "Words: $words | Chars: $chars"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Hamburger menu actions
        menuButton.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.menu.add("New File")
            popup.menu.add("Open")
            popup.menu.add("Save")
            popup.menu.add("Find")
            popup.show()
        }

        // Set default file name
        fileName.text = "untitled.txt"
    }
}
