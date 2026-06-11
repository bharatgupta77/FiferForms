package com.fifer.forms

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.fifer.forms.databinding.ActivityFormBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFormBinding
    private var domain: String = ""
    private val fieldValues: MutableMap<String, Any?> = mutableMapOf()
    private val fieldViews: MutableMap<String, View> = mutableMapOf()
    private val fieldSetters: MutableMap<String, (Any?) -> Unit> = mutableMapOf()
    private val fieldTils: MutableMap<String, TextInputLayout> = mutableMapOf()
    private val tilByField: MutableMap<String, TextInputLayout> = mutableMapOf()
    private val groupValues: MutableMap<String, MutableList<MutableMap<String, Any?>>> = mutableMapOf()
    private val derivedViews: MutableMap<String, TextInputLayout> = mutableMapOf()
    private var allFields: List<FieldConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        domain = intent.getStringExtra("domain") ?: return

        lifecycleScope.launch {
            try {
                val config = RetrofitClient.api.getDomainConfig(domain)
                title = config.label
                allFields = config.fields

                binding.formContainer.addView(TextView(this@FormActivity).apply {
                    text = "SMART FILL"
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.08f
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also {
                        it.topMargin = (4 * resources.displayMetrics.density).toInt()
                        it.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                })

                val noteLayout = TextInputLayout(
                    this@FormActivity, null,
                    com.google.android.material.R.attr.textInputOutlinedStyle
                ).apply {
                    hint = "Paste a note to auto-fill"
                    layoutParams = wrapLayoutParams()
                }
                val noteEditText = TextInputEditText(noteLayout.context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    maxLines = 5
                }
                noteLayout.addView(noteEditText)
                binding.formContainer.addView(noteLayout)

                binding.formContainer.addView(MaterialButton(this@FormActivity).apply {
                    text = "Parse"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                    setOnClickListener {
                        parseAndFill(noteEditText.text?.toString() ?: "")
                    }
                })

                binding.formContainer.addView(View(this@FormActivity).apply {
                    val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorControlHighlight))
                    setBackgroundColor(ta.getColor(0, 0x1F000000))
                    ta.recycle()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * resources.displayMetrics.density).toInt()
                    ).also { it.bottomMargin = (16 * resources.displayMetrics.density).toInt() }
                })

                for (field in config.fields) {
                    val view: View = if (field.derived != null) {
                        makeDerivedView(field.label).also { til ->
                            derivedViews[field.id] = til
                            fieldViews[field.id] = til
                            tilByField[field.id] = til
                        }
                    } else {
                        when (field.type) {
                            "group" -> makeGroupView(field)
                            else -> makeFieldView(field) { value ->
                                fieldValues[field.id] = value
                                applyVisibility()
                                recomputeDerived()
                            }.also { registerSetter(field, it) }
                        }.also { fieldViews[field.id] = it }
                    }
                    binding.formContainer.addView(view)
                }

                binding.formContainer.addView(MaterialButton(this@FormActivity).apply {
                    text = "Save"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (8 * resources.displayMetrics.density).toInt() }
                    setOnClickListener { saveRecord() }
                })

                applyVisibility()
                recomputeDerived()
            } catch (e: Exception) {
                val tv = TextView(this@FormActivity)
                tv.text = "Error: ${e.message}"
                binding.formContainer.addView(tv)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun collectFormData(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>()
        for (field in allFields) {
            if (field.derived != null) continue
            if (field.type == "group") {
                payload[field.id] = groupValues[field.id] ?: continue
                continue
            }
            if (fieldViews[field.id]?.visibility == View.GONE) continue
            val value = fieldValues[field.id] ?: continue
            payload[field.id] = if (field.type == "number") {
                value.toString().toDoubleOrNull() ?: value
            } else {
                value
            }
        }
        return payload
    }

    private fun saveRecord() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createRecord(domain, collectFormData())
                Toast.makeText(
                    this@FormActivity,
                    "Saved! Record #${response.id}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: HttpException) {
                // e.response()?.errorBody()?.string() contains the raw JSON from the backend,
                // e.g. FastAPI 422: {"detail":[{"loc":["body","field"],"msg":"...","type":"..."}]}
                val errorBody = e.response()?.errorBody()?.string() ?: e.message()
                Toast.makeText(this@FormActivity, errorBody, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@FormActivity, e.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Registers a setter for a top-level field so parseAndFill can push values into it.
    // Setters update both the displayed widget and fieldValues.
    private fun registerSetter(field: FieldConfig, view: View) {
        when (field.type) {
            "text", "number", "date" -> {
                val til = view as? TextInputLayout ?: return
                fieldTils[field.id] = til
                fieldSetters[field.id] = { value ->
                    til.editText?.setText(value?.toString() ?: "")
                    fieldValues[field.id] = value?.toString() ?: ""
                }
            }
            "enum" -> {
                val til = view as? TextInputLayout ?: return
                fieldTils[field.id] = til
                fieldSetters[field.id] = { value ->
                    (til.editText as? MaterialAutoCompleteTextView)
                        ?.setText(value?.toString() ?: "", false)
                    fieldValues[field.id] = value?.toString() ?: ""
                }
            }
            "multiselect" -> {
                val container = view as? LinearLayout ?: return
                val chipGroup = container.tag as? ChipGroup ?: return
                fieldSetters[field.id] = { value ->
                    @Suppress("UNCHECKED_CAST")
                    val selected = (value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    for (i in 0 until chipGroup.childCount) {
                        val chip = chipGroup.getChildAt(i) as? Chip ?: continue
                        chip.isChecked = chip.text?.toString() in selected
                    }
                    fieldValues[field.id] = selected
                }
            }
        }
    }

    private fun parseAndFill(noteText: String) {
        if (noteText.isBlank()) return
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.parseDomain(domain, mapOf("text" to noteText))
                for ((fieldId, value) in response.prefilled) {
                    fieldSetters[fieldId]?.invoke(value)
                }
                applyVisibility()
                recomputeDerived()

                for (til in fieldTils.values) {
                    til.helperText = null
                    til.isHelperTextEnabled = false
                }

                for ((fieldId, message) in response.fieldWarnings) {
                    fieldTils[fieldId]?.let { til ->
                        til.isHelperTextEnabled = true
                        til.helperText = message
                        til.setHelperTextColor(ColorStateList.valueOf(Color.parseColor("#FFC107")))
                    }
                }

                val unattachedWarnings = response.warnings.filter { warning ->
                    response.fieldWarnings.values.none { it == warning }
                }
                if (unattachedWarnings.isNotEmpty()) {
                    AlertDialog.Builder(this@FormActivity)
                        .setTitle("Review these")
                        .setMessage(unattachedWarnings.joinToString("\n"))
                        .setPositiveButton("OK", null)
                        .show()
                } else if (response.fieldWarnings.isNotEmpty()) {
                    Toast.makeText(this@FormActivity, "Review highlighted fields", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FormActivity, "Form pre-filled", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FormActivity, "Parse failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Renders a single field into a View and calls onValueChange whenever its value changes.
    // Used for both top-level fields and nested fields inside group rows.
    private fun makeFieldView(field: FieldConfig, onValueChange: (Any?) -> Unit): View {
        return when (field.type) {
            "text" -> makeTextInput(field.label, InputType.TYPE_CLASS_TEXT).also { til ->
                til.editText?.addTextChangedListener { s ->
                    onValueChange(s?.toString() ?: "")
                }
            }
            "number" -> makeTextInput(
                field.label,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            ).also { til ->
                til.editText?.addTextChangedListener { s ->
                    onValueChange(s?.toString() ?: "")
                }
            }
            "enum" -> makeDropdown(field.label, field.options ?: emptyList()).also { til ->
                (til.editText as? MaterialAutoCompleteTextView)
                    ?.setOnItemClickListener { parent, _, position, _ ->
                        onValueChange(parent.getItemAtPosition(position) as String)
                    }
            }
            "date" -> makeDatePicker(field.label, field.id) { dateStr ->
                onValueChange(dateStr)
            }
            "multiselect" -> makeMultiselect(field.label, field.options ?: emptyList(), onValueChange)
            else -> TextView(this).also {
                it.text = "[unsupported: ${field.type}] ${field.label}"
            }
        }
    }

    private fun makeMultiselect(label: String, options: List<String>, onValueChange: (Any?) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapLayoutParams()
        }
        container.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * resources.displayMetrics.density).toInt() }
        })
        val chipGroup = ChipGroup(this).apply { isSingleSelection = false }
        for (option in options) {
            chipGroup.addView(Chip(this).apply {
                text = option
                isCheckable = true
            })
        }
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            onValueChange(checkedIds.mapNotNull { id -> group.findViewById<Chip>(id)?.text?.toString() })
        }
        container.addView(chipGroup)
        container.tag = chipGroup  // direct reference so registerSetter doesn't rely on child index
        return container
    }

    private fun makeDerivedView(label: String): TextInputLayout {
        val til = makeTextInput(label, InputType.TYPE_NULL)
        til.editText?.apply {
            isFocusable = false
            isClickable = false
        }
        return til
    }

    private fun recomputeDerived() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (field in allFields) {
            val d = field.derived ?: continue
            val result = when (d.operation) {
                "sum_product" -> {
                    val rows = groupValues[d.over] ?: emptyList()
                    val factors = d.factors ?: emptyList()
                    val total = rows.sumOf { row ->
                        factors.fold(1.0) { acc, f ->
                            acc * (row[f]?.toString()?.toDoubleOrNull() ?: 0.0)
                        }
                    }
                    "%.2f".format(Locale.US, total)
                }
                "sum" -> {
                    val rows = groupValues[d.over] ?: emptyList()
                    val total = rows.sumOf { row ->
                        row[d.field]?.toString()?.toDoubleOrNull() ?: 0.0
                    }
                    "%.2f".format(Locale.US, total)
                }
                "days_since" -> {
                    val dateStr = fieldValues[d.from]?.toString()
                    if (dateStr.isNullOrBlank()) "" else try {
                        val fromDate = sdf.parse(dateStr)
                        val todayDate = sdf.parse(sdf.format(Date()))
                        if (fromDate == null || todayDate == null) "" else
                            ((todayDate.time - fromDate.time) / (1000L * 60 * 60 * 24)).toString()
                    } catch (e: Exception) { "" }
                }
                else -> ""
            }
            derivedViews[field.id]?.editText?.setText(result)
        }
    }

    private fun makeGroupView(field: FieldConfig): View {
        groupValues[field.id] = mutableListOf()

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapLayoutParams()
        }

        container.addView(TextView(this).apply {
            text = field.label.uppercase(Locale.getDefault())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = (4 * density).toInt()
                it.bottomMargin = (8 * density).toInt()
            }
        })

        val rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(rowsContainer)

        container.addView(MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "+ Add Row"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * density).toInt() }
            setOnClickListener {
                addGroupRow(field, rowsContainer)
                recomputeDerived()
            }
        })

        addGroupRow(field, rowsContainer)
        return container
    }

    private fun addGroupRow(field: FieldConfig, rowsContainer: LinearLayout) {
        val rowData: MutableMap<String, Any?> = mutableMapOf()
        groupValues[field.id]?.add(rowData)

        val density = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * density).toInt() }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
        }

        for (nestedField in field.fields ?: emptyList()) {
            inner.addView(makeFieldView(nestedField) { value ->
                rowData[nestedField.id] = value
                recomputeDerived()
            })
        }

        inner.addView(MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Remove"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.END
                it.topMargin = (4 * density).toInt()
            }
            setOnClickListener {
                groupValues[field.id]?.remove(rowData)
                rowsContainer.removeView(card)
                recomputeDerived()
            }
        })

        card.addView(inner)
        rowsContainer.addView(card)
    }

    private fun applyVisibility() {
        for (field in allFields) {
            val vw = field.visibleWhen ?: continue
            val show = fieldValues[vw.field] == vw.equals
            fieldViews[field.id]?.let { it.visibility = if (show) View.VISIBLE else View.GONE }
        }
    }

    // hint lives on TextInputLayout (not the EditText) so the label floats on focus/input
    private fun makeTextInput(label: String, inputType: Int): TextInputLayout {
        val til = TextInputLayout(
            this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = label
            layoutParams = wrapLayoutParams()
        }
        val et = TextInputEditText(til.context).apply {
            this.inputType = inputType
        }
        til.addView(et)
        return til
    }

    private fun makeDropdown(label: String, options: List<String>): TextInputLayout {
        val til = TextInputLayout(
            this, null,
            com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
        )
        til.hint = label
        til.layoutParams = wrapLayoutParams()
        val acv = MaterialAutoCompleteTextView(til.context)
        acv.inputType = InputType.TYPE_NULL
        acv.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, options))
        til.addView(acv)
        return til
    }

    private fun makeDatePicker(
        label: String,
        fieldId: String,
        onPicked: (String) -> Unit = {}
    ): TextInputLayout {
        val til = makeTextInput(label, InputType.TYPE_NULL)
        val et = til.editText!!
        et.isFocusable = false
        et.isClickable = true
        et.setOnClickListener { openDatePicker(label, fieldId, et, onPicked) }
        til.setEndIconOnClickListener { openDatePicker(label, fieldId, et, onPicked) }
        return til
    }

    private fun openDatePicker(
        label: String,
        fieldId: String,
        target: android.widget.EditText,
        onPicked: (String) -> Unit
    ) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(label)
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val dateStr = sdf.format(Date(ms))
            target.setText(dateStr)
            onPicked(dateStr)
        }
        picker.show(supportFragmentManager, "date_$fieldId")
    }

    private fun wrapLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = (8 * resources.displayMetrics.density).toInt() }
}
