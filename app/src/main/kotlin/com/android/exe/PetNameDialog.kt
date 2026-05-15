package com.android.exe

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.util.TypedValue

object PetNameDialog {

    fun show(context: Context, currentName: String = "Fluffy", callback: (String) -> Unit) {
        val editText = EditText(context).apply {
            setText(currentName)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            hint = "Enter pet name"
            setSingleLine(true)
            setSelection(text.length)
        }

        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 16, 48, 16)
            addView(editText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Pet Name")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().ifBlank { currentName }
                callback(newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
