package org.flaxo.model.data

import org.flaxo.common.Identifiable
import org.flaxo.common.data.DateTime
import org.flaxo.model.PlagiarismReportView
import java.time.LocalDateTime
import java.util.Objects
import javax.persistence.CascadeType
import javax.persistence.Entity
import javax.persistence.FetchType
import javax.persistence.GeneratedValue
import javax.persistence.Id
import javax.persistence.ManyToOne
import javax.persistence.OneToMany
import javax.persistence.Table

/**
 * Plagiarism report entity.
 */
@Entity(name = "plagiarism_report")
@Table(name = "plagiarism_report")
data class PlagiarismReport(

        @Id
        @GeneratedValue
        override val id: Long = -1,

        val date: LocalDateTime = LocalDateTime.MIN,

        @ManyToOne(optional = false, fetch = FetchType.LAZY)
        val task: Task = Task(),

        val url: String = "",

        @OneToMany(cascade = [CascadeType.ALL])
        val matches: List<PlagiarismMatch> = mutableListOf()

) : Identifiable, Viewable<PlagiarismReportView> {

    override fun view(): PlagiarismReportView = PlagiarismReportView(
            id = id,
            url = url,
            date = DateTime(date),
            matches = matches.views()
    )

    override fun toString() = "${this::class.simpleName}(id=$id)"

    override fun hashCode() = Objects.hash(id)

    override fun equals(other: Any?): Boolean = this::class.isInstance(other) && other is Identifiable && other.id == id

}
