package uppaal_pojo

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
    var branchpoint: MutableList<Branchpoint> = ArrayList()

    @field:Element(name = "init", required = false)
    var init: Init? = null

    @field:ElementList(name = "transition", required = false, inline = true)
    var transitions: MutableList<Transition> = ArrayList()
}