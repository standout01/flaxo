package org.flaxo.model.data

import java.util.*
import javax.persistence.CascadeType
import javax.persistence.Entity
import javax.persistence.FetchType
import javax.persistence.GeneratedValue
import javax.persistence.Id
import javax.persistence.OneToMany
import javax.persistence.ManyToOne
import javax.persistence.OneToOne
import javax.persistence.Table

/**
 * Course data object.
 */
@Entity(name = "course")
@Table(name = "course")
data class Course(
        @Id
        @GeneratedValue
        override val id: Long = -1,

        val name: String = "",

        val language: String = "",

        val testingLanguage: String = "",

        val testingFramework: String = "",

        @OneToOne(cascade = [CascadeType.ALL], optional = false, fetch = FetchType.LAZY)
        val state: CourseState = CourseState(),

        @ManyToOne(optional = false, fetch = FetchType.LAZY)
        val user: User = User(),

        @OneToMany(mappedBy = "course", orphanRemoval = true)
        val students: Set<Student> = emptySet(),

        @OneToMany(mappedBy = "course", orphanRemoval = true)
        val tasks: Set<Task> = emptySet()
) : Viewable, Identifiable {

    override fun view(): Any = let { course ->
        object {
            val id = course.id
            val name = course.name
            val language = course.language
            val testingLanguage = course.testingLanguage
            val testingFramework = course.testingFramework
            val state = course.state.view()
            val user = course.user.view()
            val students = course.students.map { it.nickname }
            val tasks = course.tasks.map { it.name }
        }
    }

    override fun hashCode() = Objects.hash(id)

    override fun equals(other: Any?) = other is Course && other.id == id
}