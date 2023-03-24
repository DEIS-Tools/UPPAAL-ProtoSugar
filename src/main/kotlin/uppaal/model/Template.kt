package uppaal.model

import org.simpleframework.xml.*

@Root(name = "template")
class Template() : UppaalPojo {
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


    constructor(name: Name,
                parameter: Parameter?,
                declaration: Declaration?,
                locations: MutableList<Location>,
                branchpoints: MutableList<Branchpoint>,
                boundarypoints: MutableList<BoundaryPoint>,
                subtemplatereferences: MutableList<SubTemplateReference>,
                init: Init?,
                transitions: MutableList<Transition>)
            : this()
    {
        this.name = name
        this.parameter = parameter
        this.declaration = declaration
        this.locations = locations
        this.branchpoints = branchpoints
        this.boundarypoints = boundarypoints
        this.subtemplatereferences = subtemplatereferences
        this.init = init
        this.transitions = transitions
    }


    fun clone(): Template = Template(
        name.clone(),
        parameter?.clone(),
        declaration?.clone(),
        locations.asSequence().map { it.clone() }.toMutableList(),
        branchpoints.asSequence().map { it.clone() }.toMutableList(),
        boundarypoints.asSequence().map { it.clone() }.toMutableList(),
        subtemplatereferences.asSequence().map { it.clone() }.toMutableList(),
        init?.clone(),
        transitions.asSequence().map { it.clone() }.toMutableList()
    )
}