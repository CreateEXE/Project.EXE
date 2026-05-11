package com.android.exe.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.android.exe.R
import com.android.exe.data.PetDatabase
import kotlinx.coroutines.launch

class PetNameDialog : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.dialog_pet_name, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val et = view.findViewById<EditText>(R.id.etPetName)
        val db = PetDatabase.getInstance(requireContext())

        lifecycleScope.launch {
            et.setText(db.petProfileDao().getActive()?.petName ?: "Exe")
        }

        view.findViewById<Button>(R.id.btnSaveName).setOnClickListener {
            val name = et.text.toString().trim().ifBlank { "Exe" }
            lifecycleScope.launch {
                val profile = db.petProfileDao().getActive() ?: return@launch
                db.petProfileDao().update(profile.copy(petName = name))
                dismiss()
            }
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dismiss() }
    }
}
