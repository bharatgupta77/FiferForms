package com.fifer.forms

import com.google.gson.annotations.SerializedName

data class DomainConfig(
    val domain: String,
    val label: String,
    val fields: List<FieldConfig>
)

data class FieldConfig(
    val id: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val options: List<String>? = null,
    val repeating: Boolean = false,
    val fields: List<FieldConfig>? = null,
    @SerializedName("visible_when") val visibleWhen: VisibleWhen? = null,
    val derived: Derived? = null,
    @SerializedName("user_overridable") val userOverridable: Boolean? = null
)

data class VisibleWhen(
    val field: String,
    val equals: String
)

data class Derived(
    val operation: String,
    val over: String? = null,
    val factors: List<String>? = null,
    val field: String? = null,
    val from: String? = null
)
