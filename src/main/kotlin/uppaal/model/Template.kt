package uppaal.model

import org.simpleframework.xml.*

@Root(name = "template")
class Template : UppaalPojo {
    @field:Element(name = "name", required = false)
    var name: Name = Name()

    @field:Element(name = "parameter", required = false)
    var parameter: Parameter? = null

    @field:Element(name = "declaration", required = false)
    var declaration: Declaration? = null

    @field:ElementList(name = "location", required = false, inline = true)
    var locations: MutableList<Location> = ArrayList()

    @field:ElementList(name = "branchpoint", required = false, inline = true)
    var branchpoints: MutableList<Branchpoint> = ArrayList()

    @field:ElementList(name = "boundarypoint", required = false, inline = true)
    var boundarypoints: MutableList<BoundaryPoint> = ArrayList()

    @field:ElementList(name = "subtemplatereference", required = false, inline = true)
    var subtemplatereferences: MutableList<SubTemplateReference> = ArrayList()

    @field:Element(name = "init", required = false)
    var init: Init? = null

    @field:ElementList(name = "transition", required = false, inline = true)
    var transitions: MutableList<Transition> = ArrayList()
}