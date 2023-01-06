package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "location")
class Location : UppaalPojo {
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Element(name = "name", required = false)
    var name: Name? = null

    @field:ElementList(name = "label", required = false, inline = true)
    var labels: List<Label> = ArrayList()

    @field:Element(name = "urgent", required = false)
    var urgent: Urgent? = null

    @field:Element(name = "committed", required = false)
    var committed: Committed? = null
}