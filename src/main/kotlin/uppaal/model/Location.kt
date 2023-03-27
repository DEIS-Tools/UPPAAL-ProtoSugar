package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "location")
class Location() : UppaalPojo {
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Element(name = "name", required = false)
    var name: Name? = null

    @field:ElementList(name = "label", required = false, inline = true)
    var labels: MutableList<Label> = ArrayList()

    @field:Element(name = "urgent", required = false)
    var urgent: Urgent? = null

    @field:Element(name = "committed", required = false)
    var committed: Committed? = null


    override var parent: UppaalPojo? = null


    constructor(id: String, x: Int, y: Int, name: Name?, labels: MutableList<Label>, urgent: Urgent?, committed: Committed?) : this() {
        this.id = id
        this.x = x
        this.y = y
        this.name = name
        this.labels = labels
        this.urgent = urgent
        this.committed = committed
    }


    fun clone(): Location =
        Location(id, x, y, name?.clone(), labels.map { it.clone() }.toMutableList(), urgent?.clone(), committed?.clone())
}