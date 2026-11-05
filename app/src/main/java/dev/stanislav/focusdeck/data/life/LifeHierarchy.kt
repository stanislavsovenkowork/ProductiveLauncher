package dev.stanislav.focusdeck.data.life

data class LifeTaskParents(
    val projectId: String?,
    val goalId: String?,
    val areaId: String?,
    val noteLink: String? = null,
)

/** Resolves the selected parent against the current index without rewriting notes or history. */
object LifeHierarchy {
    fun canonical(entry: LifeEntry, entries: List<LifeEntry>): LifeEntry = Resolver(entries).resolve(entry)

    fun taskParents(source: LifeEntry, entries: List<LifeEntry>): LifeTaskParents {
        val entry = canonical(source, entries)
        return when (entry.kind) {
            LifeKind.AREA -> LifeTaskParents(null, null, entry.id)
            LifeKind.GOAL -> LifeTaskParents(null, entry.id, entry.areaId)
            LifeKind.PROJECT -> LifeTaskParents(entry.id, entry.goalId, entry.areaId)
            LifeKind.DECISION, LifeKind.REVIEW -> LifeTaskParents(entry.projectId, entry.goalId, entry.areaId)
        }.copy(noteLink = source.wikiLink)
    }

    private class Resolver(entries: List<LifeEntry>) {
        private val byId = entries.groupBy { it.id }
        private val visiting = mutableSetOf<String>()

        fun resolve(entry: LifeEntry): LifeEntry {
            require(listOf(entry.areaId, entry.goalId, entry.projectId).none { it == entry.id }) {
                "Запись «${entry.title}» не может ссылаться сама на себя."
            }
            require(visiting.add(entry.id)) { "В родительских связях записи «${entry.title}» найден цикл." }
            try {
                when (entry.kind) {
                    LifeKind.AREA -> require(entry.areaId == null && entry.goalId == null && entry.projectId == null) {
                        "У сферы «${entry.title}» не может быть родительской записи."
                    }
                    LifeKind.GOAL -> require(entry.goalId == null && entry.projectId == null) {
                        "Родителем цели «${entry.title}» может быть только сфера."
                    }
                    LifeKind.PROJECT -> require(entry.projectId == null) {
                        "Проект «${entry.title}» нельзя связать с другим проектом как с родителем."
                    }
                    LifeKind.DECISION, LifeKind.REVIEW -> Unit
                }

                // Only the nearest selected parent is authoritative. Old inherited IDs may
                // already be dangling after a move; replace them instead of following them.
                val parents = when {
                    entry.projectId != null -> {
                        val project = resolve(parent(entry.projectId, LifeKind.PROJECT))
                        LifeTaskParents(project.id, project.goalId, project.areaId)
                    }
                    entry.goalId != null -> {
                        val goal = resolve(parent(entry.goalId, LifeKind.GOAL))
                        LifeTaskParents(null, goal.id, goal.areaId)
                    }
                    entry.areaId != null -> {
                        val area = resolve(parent(entry.areaId, LifeKind.AREA))
                        LifeTaskParents(null, null, area.id)
                    }
                    else -> LifeTaskParents(null, null, null)
                }
                return entry.copy(projectId = parents.projectId, goalId = parents.goalId, areaId = parents.areaId)
            } finally {
                visiting.remove(entry.id)
            }
        }

        private fun parent(id: String, kind: LifeKind): LifeEntry {
            val matches = byId[id].orEmpty()
            require(matches.isNotEmpty()) {
                "Связанная запись «$id» не найдена. Обнови список или выбери другую связь."
            }
            require(matches.size == 1) {
                "Найдено несколько записей с идентификатором «$id». Проверь копии заметок."
            }
            return matches.single().also {
                require(it.kind == kind) {
                    "Неверный тип связанной записи «$id»: ожидается «${kind.singular}», найден «${it.kind.singular}»."
                }
            }
        }
    }
}
