package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "subtemplatereference")
class SubTemplateReference : UppaalPojo {
    @field:Element(name = "name", required = false)
    var name: Name? = null

    @field:Element(name = "subtemplatename", required = false)
    var subtemplatename: SubTemplateName? = null

    @field:ElementList(name = "boundarypoint", required = false, inline = true)
    var boundarypoints: MutableList<Location> = ArrayList()

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Attribute(name = "width")
    var width: Int = 0

    @field:Attribute(name = "height")
    var height: Int = 0
}