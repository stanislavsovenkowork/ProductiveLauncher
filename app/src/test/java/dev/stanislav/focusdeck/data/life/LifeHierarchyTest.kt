package dev.stanislav.focusdeck.data.life

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LifeHierarchyTest {
    private val day = LocalDate.of(2026, 9, 29)
    private fun entry(id: String, kind: LifeKind, area: String? = null, goal: String? = null, project: String? = null) =
        LifeEntry(id, kind, id, areaId = area, goalId = goal, projectId = project, created = day, updated = day)

    private val areaA = entry("area-aaa", LifeKind.AREA)
    private val areaB = entry("area-bbb", LifeKind.AREA)
    private val goalA = entry("goal-aaa", LifeKind.GOAL, area = areaA.id)
    private val goalB = entry("goal-bbb", LifeKind.GOAL, area = areaB.id)

    @Test fun movedGoalSuppliesCurrentAreaToProjectAndNewTask() {
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id, goalA.id)
        val movedGoal = goalA.copy(areaId = areaB.id)
        val entries = listOf(areaA, areaB, movedGoal, project)

        assertEquals(project.copy(areaId = areaB.id), LifeHierarchy.canonical(project, entries))
        assertEquals(LifeTaskParents(project.id, goalA.id, areaB.id, project.wikiLink), LifeHierarchy.taskParents(project, entries))
        assertEquals(areaA.id, project.areaId)
        assertEquals(project, entries.last())
    }

    @Test fun decisionUsesCurrentProjectAndItsCurrentGoalEvenWhenBothCopiesAreStale() {
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id, goalB.id)
        val decision = entry("decision-aaa", LifeKind.DECISION, areaA.id, goalA.id, project.id)
        val entries = listOf(areaA, areaB, goalA, goalB, project)

        assertEquals(decision.copy(goalId = goalB.id, areaId = areaB.id), LifeHierarchy.canonical(decision, entries))
        assertEquals(LifeTaskParents(project.id, goalB.id, areaB.id, decision.wikiLink), LifeHierarchy.taskParents(decision, entries))
    }

    @Test fun staleInheritedIdsNeedNotExistWhenSelectedParentHasMoved() {
        val project = entry("project-aaa", LifeKind.PROJECT, "removed-area", goalB.id)
        val decision = entry("decision-aaa", LifeKind.DECISION, "removed-area", "removed-goal", project.id)
        assertEquals(LifeTaskParents(project.id, goalB.id, areaB.id, decision.wikiLink),
            LifeHierarchy.taskParents(decision, listOf(project, goalB, areaB)))
    }

    @Test fun parentWithoutAncestorsClearsOldInheritedChoices() {
        val goal = goalA.copy(areaId = null)
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id, goal.id)
        assertNull(LifeHierarchy.canonical(project, listOf(goal)).areaId)

        val rootProject = project.copy(goalId = null, areaId = null)
        val decision = entry("decision-aaa", LifeKind.DECISION, areaA.id, goalA.id, project.id)
        assertEquals(LifeTaskParents(project.id, null, null, decision.wikiLink),
            LifeHierarchy.taskParents(decision, listOf(rootProject)))
    }

    @Test fun projectWithoutGoalKeepsItsDirectAreaForDecision() {
        val project = entry("project-aaa", LifeKind.PROJECT, areaB.id)
        val decision = entry("decision-aaa", LifeKind.DECISION, areaA.id, goalA.id, project.id)
        assertEquals(decision.copy(goalId = null, areaId = areaB.id),
            LifeHierarchy.canonical(decision, listOf(project, areaB)))
    }

    @Test fun directAreaGoalAndProjectExportTheirOwnIds() {
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id)
        val entries = listOf(areaA, goalA, project)
        assertEquals(LifeTaskParents(null, null, areaA.id, areaA.wikiLink), LifeHierarchy.taskParents(areaA, entries))
        assertEquals(LifeTaskParents(null, goalA.id, areaA.id, goalA.wikiLink), LifeHierarchy.taskParents(goalA, entries))
        assertEquals(LifeTaskParents(project.id, null, areaA.id, project.wikiLink), LifeHierarchy.taskParents(project, entries))
    }

    @Test fun absentParentsAreAllowedForEveryKind() {
        for (kind in LifeKind.entries) {
            val root = entry("root-${kind.name}", kind)
            assertEquals(root, LifeHierarchy.canonical(root, emptyList()))
            val expected = when (kind) {
                LifeKind.AREA -> LifeTaskParents(null, null, root.id)
                LifeKind.GOAL -> LifeTaskParents(null, root.id, null)
                LifeKind.PROJECT -> LifeTaskParents(root.id, null, null)
                else -> LifeTaskParents(null, null, null)
            }
            assertEquals(expected.copy(noteLink = root.wikiLink), LifeHierarchy.taskParents(root, emptyList()))
        }
    }

    @Test fun proposalKeepsExplicitRootChoiceInsteadOfUsingItsOldIndexedCopy() {
        val proposedGoal = goalA.copy(areaId = areaB.id)
        assertEquals(proposedGoal, LifeHierarchy.canonical(proposedGoal, listOf(goalA, areaA, areaB)))
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id, goalA.id)
        val proposedProject = project.copy(goalId = goalB.id)
        assertEquals(proposedProject.copy(areaId = areaB.id),
            LifeHierarchy.canonical(proposedProject, listOf(project, goalA, goalB, areaA, areaB)))
    }

    @Test fun decisionAndReviewCanHaveDirectGoalOrAreaWithoutProject() {
        for (kind in listOf(LifeKind.DECISION, LifeKind.REVIEW)) {
            val source = entry("source-${kind.name}", kind, areaB.id, goalA.id)
            assertEquals(LifeTaskParents(null, goalA.id, areaA.id, source.wikiLink),
                LifeHierarchy.taskParents(source, listOf(goalA, areaA)))
            assertEquals(LifeTaskParents(null, null, areaB.id, source.wikiLink),
                LifeHierarchy.taskParents(source.copy(goalId = null), listOf(areaB)))
        }
    }

    @Test fun directGoalAndProjectLinksUseActualSourcePathAndReadableTitle() {
        assertNull(LifeTaskParents(null, null, null).noteLink)
        for (kind in listOf(LifeKind.GOAL, LifeKind.PROJECT)) {
            val source = entry("renamed-${kind.name}", kind).copy(
                title = "Практика английского",
                path = "FocusDeck/${kind.folder}/Переименованная заметка.md",
            )
            val parents = LifeHierarchy.taskParents(source, listOf(source))
            assertEquals("[[FocusDeck/${kind.folder}/Переименованная заметка|Практика английского]]", parents.noteLink)
        }
    }

    @Test fun linkedDecisionKeepsItsOwnNoteLinkWhileResolvingProjectAncestors() {
        val project = entry("project-aaa", LifeKind.PROJECT, areaA.id, goalB.id)
            .copy(path = "FocusDeck/Проекты/Учебный проект.md", title = "Учебный проект")
        val decision = entry("decision-aaa", LifeKind.DECISION, areaA.id, goalA.id, project.id)
            .copy(path = "FocusDeck/Решения/Выбор формата.md", title = "Выбрать формат")
        val parents = LifeHierarchy.taskParents(decision, listOf(project, goalB, areaB))

        assertEquals(LifeTaskParents(project.id, goalB.id, areaB.id,
            "[[FocusDeck/Решения/Выбор формата|Выбрать формат]]"), parents)
        assertNotEquals(project.wikiLink, parents.noteLink)
    }

    @Test fun missingSelectedParentsFailInsteadOfFallingBackToStaleAncestors() {
        val sources = listOf(
            entry("goal-source", LifeKind.GOAL, area = "missing-area"),
            entry("project-source", LifeKind.PROJECT, areaA.id, "missing-goal"),
            entry("decision-source", LifeKind.DECISION, areaA.id, goalA.id, "missing-project"),
        )
        for (source in sources) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                LifeHierarchy.canonical(source, listOf(areaA, goalA))
            }
            assertTrue(error.message.orEmpty().contains("не найдена"))
        }
    }

    @Test fun missingAncestorInsideSelectedChainAlsoFails() {
        val project = entry("project-aaa", LifeKind.PROJECT, goal = goalA.id)
        val decision = entry("decision-aaa", LifeKind.DECISION, project = project.id)
        val error = assertThrows(IllegalArgumentException::class.java) {
            LifeHierarchy.taskParents(decision, listOf(project, goalA))
        }
        assertTrue(error.message.orEmpty().contains(areaA.id))
    }

    @Test fun wrongParentKindsAreRejected() {
        val project = entry("project-aaa", LifeKind.PROJECT)
        val sources = listOf(
            goalA.copy(areaId = project.id),
            project.copy(goalId = areaA.id),
            entry("decision-aaa", LifeKind.DECISION, project = goalA.id),
        )
        for (source in sources) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                LifeHierarchy.canonical(source, listOf(areaA, goalA, project))
            }
            assertTrue(error.message.orEmpty().contains("Неверный тип"))
        }
    }

    @Test fun selfReferencesAreRejectedEvenInInheritedFields() {
        val project = entry("project-aaa", LifeKind.PROJECT, goal = goalA.id)
        for (source in listOf(project.copy(areaId = project.id), project.copy(goalId = project.id),
            project.copy(projectId = project.id))) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                LifeHierarchy.canonical(source, listOf(areaA, goalA, project))
            }
            assertTrue(error.message.orEmpty().contains("сама на себя"))
        }
    }

    @Test fun malformedBackLinkInAncestorIsRejected() {
        val project = entry("project-aaa", LifeKind.PROJECT, goal = goalA.id)
        val cyclicGoal = goalA.copy(projectId = project.id)
        assertThrows(IllegalArgumentException::class.java) {
            LifeHierarchy.canonical(project, listOf(project, cyclicGoal, areaA))
        }
    }

    @Test fun duplicateSelectedParentIdsAreRejected() {
        val project = entry("project-aaa", LifeKind.PROJECT, goal = goalA.id)
        val error = assertThrows(IllegalArgumentException::class.java) {
            LifeHierarchy.canonical(project, listOf(goalA, goalA.copy(areaId = areaB.id), areaA, areaB))
        }
        assertTrue(error.message.orEmpty().contains("несколько записей"))
    }

    @Test fun independentMergeMismatchIsDetectableByComparingOnlyParents() {
        val merged = entry("project-aaa", LifeKind.PROJECT, areaB.id, goalA.id)
        val canonical = LifeHierarchy.canonical(merged, listOf(areaA, areaB, goalA))
        assertNotEquals(LifeTaskParents(merged.projectId, merged.goalId, merged.areaId),
            LifeTaskParents(canonical.projectId, canonical.goalId, canonical.areaId))
        assertEquals(merged.copy(areaId = areaA.id), canonical)
    }

    @Test fun canonicalIsIdempotentAndPreservesHistoryTextAndProvenance() {
        val source = entry("project-aaa", LifeKind.PROJECT, areaB.id, goalA.id).copy(
            status = LifeStatus.PAUSED, dueDate = day.plusDays(3), reviewDate = day.plusDays(7),
            reviewedOn = day, path = "FocusDeck/Проекты/Проект.md", raw = "Original Markdown",
            sourceRoot = "vault-one", fields = mapOf(LifeField.REVIEW_HISTORY to "Прежняя история",
                LifeField.LINKS to "[[Прежняя ссылка]]", LifeField.NEXT_ACTION to "Следующий шаг"))
        val entries = listOf(areaA, goalA)
        val result = LifeHierarchy.canonical(source, entries)
        assertEquals(source.copy(areaId = areaA.id), result)
        assertEquals(result, LifeHierarchy.canonical(result, entries))
        assertSame(source.fields, result.fields)
    }
}
