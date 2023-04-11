package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "subtemplatereference")
class SubTemplateReference() : UppaalPojo {
    @field:Element(name = "name", required = false)
    var name: Name? = null

    @field:Element(name = "subtemplatename", required = false)
    var subtemplatename: SubTemplateName? = null

    @field:Element(name = "arguments", required = false)
    var arguments: Arguments? = null

    @field:ElementList(name = "boundarypoint", required = false, inline = true)
    var boundarypoints: MutableList<BoundaryPoint> = ArrayList()

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Attribute(name = "width")
    var width: Int = 0

    @field:Attribute(name = "height")
    var height: Int = 0


    override var parent: UppaalPojo? = null


    constructor(name: Name?,
                subtemplatename: SubTemplateName?,
                arguments: Arguments?,
                boundarypoints: MutableList<BoundaryPoint>,
                x: Int,
                y: Int,
                width: Int,
                height: Int)
            : this() {
        this.name = name
        this.subtemplatename = subtemplatename
        this.arguments = arguments
        this.boundarypoints = boundarypoints
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }


    fun clone(): SubTemplateReference = SubTemplateReference(
        name?.clone(),
        subtemplatename?.clone(),
        arguments?.clone(),
        boundarypoints.asSequence().map { it.clone() }.toMutableList(),
        x, y, width, height
    )
}