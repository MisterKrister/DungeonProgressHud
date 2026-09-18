package dev.krister.dungeonprogresshud

/** Gson can assign JSON null to Kotlin non-null fields. Reject it at the persistence boundary. */
internal object HistoryValidation {
    fun validate(state: RunState) {
        validateObject(state, "history")
        require(state.totalChestsOpened >= 0 && state.totalKismetsUsed >= 0) { "Negative historical counters" }
        require(state.chestProfitWindowMillis >= 0) { "Negative tracker window" }
        state.chestProfits.forEach { require(it.timestamp >= 0) { "Negative chest timestamp" } }
        state.runs.forEach { require(it.timestamp >= 0 && it.rawCataXp >= 0) { "Invalid run measurements" } }
    }

    private fun validateObject(value: Any, path: String) {
        for (field in value.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
            field.isAccessible = true
            val child = field.get(value)
            if (field.name == "hudLineOrder") continue // Explicitly nullable legacy preference.
            require(child != null) { "$path.${field.name} is null" }
            when (child) {
                is Collection<*> -> child.forEachIndexed { index, element ->
                    require(element != null) { "$path.${field.name}[$index] is null" }
                    if (element.javaClass.packageName == RunState::class.java.packageName && element !is Enum<*>) {
                        validateObject(element, "$path.${field.name}[$index]")
                    }
                }
                is Map<*, *> -> require(child.keys.none { it == null } && child.values.none { it == null }) { "$path.${field.name} contains null" }
                else -> if (child.javaClass.packageName == RunState::class.java.packageName && child !is Enum<*>) {
                    validateObject(child, "$path.${field.name}")
                }
            }
        }
    }
}
